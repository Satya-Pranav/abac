# JDBC test cases — 43 cases + a DR2 hot-swap scenario, ctx/claim tinkering + per-row filter trace

The runnable case catalog for the JDBC client (`JDBC/`), **merged with the end-to-end per-row
flow**. Each case is: **purpose → ctx JSON claim → SQL → expected**, plus a trace down to the exact
row-filter branch it exercises. `arg1 = claim JSON`, `arg2 = SQL` (see `AbacJdbcClient`). The curl
equivalent is the same claim in `CUSTOM_CLAIM`, re-minted per
[`../deployment/runbook.md`](../deployment/runbook.md) §6–7 — run a couple through both and confirm
the counts match.

Read [§0–§2](#0-the-pipeline-every-query-goes-through) once (the shared machinery), then jump to any
group. The final [one-line summary](#one-line-summary-per-case) lists all 43 cases (plus the DR2 hot-swap checks).

```bash
cd JDBC && mvn -q package        # once
# env: CLIENT_ID / CLIENT_SECRET / WORKSPACE_HOST (no trailing /) / WAREHOUSE_ID
J() { java -jar target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar "$1" "$2"; }
```

> **Run all 43 cases + the DR2 hot-swap scenario at once** with the bundled test suite — it **self-seeds a namespaced fixture**
> (`suite_a_*` + `SUITE_ORG` / `SUITE_EMPTY`, dropped afterward; needs `MODIFY` per `sql/09`, else it
> falls back to the existing seed), executes every case below through the real OAuth hot-swap
> (targeting the **deployed 3-branch filter** — no auto-detect), and logs
> id / purpose / description / claim / SQL / expected / actual / verdict per case with a
> `SUMMARY -> PASS x FAIL x INFO x ERROR x` line:
> ```bash
> java -cp target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar com.abacpoc.AbacTestSuite
> ```
> (source: `JDBC/src/main/java/com/abacpoc/AbacTestSuite.java`). Because it seeds its own fixture,
> Step 0 below is optional for the suite. The individual `J '<claim>' '<sql>'` commands are for
> running one case ad-hoc.

---

## Who the tester is — dummy emails, not real users

There are **no real end users**. Exploration runs one of two ways:

- **Service account (the SP):** JDBC / curl. The SP the policy is bound `TO` authenticates; the
  **effective ABAC identity is `claim.user`**, which you set to one of the **dummy emails seeded
  in `ABAC_EntitySubjectAssignment`**. You impersonate any tester by choosing that string.
- **Databricks UI (as owner):** owners **bypass** row-filter policies, so `SELECT * FROM …` shows
  everything. To see filtering in the UI, call the filter directly with a literal ctx —
  that's `sql/11_explore_behaviours.sql`. Use the UI harness for the logic, the SP path for the
  real claim.

The three seeded testers (each tied to one root type / entity — see
[`../deployment/oauth-jdbc-flow.md`](../deployment/oauth-jdbc-flow.md) §6):

| `claim.user` (dummy email) | `root` | assigned entity | allow-query result |
| --- | --- | --- | --- |
| `u.analyst1@example.com`   | `Customer`  | `2012`   | 1 customer (`c_customer_sk=2012`) |
| `u.vendor.mgr@example.com` | `Item`      | `3006`   | 1 item (`i_item_sk=3006`) |
| `u.developer@example.com`  | `StoreSale` | `118144` | all store_sales where `ss_customer_sk=118144` |
| `u.nobody@example.com`     | *(none)*    | *(none)* | 0 (used for the org-only RBAC cases) |

**Golden rule:** `claim.user` must equal a real `subjectID` above (and `UserGroupMembers.memberID`
for group grants). Wrong/empty `user` → 0 rows. Run **Step 0** to confirm the live values before
trusting any expected count below.

---

## How to read every case — the one mental model

The filter returns **TRUE (row visible)** if **any** of these three branches is true. Trace your
ctx down these lines and you have the answer before you run it:

```
1. mode = 'DISABLE'                                     → show all, stop.
2. root ≠ object_type  AND  object_type ∈ permissions   → coarse: whole related table
3. root = object_type  AND (                            → the fine-grained "root" type:
       (mode='RBAC_ABAC' AND org_id is a child of ctx.org)   ← org-based  (3a)
       OR EXISTS(an assignment to ctx.user for this entity)  ← per-row, user-based  (3b)
   )
```

`object_type` is the table name mapped+capitalised by `entity_type_to_object_type` (customer →
`Customer`). `org_id` is the `abac_column_org`-tagged column (customer → `c_current_addr_sk`).
Each case changes **one field** from its group's known-good baseline, so the result isolates
exactly what that field controls.

---

## §0. The pipeline every query goes through

```
1. JDBC connects as the SERVICE PRINCIPAL (plain OAuth M2M token, no claim).
2. injectCustomClaim() re-mints the token so it carries  custom_claim = <the case's ctx JSON>.
3. You run `SELECT count(*) FROM <table>`. The Unity Catalog policy engine finds the policy
   whose TO = this SP for <table>  (only customer / item / store_sales are governed by the
   deployed filter; sql/13 adds promotion/store/call_center/ship_mode; sql/14 adds inventory).
4. The policy's USING COLUMNS clause calls the wrapper ONCE PER ROW, passing 3 values:
        abac_row_filter_wrapper( <id-col value>, '<table-name>', <org-col value> )
5. The wrapper (deployed) does two lookups and calls the real filter:
        object_type = entity_type_to_object_type('<table-name>')   -- 'customer' -> 'Customer'
        ctx         = get_user_context()
                    = from_json( current_oauth_custom_identity_claim(), '<struct>' )
        RETURN abac_row_filter( <id value>, object_type, <org value>, ctx )
6. abac_row_filter returns TRUE/FALSE for that row. TRUE rows are kept; the count is the number
   of TRUE rows.
```

Two facts that decide everything:

- **`current_oauth_custom_identity_claim()` HARD-ERRORS if the token carries no claim** — so every
  read of a governed table needs a claim (that's why the suite injects a `DISABLE` claim even to
  seed its fixture). With a claim present, `from_json` turns the JSON into the `ctx` struct.
- **The object type is the table name capitalised** by `entity_type_to_object_type`:
  `customer→Customer`, `item→Item`, `store_sales→StoreSale`. `ctx.root` and `ctx.permissions` are
  compared against *this*, so they are **case-sensitive** and must be the capitalised form.

---

## §1. The exact filter that runs

**Deployed = the full 3-branch** (customer template
`abac_docs/Databricks/scripts-templates/create_row_filter.sql`, identical to `sql/05_dataset_udfs.sql`)
— all three branches are live:

```sql
RETURN (
  ctx.mode = 'DISABLE'                                                    -- BRANCH 1
  OR ( ctx.root <> object_type AND array_contains(ctx.permissions, object_type) )   -- BRANCH 2
  OR ( ctx.root = object_type AND (                                       -- BRANCH 3 (root type)
         ( ctx.mode = 'RBAC_ABAC' AND org_id IN                          --   3a: org subtree
             (SELECT orgID FROM orgHierarchy WHERE parentOrgID = ctx.org AND isDeleted = false) )
         OR EXISTS (                                                      --   3b: explicit assignment
           SELECT 1
           FROM ABAC_EntitySubjectAssignment esa
           JOIN ABAC_Assignment a
             ON esa.assignmentID = a.id AND a.isActive AND a.isDeleted = false
           LEFT JOIN UserGroupMembers ugm
             ON esa.subjectType = 'USER_GROUP' AND esa.subjectID = ugm.groupID
             AND ugm.memberID = ctx.user AND ugm.isDeleted = false
           WHERE esa.isDeleted = false
             AND esa.entityID   = entity_id          -- the row's id-column value
             AND esa.objectType = object_type         -- 'Customer' / 'Item' / 'StoreSale'
             AND ( ugm.memberID IS NOT NULL
                   OR (esa.subjectType = 'USER_ID' AND esa.subjectID = ctx.user) )
         )
  ) )
);
```

> **History:** the initial cut in [`../deployment/runbook.md`](../deployment/runbook.md) §3 deployed
> only a **2-branch** subset — `DISABLE` OR `root=object_type AND EXISTS` (no branch 2, no 3a). That
> cut is **superseded history**; the live warehouse now runs the full 3-branch filter above, so
> groups B-perm and B-rbac work directly.

> **KEY:** branch 3b (`EXISTS`) is **not** gated by mode. So `RBAC_ABAC` is **ADDITIVE** — a root
> row shows if it's in the **org subtree (3a) OR** the user has an **explicit assignment (3b)**. It
> does *not* replace per-row assignment; it adds org access on top. (In `ABAC` mode, 3a is false, so
> only 3b applies.) Note 3a's `parentOrgID = ctx.org`: against the customer's **real** `OrgHierarchy`
> (an ancestor-closure — see [`abac_docs/customer_data/`](../../abac_docs/customer_data/README.md))
> this returns the **full subtree**, so "show everything in the org tree" is accurate. In **this POC**
> `orgHierarchy` is seeded as a plain parent→child adjacency list, so the same predicate behaves
> **single-level** (grandchildren excluded — see the grandchild note under group R).

---

## §2. What the policy feeds in, per governed table

| Table | `entity_id` (id col, `abac_column_id`) | `object_type` | `org_id` (org col, `abac_column_org`) |
| --- | --- | --- | --- |
| `customer`    | `c_customer_sk`  | `Customer`  | `c_current_addr_sk` |
| `item`        | `i_item_sk`      | `Item`      | literal `'100'` (item has no org tag) |
| `store_sales` | `ss_customer_sk` | `StoreSale` | `ss_store_sk` |

(`sql/13` adds `promotion`/`store`/`call_center`/`ship_mode` with PascalCase literal types; `sql/14`
adds `inventory` under a *separate* threshold filter — see the M and TH groups.) `org_id` is only
ever read by branch 3a (RBAC_ABAC), which is live.

**The metadata the filter reads** (seeded by `sql/03`, or the suite's `suite_*` fixture — same
effective content):

```
ABAC_EntitySubjectAssignment (active, not deleted):
  entityID  objectType  subjectType  subjectID
  2012      Customer    USER_ID      u.analyst1@example.com
  3006      Item        USER_ID      u.vendor.mgr@example.com
  118144    StoreSale   USER_ID      u.developer@example.com
orgHierarchy:  SUITE_ORG is parent of 5 real c_current_addr_sk values   (real seed: '100' is)
UserGroupMembers:  test_group_1 -> u.analyst1@example.com   (used by N2's USER_GROUP esa row)
```

---

## ⚠️ Only 3 tables are governed by the deployed filter

Row-filter policies are bound to **`customer`, `item`, `store_sales`** by the deployed filter (not
the full 12 in `sql/08`; `sql/13`/`sql/14` add a few more under their own policies). **An ungoverned
table has no filter, so the SP sees ALL its rows regardless of the claim** — a deny test on
`store`/`warehouse` (before `sql/13`) is meaningless (it returns everything). Every case below
queries a **governed** table. (Security aside: in a real deployment, any sensitive table left unbound
is wide open to anyone with `SELECT` — bind a policy to every one.)

Case-group coverage against the deployed filter:

| Case group | Runs on the deployed 3-branch filter |
| --- | --- |
| **A** pure ABAC per-root + DISABLE + deny | ✅ works |
| **B-perm** non-root via `permissions` | ✅ works |
| **B-rbac (R)** RBAC_ABAC org tree | ✅ works |
| **T** `ctx.tenant` sensitivity | ✅ works — but tenant gives **no** row isolation (read nowhere) |
| **O** `ctx.org` sensitivity | ✅ works — org is read **only** by RBAC_ABAC (branch 3a) |
| **C** claim/ctx edge-value parsing | ✅ works |
| **M (N)** new governed tables via the `sql/13` wrapper | ✅ works — `promotion`/`store`/`call_center`/`ship_mode` |
| **TH** threshold/range grant (`sql/14`, separate filter on `inventory`) | ✅ works — `>=` predicate |
| **conflict (W/WP/WS)** two row filters on one table (`sql/12`, `warehouse`) | ❌ query errors `UC_ABAC_MULTIPLE_ROW_FILTERS` (by design) |
| **DR1** direct **classic** RLS (`sql/15`, `reason`) | ✅ works — `ALTER TABLE … SET ROW FILTER`, **no tags, no policy** |
| **DR2** ABAC `has_tag()` policy **hot-swap** (`sql/15`, `income_band`) | ✅ works — replace the inner UDF, wait 10s, re-assert, revert |

---

## Step 0 — confirm the live seed (so cases aren't guesses)

The row filter runs against **real seeded metadata**. Confirm the dummy emails / entity ids / org
tree that are actually loaded. Run as owner (SQL editor / SQL Statements API):

```sql
-- the testers + the entity ids each may see (the allow cases depend on these)
SELECT entityID, objectType, subjectType, subjectID
FROM abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment WHERE isDeleted = false ORDER BY objectType;
-- group memberships (for USER_GROUP grants)
SELECT * FROM abac_tpcds.tpcds_1_delta.UserGroupMembers WHERE isDeleted = false;
-- org children (RBAC_ABAC admits rows whose org_id is a DIRECT child of ctx.org)
SELECT orgID FROM abac_tpcds.tpcds_1_delta.orgHierarchy WHERE isDeleted = false ORDER BY parentOrgID;
```

If your seed differs from the table above, substitute your own dummy emails / entity ids / org
below. `org` in the RBAC cases (`"100"`) is a placeholder — use the parent org Step 0 shows.

---

## A. Pure ABAC — per-root explicit assignment

Baseline for this group is **A2** (analyst → 1 customer). A4/A5 show the same happy path for the
other two testers; A6–A8 are denials; A9 flips to ALL via the permissions branch (branch 2).

**A1 — DISABLE (allow-all sanity).**
- *Trace:* branch 1 (`ctx.mode='DISABLE'`) fires immediately and stops — `user`/`root`/`permissions` ignored.
- *Expect:* **ALL customers.** Proves the pipeline returns data — so if a later case gives 0, it's the filter, not a broken connection/seed.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"DISABLE","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**A2 — ABAC Customer allow (analyst) — the baseline.**
- *Trace:* B1 false; **B3** `root='Customer'=object_type` TRUE → **B3b** `esa.entityID=c_customer_sk AND objectType='Customer' AND subjectID='u.analyst1' AND a.isActive` → TRUE **only when `c_customer_sk=2012`** (mode≠RBAC_ABAC so 3a is false).
- *Expect:* **1 row (`c_customer_sk=2012`).** Core ABAC: you see only entities explicitly assigned to you.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**A3 — the visible id list is exactly `[2012]` (asserted).**
- *Trace:* identical eval to A2, projects the id column (`ORDER BY 1`).
- *Expect:* the id list equals **exactly `[2012]`** — asserts the analyst sees only their assigned entity and **nothing leaks** (not just that the first row is 2012). Observed run: `[2012]`.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT c_customer_sk FROM abac_tpcds.tpcds_1_delta.customer ORDER BY 1"
```

**A4 — ABAC Item allow (vendor manager).**
- *Trace:* B3 `root='Item'=object_type='Item'` → B3b matches `esa.entityID=i_item_sk … subjectID='u.vendor.mgr'` → TRUE only when `i_item_sk=3006`.
- *Expect:* **1 row (`i_item_sk=3006`).** Same mechanism as A2, different tester/root.
```bash
J '{"tenant":1,"user":"u.vendor.mgr@example.com","org":"100","mode":"ABAC","root":"Item","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.item"
```

**A5 — ABAC StoreSale allow (developer).**
- *Trace:* B3 `root='StoreSale'=object_type` → B3b matches `esa.entityID=ss_customer_sk … subjectID='u.developer'` → TRUE for **every store_sales row whose `ss_customer_sk=118144`**.
- *Expect:* **all store_sales where `ss_customer_sk=118144`** (many rows — one entity id, many sales).
```bash
J '{"tenant":1,"user":"u.developer@example.com","org":"100","mode":"ABAC","root":"StoreSale","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.store_sales"
```

**A6 — DENY, wrong user (right table, unassigned tester).**
- *Trace:* B3b looks for `objectType='Customer' AND subjectID='u.vendor.mgr'` → no such esa row (vendor.mgr is only assigned to Item 3006) → FALSE.
- *Expect:* **0.** Proves identity is the deciding lever — the Customer rows exist, just not for this user.
```bash
J '{"tenant":1,"user":"u.vendor.mgr@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**A7 — DENY, empty user (the JDBC sample's default).**
- *Trace:* `esa.subjectID=''` matches nothing in B3b.
- *Expect:* **0.** Explains why the out-of-the-box sample claim returns nothing.
```bash
J '{"tenant":1,"user":"","org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**A8 — DENY, wrong root.**
- *Trace:* B3 needs `root=object_type` → `Item≠Customer` → fails; B2 also fails (`array_contains([],'Customer')` false); no branch fires.
- *Expect:* **0.** Proves `root` must name the table you're querying.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Item","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**A9 — non-root table reached via `permissions` (branch 2 fires).**
- *Trace:* B1 no; B3 `root(Customer)=object_type(StoreSale)` no; **B2 fires** — `root(Customer)≠object_type(StoreSale)` **and** `StoreSale ∈ permissions`.
- *Expect:* **ALL store_sales.** Coarse related-table access through the permissions branch (same claim and result as B2).
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":["Item","StoreSale"]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.store_sales"
```

---

## B-perm. Non-root via `permissions` (branch 2)

**Branch 2 never reads `user`** — so the tester here is irrelevant (any dummy email works); what
matters is `root ≠ object_type` and the object type being listed. `permissions` holds **OBJECT
TYPES** (`"Item"`, `"StoreSale"`), not `.view` strings — the customer-template convention (§D).

**B1 — Item visible because `"Item" ∈ permissions`.**
- *Trace:* B2 fires — `root(Customer)≠object_type(Item)` **and** `Item ∈ permissions`.
- *Expect:* **ALL items.** Coarse "whole related table" access — no per-row grant involved.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":["Item","StoreSale"]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.item"
```

**B2 — store_sales now returns ALL (the fix for A9).**
- *Trace:* B2 fires on `StoreSale ∈ permissions`.
- *Expect:* **ALL** — the middle branch grants the whole related table. Same claim as A9.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":["Item","StoreSale"]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.store_sales"
```

**B3 — DENY on a governed table (item not in the allow-list).**
- *Trace:* B2's `array_contains([StoreSale],'Item')` is false; B3 `root=Item`? false.
- *Expect:* **0.** Proves `permissions` is a strict allow-list. (Must use a **governed** table — `item`. `store`/`warehouse` have no policy pre-`sql/13`, so they'd return ALL rows regardless.)
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":["StoreSale"]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.item"
```

**B4 — wrong permission format (the `.view` trap).**
- *Trace:* B2 compares against object type `"Item"`, and `"Item" ∉ ["items.view",…]` → false.
- *Expect:* **0.** Proves the `.view` strings do nothing — the filter wants object-type strings. Pick one convention (see §D).
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":["items.view","sales.view"]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.item"
```

---

## R. RBAC_ABAC — org-tree visibility (branch 3a, ADDITIVE: 3a OR 3b)

RBAC_ABAC **adds** org-subtree access **on top of** per-row assignment (it does **not** replace it).
On the **root** type a row shows if `org_id` is under `ctx.org` in `orgHierarchy`
(`parentOrgID = ctx.org`) **OR** the user has an explicit assignment. In **this POC** `orgHierarchy`
is a plain parent→child **adjacency list**, so that predicate matches **direct children only**
(grandchildren excluded — see the grandchild note below); the customer's **real** `OrgHierarchy` is an
**ancestor-closure** ([`abac_docs/customer_data/`](../../abac_docs/customer_data/README.md)), so the
same predicate returns the **full subtree**. The `EXISTS` sub-branch is **not** gated by mode, so it's an additive **OR**.
The org sub-branch never reads `user`; the assignment sub-branch does. Set `org` to the parent org
Step 0 shows. The suite seeds `SUITE_ORG` as parent of 5 real `c_current_addr_sk` values, and
`SUITE_EMPTY` as an org with no children.

**R1 — org WITH children (analyst).**
- *Trace:* B3 `root=Customer` TRUE → **(3a)** `org_id=c_current_addr_sk IN {5 children of SUITE_ORG}` **OR (3b)** EXISTS → 2012. Union.
- *Expect:* **>0 (observed 8) — a *larger* set than A2**: the org children **plus** the analyst's assigned `2012`.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"SUITE_ORG","mode":"RBAC_ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**R2 — org EMPTY, but assignment survives (the additive proof).**
- *Trace:* **3a** `org_id IN (children of SUITE_EMPTY = ∅)` → false for all rows; **3b** EXISTS(analyst) → TRUE for `2012`. Whole B3 = TRUE only for 2012.
- *Expect:* **1 (`c_customer_sk=2012`).** An empty org does **not** deny an explicitly-assigned user — RBAC is additive (org **OR** assignment). For a true org-only `0`, use a user with no assignment (see O2).
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"SUITE_EMPTY","mode":"RBAC_ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**R3 — RBAC_ABAC does NOT help non-root tables.**
- *Trace:* B3 `root='Customer'=object_type='Item'`? false → neither 3a nor 3b reached; B2 `array_contains([],'Item')` false.
- *Expect:* **0.** RBAC_ABAC relaxes **only** the root type.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"RBAC_ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.item"
```

**R4 — pure org access, no assignment (the isolation case).**
- *Trace:* B3 `root=Customer` TRUE → **(3a)** `org_id IN {5 children}` → TRUE for those customers; **(3b)** EXISTS(`u.nobody`) → none.
- *Expect:* **>0 (observed 7).** Proves 3a is purely org-driven, independent of any assignment. (R1−R4 = 8−7 = 1 = the analyst's extra 2012 — additivity confirmed by arithmetic.)
```bash
J '{"tenant":1,"user":"u.nobody@example.com","org":"SUITE_ORG","mode":"RBAC_ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**ODEL — orgHierarchy child soft-deleted (deny).**
- *Doing:* `user=u.nobody@example.com` (no assignments), `org:"<DEL_ORG>"`, `mode:"RBAC_ABAC"`; query `customer`. `sql/13` seeded one **real customer address** as a child of **both** `<DEL_ORG>` (`isDeleted=true`) and `<LIVE_ORG>` (`isDeleted=false`).
- *Trace:* 3a lists children of `<DEL_ORG>` `WHERE isDeleted=false` → the only child is the soft-deleted edge → excluded → the `IN` set is empty; 3b `nobody` has no assignment → false.
- *Expect:* **0.** Negative: a soft-deleted `orgHierarchy` edge is invisible to the RBAC org walk.
```bash
J '{"tenant":1,"user":"u.nobody@example.com","org":"<DEL_ORG>","mode":"RBAC_ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**OLIVE — same address, live edge (allow).**
- *Doing:* identical to ODEL but `org:"<LIVE_ORG>"`.
- *Trace:* 3a lists children of `<LIVE_ORG>` → the same address, `isDeleted=false` → included → customers on that address become visible; 3b still empty for `nobody`.
- *Expect:* **>0 (observed 1).** OLIVE **>0** vs ODEL **=0** on the **same** address isolates `orgHierarchy.isDeleted` as the sole cause — nothing else differs between the two claims.
```bash
J '{"tenant":1,"user":"u.nobody@example.com","org":"<LIVE_ORG>","mode":"RBAC_ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

> **Single-level vs subtree, end-to-end:** seed a grandchild org
> (`INSERT orgHierarchy VALUES ('<grandchild_addr>','<a_child_of_100>',false)`) and confirm a
> customer on `<grandchild_addr>` is **NOT** visible under `org=100`. (Owner-direct version: Part C
> of `sql/11_explore_behaviours.sql`.) This single-level result is the **POC's adjacency seed**; the
> customer's real ancestor-closure would make that grandchild visible under `org=100` — to reproduce
> real subtree behavior seed each org → every ancestor (self + root). See
> [`abac_docs/customer_data/`](../../abac_docs/customer_data/README.md).

---

## C. Claim / ctx edge-value tinkering (parsing & case-sensitivity)

Baseline is **A2** (analyst → 1 customer). Each case changes one field of A2 and queries `customer`.
All provide a `custom_claim` (so `current_oauth_custom_identity_claim()` does **not** error — that
only happens with *no* claim at all). `from_json` maps the JSON to the struct; missing/mismatched
fields become `null`.

**C1 — `mode:"abac"` (lowercase).**
- *Trace:* not `"DISABLE"` and not `"RBAC_ABAC"` → falls through to **B3b** EXISTS(analyst) → 1.
- *Expect:* **same as A2 (1 row).** Only `DISABLE`/`RBAC_ABAC` are "magic"; any other mode behaves like plain ABAC.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"abac","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**C2 — `mode:"disable"` (lowercase).**
- *Trace:* B1 is an exact `= 'DISABLE'` compare → lowercase fails → falls to B3b.
- *Expect:* **same as A2 (1 row), NOT all.** A real footgun: `DISABLE` is case-sensitive.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"disable","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**C3 — `root:"customer"` (lowercase).**
- *Trace:* the mapper produces `object_type='Customer'`; `root('customer')=object_type('Customer')` is false → B3 fails; B2 `array_contains([],'Customer')` false.
- *Expect:* **0.** `root` is compared as an exact string.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**C4 — missing `permissions` field.**
- *Trace:* `from_json` sets `permissions=null`; the root/`EXISTS` path never reads permissions → 1.
- *Expect:* **same as A2 (1 row).** Optional fields can be omitted safely on the root path.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer"}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**C5 — extra unknown field `scope`.**
- *Trace:* `from_json` parses against a fixed struct and ignores unknown keys → ctx identical to A2.
- *Expect:* **same as A2 (1 row).** Extra claim junk is harmless.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[],"scope":"xyz"}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**C6 — `tenant:"1"` (string, not int) — now asserted.**
- *Trace:* `tenant` is **never read** by the filter; the observed run proved `from_json` **tolerates** the type mismatch (it does not null the whole struct), so the row set is unchanged.
- *Expect:* **exactly 1** (asserted; observed run: 1). Confirms a string-typed `tenant` still parses and doesn't affect the decision.
```bash
J '{"tenant":"1","user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**C7 — empty claim `{}`.**
- *Trace:* every field `null` → `null='DISABLE'`→NULL, `null='Customer'`→NULL, `null<>'Customer'`→NULL; no branch TRUE.
- *Expect:* **0.** The secure default: a present-but-empty claim denies.
```bash
J '{}' "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**C8 — `user:"U.Analyst1@example.com"` (mixed case).**
- *Trace:* `esa.subjectID = ctx.user` is an exact compare vs seeded `u.analyst1@example.com`.
- *Expect:* **0.** Identity matching is case-sensitive.
```bash
J '{"tenant":1,"user":"U.Analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

> Also worth a look: run `SELECT current_oauth_custom_identity_claim()` (JDBC or curl §7) with a
> deliberately malformed claim to see the raw string the warehouse received *before* `from_json`.

---

## T / O. tenant & org sensitivity — which claim fields the filter actually reads

`ctx.tenant` is read **nowhere** in the filter body; `ctx.org` is read by **exactly one** sub-branch
— the RBAC_ABAC org-tree test (3a), which only runs when `mode='RBAC_ABAC'`. These cases change one
of those two fields off the A2 / R1 baselines so the result isolates what each controls. Headline:
**`tenant` buys you no row isolation here**, and **`org` is inert unless `mode='RBAC_ABAC'`**.

**T1 — `tenant` is never read (plain ABAC).**
- *Trace:* B3 `root=Customer` TRUE → 3a `mode='ABAC'`→false (org never read); 3b EXISTS → 2012; `tenant` never referenced.
- *Expect:* **1 (`c_customer_sk=2012`) — identical to A2.** `tenant` provides **no** isolation in this row filter — enforce it elsewhere (per-tenant catalogs/schemas, or the app tier).
```bash
J '{"tenant":999,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**T2 — `tenant` still inert under RBAC_ABAC.**
- *Trace:* B3 opens on root; 3a walks children of `SUITE_ORG`, 3b keeps the assignment — `tenant` read by neither; `org` drives the count.
- *Expect:* **>0 (observed 8) — identical to R1.** `tenant` stays inert even under RBAC_ABAC.
```bash
J '{"tenant":999,"user":"u.analyst1@example.com","org":"SUITE_ORG","mode":"RBAC_ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**O1 — `org` is inert in plain ABAC.**
- *Trace:* 3a needs `mode='RBAC_ABAC'`→false, so `org` never read; 3b EXISTS unchanged, still matches 2012.
- *Expect:* **1 (`c_customer_sk=2012`) — identical to A2.** `org` value can be anything in plain ABAC and the count doesn't move.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"999","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

**O2 — `org` DRIVES RBAC_ABAC (empty org + no assignment = nothing).**
- *Trace:* 3a children of `SUITE_EMPTY` = ∅; 3b `u.nobody` has no EXISTS match → both sub-branches fail.
- *Expect:* **0.** `org` **drives** RBAC_ABAC — an empty org with no assignment fallback sees nothing. (Mirror of R4, where the same `nobody` RBAC claim returned rows *because that org had children*.)
```bash
J '{"tenant":1,"user":"u.nobody@example.com","org":"SUITE_EMPTY","mode":"RBAC_ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
```

---

## M. New governed tables — one assignment condition each (sql/13)

`sql/13` onboards four more tables — **`promotion`, `store`, `call_center`, `ship_mode`** — under the
**same** `abac_row_filter_wrapper` that customer/item/store_sales already use. The policy passes the
**PascalCase object-type literal** (`'Promotion'`, `'Store'`, `'CallCenter'`, `'ShipMode'`), which
flows through `entity_type_to_object_type`'s `ELSE` branch **unchanged**. Each case queries as the
analyst in plain `mode:"ABAC"` with `root` = that table's object type (so `root=object_type`, 3a
false, only **3b** can fire) and isolates **one** clause of the branch-3 `EXISTS` sub-query. Three are
negatives (each trips a different guard); one is a positive.

**N1 — promotion: ESA soft-deleted (`esa.isDeleted=true`).**
- *Trace:* B3 opens (Promotion=Promotion); the one esa row for this entity has `isDeleted=true`, dropped by `WHERE esa.isDeleted=false` → EXISTS false.
- *Expect:* **0.** Negative (ESA soft-delete): the grant vanishes even though everything else lines up.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Promotion","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.promotion"
```

**N2 — store: group grant via `UserGroupMembers` (positive).**
- *Trace:* esa is `subjectType='USER_GROUP', subjectID='test_group_1'`; `UserGroupMembers` has `(test_group_1, u.analyst1, not deleted)` and `assignment_store_1` is active+not-deleted → the `LEFT JOIN ugm` matches → EXISTS true for the one assigned `s_store_sk`.
- *Expect:* **1.** Positive: proves both the **group path** and the **new-table onboarding** work end to end.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Store","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.store"
```

**N3 — call_center: assignment inactive (`a.isActive=false`).**
- *Trace:* esa normal, but its `ABAC_Assignment` (`assignment_cc_1`) has `isActive=false` → `JOIN … AND a.isActive` fails → EXISTS false.
- *Expect:* **0.** Negative (assignment inactive): breaks the join even with a live ESA.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"CallCenter","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.call_center"
```

**N4 — ship_mode: assignment soft-deleted (`a.isDeleted=true`).**
- *Trace:* esa normal again, but its `ABAC_Assignment` (`assignment_ship_1`) has `isDeleted=true` → `JOIN … AND a.isDeleted=false` fails → EXISTS false.
- *Expect:* **0.** Negative (assignment soft-deleted): soft-deleting the *assignment* — not the ESA — also removes the grant, a distinct guard from N1.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"ShipMode","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.ship_mode"
```

---

## TH. Threshold / range grant — "show every row at/above the assigned value" (sql/14)

`sql/14` builds a **separate** filter `abac_row_filter_threshold` (bound to the real `inventory`
table) that changes only branch 3b's match predicate:

```sql
--   deployed exact-match:  AND esa.entityID = entity_id
--   threshold (range):     AND try_cast(entity_id AS BIGINT) >= try_cast(esa.entityID AS BIGINT)
```

`inventory.inv_quantity_on_hand` is tagged as the id column, so the filter compares each row's
quantity to the analyst's assigned value **`500`** on object type `Inventory`. `try_cast(... AS
BIGINT)` is mandatory — the entityID columns are STRING and a raw `>=` on strings is **lexicographic**
(`'10' >= '5'` is FALSE), so you must cast to a number; `try_cast` fails safe (NULL → row hidden).
`'>='` = at/above the assigned value; use `'>'` for strictly above, `'<='`/`'<'` for below. All three
cases inject the analyst claim with `root=Inventory`, `mode=ABAC` (so 3a is off, only the threshold
3b fires).

**TH1 — the range grant returns the at/above-threshold slice.**
- *Trace:* B3 `root=Inventory` TRUE → threshold 3b passes each row whose `inv_quantity_on_hand >= 500`.
- *Expect:* **>0 rows** — the at/above-500 slice of a large fact table.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Inventory","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.inventory"
```

**TH2 — nothing below the threshold leaks (the killer, data-independent assertion).**
- *Trace:* the row filter is ANDed with the query predicate, so effectively `inv_quantity_on_hand >= 500 AND inv_quantity_on_hand < 500` → impossible for every row.
- *Expect:* **0 rows** — proves the cutoff holds regardless of how TPC-DS distributes quantities (the row filter can't be bypassed by the query predicate).
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Inventory","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.inventory WHERE inv_quantity_on_hand < 500"
```

**TH3 — the floor holds: `min(inv_quantity_on_hand) >= 500` (asserted).**
- *Trace:* aggregate the minimum visible quantity among the rows the filter lets through.
- *Expect:* **≥ 500** (asserted; observed run: exactly **500**, the boundary), confirming the floor the `>=` predicate enforces. (`≥` not `= 500` so a future data shift that keeps the floor doesn't false-fail.)
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Inventory","permissions":[]}' \
  "SELECT min(inv_quantity_on_hand) FROM abac_tpcds.tpcds_1_delta.inventory"
```

---

## DR. Direct RLS vs ABAC-tag hot-swap (sql/15)

Two contrasting ways to attach a row filter, set up by `sql/15` (run once as owner). **DR1** uses the
**classic, table-managed** form (no ABAC); **DR2** uses the **governance-tag ABAC policy** form and is
a stateful hot-swap the suite drives end to end.

**DR1 — direct classic RLS, no tags, no policy (`reason`).**
- *Setup:* `ALTER TABLE reason SET ROW FILTER rls_reason ON (r_reason_sk)` binds the pure predicate
  `k >= 20` **directly to the column** — no `has_tag`, no `CREATE POLICY`, no wrapper, no claim.
- *Trace:* UC applies the table-bound filter to the SP, keeping only `r_reason_sk >= 20`.
- *Expect:* `count(*) WHERE r_reason_sk < 20` = **0** — a data-independent proof the classic RLS form
  filters with **none** of the ABAC tag/policy machinery. (Every other governed case uses the
  `has_tag()` policy form — that's the contrast.)
```bash
J '{"tenant":1,"user":"probe","org":"100","mode":"DISABLE","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.reason WHERE r_reason_sk < 20"
```

**DR2 — ABAC `has_tag()` policy + live UDF hot-swap (`income_band`, 20 fixed rows).**
The suite runs this as a **scenario** (not a single `Case`): the policy binds the *stable* `dr2_wrapper`
→ the *SP-owned, swappable* inner `dr2_row_filter`. The suite replaces the **inner** UDF (so the
policy binding is never touched — no "function in use" conflict), waits **10 s**, and re-asserts.

| Step | What the suite does | Assert |
| --- | --- | --- |
| **DR2a** | query baseline (inner cutoff `<= 10`) | count = **10** |
| *swap* | `CREATE OR REPLACE dr2_row_filter … <= 5` (as the SP) + **sleep 10 s** | — |
| **DR2b** | re-query; prints swap→reflected elapsed ms | count = **5** |
| *revert* | `CREATE OR REPLACE dr2_row_filter … <= 10` | — |
| **DR2c** | re-query | count = **10** (clean revert → suite stays re-runnable) |

- **Why swap the *inner* UDF:** replacing a function that is **directly** bound as a row filter can be
  blocked by UC ("function in use"); binding a stable wrapper and swapping the function it *calls*
  sidesteps that.
- **Propagation:** with the 10 s delay the change is reflected on the next query (Databricks recompiles
  per statement); the printed elapsed characterizes how fast.
- **Requires** `sql/15` applied and the **SP to own `dr2_row_filter`** (so it can `CREATE OR REPLACE`
  it); if the SP can't replace even as owner, run `sql/15`'s commented `GRANT CREATE FUNCTION ON SCHEMA`.

---

## D. From the customer template — what's reusable

The template (`abac_docs/Databricks/scripts-templates/`) is the source of truth. What carries
straight over into these cases:

| Reusable piece | Where in the template | How it shows up in the cases |
| --- | --- | --- |
| **ctx struct** `STRUCT<tenant,user,org,mode,root,permissions>` | `create_get_user_context.sql` | the exact JSON claim shape in every case |
| **3 modes** `DISABLE` / `ABAC` / `RBAC_ABAC` | `create_row_filter.sql` | groups A / A / R |
| **`permissions` = object-type strings** (not `.view`) | `create_row_filter.sql` `array_contains(ctx.permissions, object_type)` | B1–B4 (B4 shows `.view` fails) |
| **3-branch logic + RBAC_ABAC org-tree** | `create_row_filter.sql` | the deployed filter that groups B-perm/R exercise |
| **object-type ↔ permission vocabulary** | `object_type_to_permission.sql` | see the map below |

**Real customer object types → permission infix** (from the template), and the POC analog our
cases use (from `abac_tpcds.abac.entity_type_to_object_type`):

| Customer object type | permission infix | POC analog (this repo) |
| --- | --- | --- |
| `Risk`, `Asset`, `Vendor`, `Control`, `Issue`, `Project`, `AISystem`, `Dataset`, … | `risks`, `assets`, `vendors`, `controls`, `issues`, `projects`, `ai-systems`, `datasets` | `Customer`, `Item`, `StoreSale`, `Store`, `Warehouse`, … |

So a claim shaped like the customer's real one —
`{"tenant":1,"user":"<dummy>","org":"<o>","mode":"RBAC_ABAC","root":"Risk","permissions":["Asset","Control"]}`
— maps 1:1 onto our POC as
`{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"RBAC_ABAC","root":"Customer","permissions":["Item","StoreSale"]}`.
Same struct, same modes, same "root = the one fine-grained type, permissions = coarse related types"
pattern — only the object-type names differ.

> The template also ships `create_mask_column.sql` (column masking) and `create_policy_no_type.sql`
> (the policy shape we already use). Masking is out of scope for these row-filter cases but is the
> natural next axis if we want to exercise `ABAC_AssignmentPermission` (which the row filter never reads).

---

## Conflict experiment — two row filters on one table (W / WP / WS)

Attach **two** row-filter policies to a table, both `TO` the SP, and see what prevails.

> **RESULT (observed):** Unity Catalog does **not** combine them — not AND, not OR. Both
> `CREATE POLICY` statements **succeed and coexist**, but at **query time** UC rejects the query:
> `[UC_ABAC_MULTIPLE_ROW_FILTERS] … resulted in multiple row filters. At most one row filter is
> allowed.` So the rule is **at most one row filter per table, enforced at evaluation** — a
> fail-loud design, no silent precedence. (The JDBC driver surfaces it as `SQLSTATE 42KDJ`; the full
> message text contains `UC_ABAC_MULTIPLE_ROW_FILTERS`.)

**No metadata needed.** The two filter functions are constant (`true` / `false`) — they read no
`ABAC_*` table and ignore the claim. This isolates the *policy-combination* behaviour from the ABAC
logic. **Governance tags ARE required:** DBR's ABAC policy feature does **not** accept a bare column
name in `USING COLUMNS` — a column must be resolved via `has_tag(...)` in a `MATCH COLUMNS` clause.
Because the tag lands on the numeric `w_warehouse_sk`, the filter functions take **`BIGINT`**.

Run this once as the **owner** (substitute your SP app id if different):

```sql
-- 1) Two trivial, deliberately conflicting row-filter functions (BIGINT: matches the tagged column).
CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.rf_allow_all(k BIGINT) RETURNS BOOLEAN RETURN true;
CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.rf_deny_all(k BIGINT)  RETURNS BOOLEAN RETURN false;

GRANT EXECUTE ON FUNCTION abac_tpcds.tpcds_1_delta.rf_allow_all TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT EXECUTE ON FUNCTION abac_tpcds.tpcds_1_delta.rf_deny_all  TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT   ON TABLE   abac_tpcds.tpcds_1_delta.warehouse    TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- 2) Tag the id column so has_tag() resolves it (idempotent; ONE column carries abac_column_id).
ALTER TABLE abac_tpcds.tpcds_1_delta.warehouse ALTER COLUMN w_warehouse_sk SET TAGS ('abac_column_id' = 'true');

-- 3) Two policies on the SAME table, both TO the SP — via the has_tag() form DBR requires.
CREATE OR REPLACE POLICY warehouse_allow_policy
ON TABLE abac_tpcds.tpcds_1_delta.warehouse
ROW FILTER abac_tpcds.tpcds_1_delta.rf_allow_all
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

CREATE OR REPLACE POLICY warehouse_deny_policy
ON TABLE abac_tpcds.tpcds_1_delta.warehouse
ROW FILTER abac_tpcds.tpcds_1_delta.rf_deny_all
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

-- 4) Confirm BOTH attached (if the 2nd CREATE errors, only one row filter per table is allowed).
SHOW POLICIES ON TABLE abac_tpcds.tpcds_1_delta.warehouse;
```

**W1 — warehouse, two row filters (allow-all + deny-all, same column).**
- *Trace:* two row filters resolve on one table → UC refuses to combine them (the claim is irrelevant — it errors before evaluation).
- *Expect:* **ERROR `UC_ABAC_MULTIPLE_ROW_FILTERS`.**
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.warehouse"
```

| Case W1 | What you saw | Meaning |
| --- | --- | --- |
| **PASS** | query errors `UC_ABAC_MULTIPLE_ROW_FILTERS` | **observed** — ≤1 row filter per table, enforced at query time |
| FAIL (rows) | a row count, no error | UC actually combined them (would contradict the above) |
| FAIL (diff error) | a different error | something else is wrong (permissions, missing table, …) |

**Follow-up — is the conflict tied to the shared column? (sql/12)**

`sql/12` pushes on that question with two more tables (in every case **both `CREATE POLICY`
statements succeed** — the conflict only surfaces at query time):

- **`web_page`** — `rf_page_1` binds **(`wp_web_page_sk`, `wp_access_date_sk`)** and `rf_page_2` binds **(`wp_access_date_sk`)** — different / only-overlapping bindings.
- **`web_site`** — `rf_site_1` and `rf_site_2` **both** bind the same `web_site_sk`.

**WP1 — web_page, `count(*)` (different bindings still conflict).**
- *Trace:* two row filters resolve on one table; UC refuses to combine them.
- *Expect:* **ERROR `UC_ABAC_MULTIPLE_ROW_FILTERS`.** Different `USING COLUMNS` bindings do **not** let two filters coexist.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.web_page"
```

**WP2 — web_page, a column bound by ONE filter only (the clincher).**
- *Trace:* `wp_web_page_sk` is bound by `rf_page_1` **only**, yet the query still errors.
- *Expect:* **ERROR `UC_ABAC_MULTIPLE_ROW_FILTERS`.** The conflict is **table-wide**, not tied to the shared or selected column.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT wp_web_page_sk FROM abac_tpcds.tpcds_1_delta.web_page"
```

**WS1 — web_site, two filters on the SAME column.**
- *Trace:* `rf_site_1` and `rf_site_2` both bind `web_site_sk` — same as W1 / WP1.
- *Expect:* **ERROR `UC_ABAC_MULTIPLE_ROW_FILTERS`.** The shared-column case behaves identically to the different-column case.
```bash
J '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.web_site"
```

**Generalized finding — a row filter is TABLE-WIDE.** Across `warehouse` (W1), `web_page` (WP1/WP2)
and `web_site` (WS1): a table may carry **at most one row filter**, enforced at **query time**
(`UC_ABAC_MULTIPLE_ROW_FILTERS`), **independent of the `USING COLUMNS` list**. Both `CREATE POLICY`
statements always succeed. You **cannot** make two row filters coexist by pointing them at different,
non-overlapping, or disjoint columns — the constraint is on the **table**, not the **column**. Design
a **single** row filter that encodes all the logic (as the real `abac_row_filter` does). Multiple
*tables* and a longer `TO`-list are fine; multiple row filters on the *same* table for the same
principal are not.

**Cleanup** when done:

```sql
DROP POLICY warehouse_allow_policy ON TABLE abac_tpcds.tpcds_1_delta.warehouse;
DROP POLICY warehouse_deny_policy  ON TABLE abac_tpcds.tpcds_1_delta.warehouse;
DROP FUNCTION abac_tpcds.tpcds_1_delta.rf_allow_all;
DROP FUNCTION abac_tpcds.tpcds_1_delta.rf_deny_all;
-- sql/12 has its own teardown for the web_page / web_site policies + functions.
```

---

## One-line summary per case

| Case | Branch that decides it | Result |
| --- | --- | --- |
| A1 | B1 DISABLE | ALL |
| A2 | B3b EXISTS(2012) | 1 |
| A3 | B3b — asserts id list = [2012] | [2012] |
| A4 | B3b EXISTS(3006) | 1 |
| A5 | B3b EXISTS(118144) | N |
| A6 | B3b no match (wrong user) | 0 |
| A7 | B3b no match (empty user) | 0 |
| A8 | B3 root≠object_type | 0 |
| A9 | **B2** array_contains(StoreSale) | ALL |
| B1 | **B2** array_contains(Item) | ALL |
| B2 | **B2** array_contains(StoreSale) | ALL |
| B3 | B2 fails (Item omitted) | 0 |
| B4 | B2 fails (.view format) | 0 |
| R1 | **3a OR 3b** | >0 (8) |
| R2 | **3b** (additive; org empty) | 1 |
| R3 | root≠object_type | 0 |
| R4 | **3a** (org, no assignment) | >0 (7) |
| ODEL | **3a** `orgHierarchy.isDeleted` | 0 |
| OLIVE | **3a** live child | >0 (1) |
| C1 | B3b (mode not special) | 1 |
| C2 | B1 fails (case) → B3b | 1 |
| C3 | B3 root case mismatch | 0 |
| C4 | B3b (perms null, unused) | 1 |
| C5 | B3b (extra key ignored) | 1 |
| C6 | B3b (tenant unused) — asserted | 1 |
| C7 | all null → no branch | 0 |
| C8 | B3b case-sensitive user | 0 |
| T1 | B3b (tenant unused) | 1 |
| T2 | **3a OR 3b** (tenant unused) | 8 |
| O1 | B3b (org unused in ABAC) | 1 |
| O2 | **3a** empty + no 3b | 0 |
| N1 | B3b `esa.isDeleted=true` | 0 |
| N2 | B3b USER_GROUP path | 1 |
| N3 | B3b `a.isActive=false` | 0 |
| N4 | B3b `a.isDeleted=true` | 0 |
| TH1 | 3b range `qty >= 500` | >0 |
| TH2 | 3b range ∧ `qty < 500` (impossible) | 0 |
| TH3 | 3b range — `min(qty)` asserted ≥500 | ≥500 |
| W1 | row-filter conflict | ERROR |
| WP1 | row-filter conflict | ERROR |
| WP2 | conflict (table-wide) | ERROR |
| WS1 | row-filter conflict | ERROR |
| DR1 | classic RLS `SET ROW FILTER` (no tags) | 0 |
| DR2a | ABAC `has_tag` policy, inner cutoff `<=10` | 10 |
| DR2b | inner UDF swapped `<=5` (+10s delay) | 5 |
| DR2c | inner UDF reverted `<=10` | 10 |

Expected suite line: `SUMMARY -> PASS 46 FAIL 0 INFO 0 ERROR 0` — the **43 cases** (all assert; A3/C6/TH3
are hard assertions against their observed outputs) **plus the 3 DR2 hot-swap checks** = 46. The **4
conflict cases** (W1/WP1/WP2/WS1) PASS by matching the `UC_ABAC_MULTIPLE_ROW_FILTERS` error text; **DR1**
proves classic RLS filters with no tags; **DR2a/b/c** prove a live UDF change reflects (10 → 5 → 10).

## Cross-checks

- Run **A2** (JDBC) and the same claim via **curl** ([`../deployment/runbook.md`](../deployment/runbook.md) §6–7) → counts must match (driver path == REST path).
- Run **A2** vs the owner-direct equivalent in `sql/11_explore_behaviours.sql` Part A → same allow/deny decision, proving the direct-call shortcut mirrors the live claim path.

**Related:** [`../deployment/oauth-jdbc-flow.md`](../deployment/oauth-jdbc-flow.md) (OAuth/claim plumbing) ·
[`../deployment/runbook.md`](../deployment/runbook.md) (end-to-end runbook) ·
`sql/05_dataset_udfs.sql` (the deployed 3-branch filter) ·
`sql/13`/`sql/14` (the M and TH onboarding) ·
[`explore-behaviours.md`](explore-behaviours.md) (owner-side behaviour sweep).
