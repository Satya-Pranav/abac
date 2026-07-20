# OAuth + JDBC ABAC Flow (Phase 2 — the active model)

This supersedes the **no-OAuth** model (README §9A). We now inject a **real per-session OAuth
custom claim** instead of looking identity up from `ABAC_UserContext` by `current_user()`.
Two clients drive it: raw `curl` (SQL Statements API) and the **JDBC client** in [`JDBC/`](../../JDBC/).

Related files: [`../../README.md`](../../README.md) (master context), [`runbook.md`](runbook.md)
(raw runbook + exact commands), [`../testing/jdbc-cases.md`](../testing/jdbc-cases.md) (runnable cases
+ per-row trace of all 60 cases + 8 scenarios), [`sql/`](../../sql/) (the DDL),
[`abac_docs/`](../../abac_docs/) (customer source of truth).

---

## 1. What changed vs the no-OAuth phase

| | Phase 1 — no OAuth (README §9A) | Phase 2 — OAuth (this doc) |
| --- | --- | --- |
| Where identity comes from | `ABAC_UserContext` table row, looked up by `current_user()` | the **OAuth custom claim** in the access token |
| `get_user_context()` body | `SELECT … FROM ABAC_UserContext WHERE user_name = current_user()` | `from_json(current_oauth_custom_identity_claim(), '<struct>')` |
| Who sets `mode`/`root`/`permissions` | a static row per principal (set by an admin) | the **caller**, per session, in the claim JSON |
| Policy binds to | `abac_row_filter_test_wrapper` (or wrapper) | `abac_row_filter_wrapper` (the real one) |
| Objects dropped | — | `abac_row_filter_test_wrapper`, `get_test_user_context`, `ABAC_UserContext` no longer needed |

**Only `get_user_context()` changed** between the two phases — exactly as the customer designed it.
Everything downstream (wrapper, row filter, metadata tables, tags, policies) is identical.

---

## 2. The key discovery — how the custom claim actually gets in

The Databricks **OAuth token endpoint accepts a `custom_claim` parameter**. Whatever JSON you pass
is embedded in the minted access token, and inside the warehouse
`current_oauth_custom_identity_claim()` returns it. That single fact is what makes per-user ABAC
work **without any IdP / SSO wiring** — the caller carries the identity in the token.

```bash
curl -s -u "${CLIENT_ID}:${CLIENT_SECRET}" \
  -X POST "https://${WORKSPACE_HOST}/oidc/v1/token" \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "scope=all-apis" \
  --data-urlencode "custom_claim=${CUSTOM_CLAIM}"
```

The claim JSON is the ABAC context struct:

```json
{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer",
 "permissions":["Item","StoreSale"]}
```

> 🔑 **`user` is arbitrary — the caller chooses it.** It is *decoupled* from the service principal
> that authenticates. The SP is just the machine login; the claim's `user` is the *effective* ABAC
> identity — here a **dummy email seeded in `ABAC_EntitySubjectAssignment`** (`u.analyst1@example.com`
> / `u.vendor.mgr@example.com` / `u.developer@example.com`). This is why `subjectID` must be seeded
> to **whatever the claim's `user` will be** (see §5, rule 1).

Prerequisite: the SP was granted **data-editor** access for OAuth generation.

---

## 3. The JDBC client (`JDBC/src/.../AbacJdbcClient.java`)

A standalone reproduction of the customer's `abac_docs/Java/config/DatabricksConnectionProxy.java`,
using the real `com.databricks:databricks-jdbc:3.4.1` driver. It does the same **two-step auth** the
customer does — because the driver's normal OAuth M2M flow does *not* let you attach a `custom_claim`,
so you mint a plain token first and then hot-swap it for one that carries the claim.

**Interface:** `arg0 = ctx JSON`, `arg1 = SQL`. Env: `CLIENT_ID`, `CLIENT_SECRET`, `WORKSPACE_HOST`, `WAREHOUSE_ID`.

```bash
cd JDBC && mvn package        # -> target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar

java -jar target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar \
  '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT * FROM abac_tpcds.tpcds_1_delta.customer ORDER BY 1"
```

**What it does, step by step** (`AbacJdbcClient.injectCustomClaim()`):

1. Opens a normal JDBC connection with documented OAuth M2M props — `AuthMech=11`, `Auth_Flow=1`,
   `OAuth2ClientId`, `OAuth2Secret`. → mints an initial token with **no** custom_claim.
2. Unwraps to the driver-internal `DatabricksConnection`, gets the `DatabricksConfig`, and builds a
   `DatabricksTokenFederationProvider` wrapping an `OAuthM2MServicePrincipalCredentialsProvider`
   whose `configure()` sets `endpointParametersSupplier = () -> Map.of("custom_claim", ctxJson)` on a
   `ClientCredentials` token source.
3. `client.resetAccessToken(newProvider.getToken().getAccessToken())` — **hot-swaps** the live
   connection's token for the claim-carrying one.
4. Runs the SQL and prints rows.

> The driver's internal API (`com.databricks.jdbc.*`, `com.databricks.internal.sdk.core.oauth.*`) is
> **not** a stable public contract — it can change between driver versions. Pin the same
> `databricks-jdbc` version the customer uses to keep class/method signatures aligned.

### curl vs JDBC — use whichever fits

- **`curl` + SQL Statements API** (see [`runbook.md`](runbook.md)) — fast, no JVM,
  good for iterating through DDL and test scenarios.
- **JDBC client** — actually exercises the customer's real driver auth path (token hot-swap), proving
  the mechanism end-to-end, not just the REST shortcut. Both should give the same filtered result.

---

## 4. End-to-end query lifecycle under OAuth

Same "members-only archive" model as before — the one difference is **where your dossier comes from**:

- Phase 1 (no OAuth): the gatekeeper pulled your dossier from the **HR filing cabinet** (`ABAC_UserContext`) by your badge (`current_user()`).
- Phase 2 (OAuth): you hand over a **sealed envelope** (the `custom_claim` baked into your token); `get_user_context()` opens it via `current_oauth_custom_identity_claim()`.

Everything after that is identical:

```
client builds claim JSON
   └─ token exchange embeds it as custom_claim ────────────────┐
your query hits the warehouse (authenticated as the SP)        │
   1. policy engine finds the policy whose TO = this SP         │
   2. governed TAGS resolve which columns are id / org          │
   3. it appends WHERE abac_row_filter_wrapper(id,'customer',org)
   4. wrapper: entity_type_to_object_type('customer') -> 'Customer'
              + get_user_context() ── opens the sealed envelope ┘
   5. abac_row_filter(id,'Customer',org, ctx) runs per row:
        mode=DISABLE?  → show all
        non-root & object_type ∈ ctx.permissions? → show all of that table
        root type & explicit assignment for ctx.user? → show that row
   6. only passing rows return
```

---

## 5. Correctness rules we learned (the gotchas that decide allow vs deny)

1. **`claim.user` MUST equal `ABAC_EntitySubjectAssignment.subjectID`** (and `UserGroupMembers.memberID`
   for group grants). This is the #1 allow/deny lever. Empty/wrong `user` → **0 rows** (a valid deny test).
   Decide the identity once (SP app id **or** end-user email) and seed `subjectID` + set `claim.user` to
   the *same* string.
2. **The policy `TO` clause must target the SP application id** (`76d5804d-…`) — that is who the JDBC/curl
   session authenticates as. **Owners/metastore admins bypass row filters**, so validate as the SP, not as yourself.
3. **Row-filter branches — the full 3-branch filter is now deployed.**
   The deployed `abac_row_filter` matches the customer template (`sql/05_dataset_udfs.sql`) with all
   three OR-branches live:
   - **`DISABLE`** → show all rows (bypass).
   - **Middle / non-root permissions branch** — `ctx.root <> object_type AND array_contains(ctx.permissions, object_type)`:
     a **non-root** table now shows **all of its rows** when that table's object type is listed in
     `ctx.permissions`. So `ctx.permissions` is **live**, not inert — it grants coarse "see the whole
     related table" access.
   - **Root branch** — `ctx.root = object_type AND ( (ctx.mode='RBAC_ABAC' AND org_id IN <single-level
     children of ctx.org>) OR EXISTS(explicit assignment for ctx.user) )`: the two sub-conditions are
     additive. The explicit-assignment sub-condition is the ABAC per-row grant; the **RBAC_ABAC**
     sub-condition makes `ctx.org` **live**, granting org-subtree visibility on the root table.
   So for the root table, `root` must match the queried table; for related tables, listing their object
   type in `permissions` opens them. *(History: the initial cut deployed a simplified 2-branch filter
   — DISABLE + root/EXISTS only — since superseded by this full 3-branch redeploy.)* See §6 for the matrix.
4. **Permission format must match.** The middle branch compares against **object-type** strings
   (`'Item'`, `'StoreSale'`). If your claim `permissions` are `.view` strings (`"items.view"`), either
   switch the claim to object types, or switch the filter to the `.view`-mapping form
   (`concat(object_type_to_permission(object_type), '.view')`). Don't mix.
5. **`org_id` (the 3rd wrapper arg) is used by the `RBAC_ABAC` sub-condition of the root branch**
   (`org_id IN (single-level children of ctx.org)`). With the 3-branch filter deployed this is **live**:
   in `RBAC_ABAC` mode, a root-table row is visible when its `org_id` falls under `ctx.org`, so `ctx.org`
   now materially affects results.
6. **Which metadata tables are actually read by the row filter:**
   `ABAC_EntitySubjectAssignment` + `ABAC_Assignment` (always) · `UserGroupMembers` (only USER_GROUP grants)
   · `orgHierarchy` (only RBAC_ABAC) · `ABAC_AssignmentPermission` (**not** the row filter — masking only).
   So for ABAC-mode USER_ID testing, only ESA + Assignment need real, matching rows.

---

## 6. Service-principal-only testing (the SP is the only tester)

There are **no real end users** in this phase. All testing runs as the **service principal**
(`76d5804d-…`, the id the policies are bound `TO`), via the JDBC client or the curl SQL Statements API.

> 🔑 **The SP impersonates any assignment by choosing `claim.user`.** Because `claim.user` is just a
> string the caller puts in the token, you set it to the `subjectID` of whichever seed row you want to
> test. No real Databricks identities are needed — `get_user_context()` and the row filter both read
> `ctx.user` straight from the claim. So keep the multi-user seed and **vary `claim.user` per test.**

### Current seed

```
ABAC_EntitySubjectAssignment:
  entityID  objectType  assignmentID           subjectType  subjectID
  2012      Customer    assignment_customer_1  USER_ID      u.analyst1@example.com
  3006      Item        assignment_item_1      USER_ID      u.vendor.mgr@example.com
  118144    StoreSale   assignment_sales_1     USER_ID      u.developer@example.com
UserGroupMembers:
  test_group_1 -> u.analyst1@example.com
```

### Expected results (with the full 3-branch filter)

The table below uses `mode=ABAC` and **`permissions:[]`**, so only the root branch's explicit-assignment
path can fire — the `0`s for non-root tables reflect the **empty permissions list**, not a missing
branch. Populate `permissions` or switch to `RBAC_ABAC` and the other two branches light up (see the
rows beneath the table, and [`../testing/jdbc-cases.md`](../testing/jdbc-cases.md) for the
per-case numbers).

| `claim.user` | `claim.root` | `mode` / `permissions` | Query | Result |
| --- | --- | --- | --- | --- |
| `u.analyst1@example.com`  | `Customer`   | `ABAC` / `[]` | `SELECT * FROM customer`    | **1 row** (`c_customer_sk=2012`) |
| `u.analyst1@example.com`  | `Customer`   | `ABAC` / `[]` | `SELECT * FROM item`        | **0** (root ≠ Item, `Item` not in permissions) |
| `u.analyst1@example.com`  | `Customer`   | `ABAC` / `[]` | `SELECT * FROM store_sales` | **0** (root ≠ StoreSale, `StoreSale` not in permissions) |
| `u.vendor.mgr@example.com`| `Item`       | `ABAC` / `[]` | `SELECT * FROM item`        | **1 row** (`i_item_sk=3006`) |
| `u.vendor.mgr@example.com`| `Customer`   | `ABAC` / `[]` | `SELECT * FROM item`        | **0** (root ≠ Item, empty permissions) |
| `u.developer@example.com` | `StoreSale`  | `ABAC` / `[]` | `SELECT * FROM store_sales` | **N rows** — all sales where `ss_customer_sk=118144` (not unique) |
| unassigned / unsubstituted `<service_principal_user>` | any | `ABAC` / `[]` | any | **0** (deny) |
| any | any | `DISABLE` | any | **ALL rows** (pipeline sanity check) |

**What the other two branches add (now live):**

- **Permissions (middle) branch** — put the non-root table's object type in `permissions`, e.g.
  `root=Customer, permissions:["Item"]`, and `SELECT * FROM item` returns **all rows of `item`** (coarse
  "see the whole related table" access), independent of any per-row assignment.
- **RBAC_ABAC branch** — set `mode=RBAC_ABAC` and query the **root** table, and rows are additionally
  visible when their `org_id` falls under `ctx.org` (the org subtree), on top of any explicit
  assignments. See [`../testing/jdbc-cases.md`](../testing/jdbc-cases.md) for exact counts.

### The three allow-tests (JDBC form)

These exercise the root/explicit-assignment path, so `permissions:[]` is fine — the permissions branch
just isn't needed here (populate it to also open related tables; see the rows above).

```bash
# Customer  (analyst)      -> expect c_customer_sk=2012
java -jar target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar \
  '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT * FROM abac_tpcds.tpcds_1_delta.customer ORDER BY 1"

# Item      (vendor mgr)   -> expect i_item_sk=3006
java -jar target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar \
  '{"tenant":1,"user":"u.vendor.mgr@example.com","org":"100","mode":"ABAC","root":"Item","permissions":[]}' \
  "SELECT * FROM abac_tpcds.tpcds_1_delta.item ORDER BY 1"

# StoreSale (developer)    -> expect all rows where ss_customer_sk=118144
java -jar target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar \
  '{"tenant":1,"user":"u.developer@example.com","org":"100","mode":"ABAC","root":"StoreSale","permissions":[]}' \
  "SELECT * FROM abac_tpcds.tpcds_1_delta.store_sales WHERE ss_customer_sk=118144 ORDER BY 1"
```

Deny test: reuse any of the above with a `user` that isn't in the seed → **0 rows**.
DISABLE sanity: set `"mode":"DISABLE"` → **all rows** (proves the pipeline returns data at all).

For the **curl** equivalent, set `CUSTOM_CLAIM` to the same JSON, re-mint the token (§6 of
[`runbook.md`](runbook.md)), then run the statement. Run the *same* claim through
both JDBC and curl and confirm the counts match — that proves the real driver path and the REST path
enforce identically.

### Notes / currently-dormant paths

- **Substitute the placeholder.** The `CUSTOM_CLAIM` export literally contains
  `"user":"<service_principal_user>"`. Run it un-substituted and `ctx.user` matches no `subjectID` → 0
  rows everywhere (except DISABLE). Always replace it with a real `subjectID` from the seed.
- **Group membership is dormant.** `test_group_1 → u.analyst1` exists, but no ESA row uses
  `subjectType='USER_GROUP', subjectID='test_group_1'`, so the group path is never exercised. To test
  it, add e.g. `(<entityID>, 'Customer', 'assignment_customer_1', 'USER_GROUP', 'test_group_1', false)`
  → then any group member (analyst) with `root=Customer` sees that entity.

---

## 7. Security

### 7.1 Operational (secrets, tokens)

- `runbook.md` and shell exports contain `CLIENT_SECRET` / `ACCESS_TOKEN`. `JDBC/.gitignore`
  only ignores `target/` — it does **not** protect those. Don't commit real secrets; rotate if leaked.
- Tokens are short-lived (`expires_in: 3600`). Re-mint when a call returns 401.

### 7.2 Claim trust model (threat model) — the most important caveat

**The `custom_claim` is trusted, not verified.** The OAuth server signs the token, but the signature
only proves *"this token was issued to SP `76d5804d` and contains this claim blob."* It does **not**
validate the claim's *contents*. The row filter reads `ctx.user / mode / root / permissions` and
trusts them outright — there is **no binding** between the token's real principal (the SP) and the
claimed `user`, and **no validation** of `mode` / `root` / `permissions`.

**So anyone who can mint a token with an arbitrary claim can escalate at will:**

```json
{"mode":"DISABLE", ...}                          → every row of every governed table (filter bypassed)
{"user":"u.developer@example.com","root":"StoreSale", ...}   → impersonate any seeded subjectID
```

Nothing stops this — it is exactly why SP-only testing works (§6). `mode:"DISABLE"` is the worst: it
skips the filter entirely.

**Two ceilings — what the claim can and cannot do:**

| Question | Answer |
| --- | --- |
| Can a claim exceed the **claimed user's** real rights? | **Yes, trivially** — impersonation + `DISABLE`. The claim is self-asserted and unbound to real identity. |
| Can a claim exceed the **SP's** rights? | **No.** Databricks still enforces `SELECT`/`USE` on the SP identity. The claim only relaxes the **row filter** *within* tables the SP can already read — it grants no new table privileges. |

So the effective ceiling is **"all rows of every table the SP has `SELECT` on"** (reachable in full via
`DISABLE`), plus every **ungoverned** table (which returns everything regardless — only `customer` /
`item` / `store_sales` are governed here).

**Where the real boundary lives.** This is not a Databricks bug — it's an intentional **delegation
model**, the same shape as a backend that authenticates users itself and queries with a service
account "on behalf of" them. Security reduces to two things:

1. **Who holds the SP secret** — only that party can mint a claim-carrying token. In the intended
   architecture (`abac_docs/Java/config/DatabricksConnectionProxy.java`), the **application server** is
   the sole holder; end users authenticate to the *app* (its own SSO), never see the secret, never
   touch `/oidc/v1/token`.
2. **Whether the claim is set honestly** — the trusted app computes the ABAC context from the user's
   *real, app-verified* authorization and stamps it into the claim. The user can't forge it because
   they can't mint the token.

> ⚠️ **In this POC there is no such enforcement** — the tester holds the secret and hand-crafts
> claims. That's fine for testing, but would be a catastrophic hole in production **if an end user
> could ever reach the token endpoint with the secret.** The security you don't see in the POC is
> supposed to live in the app tier.

**Hardening (defense in depth):**

- Protect the secret: only a trusted backend holds it; rotate it; ensure end users can't reach the
  token endpoint with it. *(Primary control.)*
- Never let an untrusted party choose `mode` — especially `DISABLE`.
- Optionally trust fewer claim fields: derive `mode`/`root`/`permissions` from a **server-side table
  keyed by the real principal** instead of the claim — but that trades away the per-request
  flexibility that is the claim's whole purpose.
- Govern **every** sensitive table (ungoverned tables leak fully, independent of any of this).

**The question to answer for production:** *who holds the SP secret and sets the claim — a trusted
backend, or something an end user can influence?* If it's the backend, the model is sound; if an end
user can ever mint their own claim, they own the data.

### 7.3 How production builds the claim (why user/root/mode can't be forged)

§7.2 shows the claim is trusted; this shows why that trust is safe in the real app. The customer's
`abac_docs/Java/config/` code is the reference: **one service principal (secret held only by the app
server), pooled connections, and a claim derived entirely from the authenticated user — never from
request input.**

**Field-by-field provenance** (from `DatabricksSessionContext`):

| Claim field | Where it comes from in production | Can the user forge it? |
| --- | --- | --- |
| `tenant`, `user`, `org` | the authenticated session: `MsSecurityContext.getCurrentUserDetails()` → `getTenantIdAsLong()` / `getUserId()` / `getOrgGroupId()` (`set()`, lines 125–146) | No — from the session, not a request param |
| `root` | the **object type of the report/entity being viewed**: `ObjectType.by…(report/type)` → `set(t.objectType)` (lines 90–123) | No — derived from what the authorized app is querying |
| `mode` | computed from the user's permissions via `AssertionService` (`getMode()`, 238–253): no feature perm → `DISABLED`, basic/advanced → `RBAC_ABAC`, else `ABAC` | No — from server-side authz |
| `permissions` | computed from `root`'s related object types ∩ the user's real permissions (`getPermissions()`, 255–270) | No — from server-side authz |

**`root` and `permissions` are one consistent bundle** — `permissions` is computed *from* `root`'s
`getRelatedObjectPermissions()`, both from the user's real entitlements. So the POC-style forgery
"set `root=X, permissions=[Y]` to coarse-view Y" is impossible in production: neither is
user-settable, and they move together.

**How it's injected per request** (`DatabricksConnectionProxy`): the proxy wraps the pooled
connection; before each call, `checkClaim()` (184–223) reads the current thread's
`DatabricksSessionContext` (a `ThreadLocal` set from the authenticated user), and if the pooled
connection's token doesn't already carry a matching, unexpired claim (`context.matches(claim) &&
claim.hasToken()`), it serialises the server-built context, mints a token with
`custom_claim=<that JSON>` (`newProvider`, 225–257 — same mechanism as our `injectCustomClaim`), and
`resetAccessToken(...)` hot-swaps it. `matches(...)` includes `root`, so navigating to a different
report re-mints with the new `root`.

**Net:** the end user can only authenticate as themselves; the app then asserts *their real* context.
They can't forge a claim because they never hold the secret and never reach `/oidc/v1/token`. Your POC
can impersonate freely precisely because **you** are standing in for the app tier. Residual trust
therefore lives app-side: protect the SP secret, populate `MsSecurityContext` only from real auth, and
validate org/tenant switches (`setOrg`, 51–59) so a user can't claim an org they don't belong to.

---

## 8. Open items

- **Identity model — RESOLVED.** Testing is **SP-only**; the SP impersonates any seed row by setting
  `claim.user` to that row's `subjectID` (§6). No reseeding to a single identity needed; the multi-user
  seed (`u.analyst1` / `u.vendor.mgr` / `u.developer`) is intentional. `claim.user` is varied per test.
- **Row filter — now full 3-branch.** The POC runs the complete customer filter (`permissions` +
  `RBAC_ABAC` branches live; §5 rule 3). Remaining decision is **prod parity** — confirm this matches
  the customer's deployed filter and permission-string format (§5 rule 4).
- **`WORKSPACE_HOST` trailing slash** → drop it (`adb-….azuredatabricks.net`, no `/`).
- **Swap-back note:** to return to no-OAuth, only `get_user_context()` reverts to the `ABAC_UserContext`
  lookup — nothing else.
- **Claim trust boundary — DECIDE (§7.2).** The claim is self-asserted; whoever holds the SP secret can
  set `mode:DISABLE` or impersonate any `user`. Confirm the **app tier is the sole secret-holder** and
  sets the claim from real user auth; end users must never be able to mint their own token.

---

## 9. Test cases & edge cases (TODO — filling in tomorrow)

> **Explore first.** [`sql/11_explore_behaviours.sql`](../../sql/11_explore_behaviours.sql)
> (guide: [`../testing/explore-behaviours.md`](../testing/explore-behaviours.md)) sweeps ctx / claim / mode /
> metadata values as the owner with no OAuth, and self-checks each result. Run it to see
> the live behaviour of the deployed **full 3-branch** filter (incl. how the permissions
> branch and RBAC_ABAC actually resolve), then promote its grids into the formal matrix below.
>
> **Runnable JDBC/curl cases:** [`../testing/jdbc-cases.md`](../testing/jdbc-cases.md) — the same scenarios as
> real claims through the service-principal path (ABAC, RBAC_ABAC, permissions, and
> ctx-edge tinkering), with the customer-template values mapped in.

Formal matrix to be defined. Seed points to build from:

- [ ] **Allow (root match)** — the 3 happy paths in §6 (Customer/Item/StoreSale).
- [ ] **Deny (wrong user)** — assigned entity, but `claim.user` ≠ that row's `subjectID`.
- [ ] **Deny (wrong root)** — right user, but `claim.root` ≠ the table's object type.
- [ ] **Deny (unassigned entity)** — right user/root, but query an entity id with no ESA row.
- [ ] **DISABLE mode** — all rows, every table (pipeline sanity).
- [ ] **JDBC vs curl parity** — same claim, identical row counts on both paths.
- [ ] **Placeholder / empty user** — `""` or un-substituted `<service_principal_user>` → 0 rows.
- [ ] **Group path** — add a `USER_GROUP`/`test_group_1` ESA row; confirm analyst sees it (§6 notes).
- [ ] **isActive / isDeleted flags** — flip `ABAC_Assignment.isActive=false` or `isDeleted=true` → row hidden.
- [ ] **Token expiry** — expired token (`expires_in` 3600) → 401, re-mint.
- [ ] **Owner bypass** — same query as the workspace owner (not the SP) → policy does NOT apply (all rows).
- [ ] _(edge cases to add: multi-row entity ids, casing of object types, missing tags, etc.)_

> Add the finalized cases and their observed results here as a table once run.
