# POC playbook — author, deploy & test an ABAC row filter end to end

The concrete recipe used to replicate the customer's (OneTrust) ABAC on Azure Databricks over a
TPC-DS Delta dataset (`abac_tpcds.tpcds_1_delta`). Companion to `SKILL.md`. For the abstract
Databricks semantics see [`databricks-policy-reference.md`](databricks-policy-reference.md); for the
runnable cases see `docs/testing/jdbc-cases.md`; for the OAuth plumbing see
`docs/deployment/oauth-jdbc-flow.md` and `docs/deployment/runbook.md`.

---

## 1. The identity context (the OAuth claim)

Identity enters through **one** function. Everything downstream reads its output:

```sql
CREATE FUNCTION abac.get_user_context() RETURNS STRUCT<...>
RETURN from_json(current_oauth_custom_identity_claim(),
  'STRUCT<tenant:int, user:string, org:string, mode:string, root:string, permissions:array<string>>');
```

| Field | Meaning |
|---|---|
| `tenant` | tenant id — **read nowhere in the row filter**; real tenant isolation is the `tenantHash` column on every metadata table, not this field |
| `user` | effective ABAC identity (a dummy email in the POC; the app's real user in production) |
| `org` | the user's org id — read **only** by the RBAC_ABAC org-subtree branch (3a) |
| `mode` | `DISABLE` / `ABAC` / `RBAC_ABAC` — **derived by the app**, not user-chosen. Note the SQL literal is `DISABLE` (Java enum `DISABLED`) |
| `root` | the root object type of the current query (e.g. `Customer`) — the one fine-grained type |
| `permissions` | **array of object-TYPE strings** (`["Item","StoreSale"]`) the user has related-view access to — **not** `.view` strings |

- `current_oauth_custom_identity_claim()` **hard-errors if the token carries no claim** — every read
  of a governed table needs a claim.
- Injection is M2M OAuth (`AuthMech=11`, `Auth_Flow=1`) with the token endpoint's `custom_claim`
  parameter; the JDBC client hot-swaps the driver token to carry it. See `oauth-jdbc-flow.md` §2–3.
- No-OAuth fallback (superseded): look the context up by `current_user()` from an `ABAC_UserContext`
  table — swap only this one function body.

## 2. The deployed row filter — 3 additive branches

A row is **visible** if **any** branch is TRUE (source: the customer template
`abac_docs/Databricks/scripts-templates/create_row_filter.sql` = repo `sql/05_dataset_udfs.sql`):

```sql
RETURN (
  ctx.mode = 'DISABLE'                                                    -- 1: ABAC off → all rows
  OR ( ctx.root <> object_type AND array_contains(ctx.permissions, object_type) )   -- 2: coarse related table
  OR ( ctx.root = object_type AND (                                       -- 3: the root type
         ( ctx.mode = 'RBAC_ABAC' AND org_id IN                          --   3a: org one-level subtree
             (SELECT orgID FROM orgHierarchy WHERE parentOrgID = ctx.org AND isDeleted = false) )
         OR EXISTS (                                                      --   3b: explicit assignment
           SELECT 1 FROM ABAC_EntitySubjectAssignment esa
           JOIN ABAC_Assignment a ON esa.assignmentID = a.id AND a.isActive AND a.isDeleted = false
           LEFT JOIN UserGroupMembers ugm
             ON esa.subjectType='USER_GROUP' AND esa.subjectID=ugm.groupID
                AND ugm.memberID=ctx.user AND ugm.isDeleted=false
           WHERE esa.isDeleted=false AND esa.entityID=entity_id AND esa.objectType=object_type
             AND ( ugm.memberID IS NOT NULL OR (esa.subjectType='USER_ID' AND esa.subjectID=ctx.user) ) )
  ) )
);
```

Facts that trip people up:
- **3b is NOT gated by mode**, so RBAC_ABAC is **additive**: root row shows if in the org subtree
  (3a) **OR** the user has an explicit assignment (3b). In `ABAC` mode 3a is false, so only 3b fires.
- **3a org scope depends on how `orgHierarchy` is shaped.** The customer's real `OrgHierarchy` is an
  **ancestor-closure** (each org paired with every ancestor incl. self + root — see
  `abac_docs/customer_data/`), so `parentOrgID = ctx.org` returns the **full subtree** and "whole org
  tree" is accurate. This POC seeds a plain parent→child **adjacency list**, so the same predicate is
  **single-level** (grandchildren excluded).
- Object type is the **table name mapped + capitalised** by `entity_type_to_object_type` (`customer →
  Customer`). Its `ELSE` returns the input unchanged, so a PascalCase literal like `'Promotion'`
  passes through — you can onboard new tables without editing that mapper.
- `permissions` are **object types**, not `.view` strings (a `.view` array matches nothing in branch 2).

**Metadata tables** the filter reads (schema, as used): `ABAC_Assignment(id, isActive, isDeleted)` ·
`ABAC_EntitySubjectAssignment(entityID, objectType, assignmentID, subjectType, subjectID, isDeleted)`
· `UserGroupMembers(groupID, memberID, isDeleted)` · `orgHierarchy(orgID, parentOrgID, isDeleted)`.

> These are the **POC subset**. The customer's real physical schemas are richer — every table has a
> **`tenantHash`** (the real tenant isolation), ESA adds `policyId`/`entityOrganizationId` (both
> unread by the filter), `ABAC_Assignment.id` is a `long` and both it and ESA are **partitioned by
> `objectType`**, and `OrgHierarchy` is a **view over an ancestor-closure**. Scale is the headline:
> ESA is **~600M–1B rows/tenant**, so the per-row `EXISTS` (pruned by the `objectType` partition) is
> the planner's #1 optimization target. Real DDL + sample data + estimates:
> [`abac_docs/customer_data/`](../../../../abac_docs/customer_data/README.md).

## 3. Onboarding a table (the `no_type` policy shape)

TPC-DS tables have no per-row type column, so use the **literal-type** policy per table:

```sql
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.<t> TO `<SP_APP_ID>`;
ALTER TABLE abac_tpcds.tpcds_1_delta.<t> ALTER COLUMN <id_col> SET TAGS ('abac_column_id' = 'true');
-- (add 'abac_column_org' on the org column, or use a literal for org)
CREATE OR REPLACE POLICY <t>_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.<t>
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `<SP_APP_ID>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id [, has_tag('abac_column_org') AS org]
USING COLUMNS (id, '<PascalCaseTypeLiteral>', org);   -- org may be a literal like '100'
```

Seed a matching `ABAC_Assignment` + `ABAC_EntitySubjectAssignment` (entityID = the real id value; use
`INSERT … SELECT` to pull actual surrogate keys) so the filter actually returns rows.
(Schema-level `default` policy variant with `abac_column_type` is for tables that DO have a per-row
type column — not needed for TPC-DS.)

## 4. Deploy order (repo `sql/`)

`00` diagnostics/probe ABAC enabled → `01` schemas → `02` metadata tables → `03` seed → `04` helper
UDFs (`get_user_context`, `entity_type_to_object_type`, …) → `05` the row filter + wrapper → `06`
owner-side validation (test wrapper, no policy) → `07` tags → `08` policies → `09` grants → `10` live
SP validation → `11` owner behaviour sweep. Extras: `12` row-filter conflict, `13` onboard 4 more
tables, `14` threshold/range filter, `15` direct **classic RLS** (`reason`, `SET ROW FILTER`, no tags)
+ an ABAC `has_tag()` policy whose inner UDF the suite **hot-swaps** live (`income_band`), `16`
**views** over already-governed base tables (does a view bypass the filter?), `17` **policy SCOPE**
(`ON SCHEMA` vs `ON TABLE`, an isolated `abac_scope` schema), `18` **tag BINDING** (`has_tag_value()`
matching, an ambiguous dual-tag alias, a no-match fail-open, an isolated `abac_tags` schema), `19`
the **UDF CONTRACT** (`USING COLUMNS` arity, declared-vs-bound type coercion, an isolated `abac_udf`
schema), `20` **cross-mechanism** conflict (classic RLS + an ABAC policy on the same table, an
isolated `abac_xmech` schema), `21` **`EXCEPT` + the UDF arity/`DEFAULT` question** (does
`TO ... EXCEPT <principal>` actually exempt a principal from a row filter, and does a `DEFAULT`
param let `USING COLUMNS` omit an argument — an isolated `abac_gaps` schema).
**Checkpoint:** validate through `06` (as owner) before attaching policies. `16`–`21` are each
self-contained and idempotent, applied **as owner** in numeric order — see `docs/testing/jdbc-cases.md`
(groups V, SC, TG, UC, XT, EX, and the VP scenario) for the cases each one unlocks. **All of
`16`–`21` have been applied to a live workspace and confirmed** (2026-07-23) — see the skill's
gotchas table and `docs/testing/jdbc-cases.md` for the observed results. The one exception is the
7 `E6-*` scenarios, which remain `SKIP` pending the e6data ABAC identity flow.

`sql/21` (`EXCEPT`) plus three Databricks-auth-specific scenarios that create **no new SQL** — SEC
(secret invariance), MSP (second-principal / `TO`-set targeting), EXP (token expiry) — together cover
principal exemption, multi-secret auth invariance, second-principal targeting, and token expiry; all
four confirmed live 2026-07-23, each env-gated and `SKIP` by default (see `docs/testing/jdbc-cases.md`'s
EX, SEC, MSP, and EXP sections).

## 5. Variations you can build on the same skeleton

- **Threshold / range grant** (`sql/14`, on `inventory`): change 3b's match from `esa.entityID =
  entity_id` to `try_cast(entity_id AS BIGINT) >= try_cast(esa.entityID AS BIGINT)` → "show every row
  at/above the assigned value." `try_cast` is mandatory (STRING `>=` is lexicographic). Keep it a
  **separate** filter/policy so the exact-match cases are unaffected. `'>'` = strictly above,
  `'<='`/`'<'` = below.
- **Group grants**: an ESA row with `subjectType='USER_GROUP'` + a `UserGroupMembers` row → the
  `LEFT JOIN ugm` path grants access (N2).
- **Kill switches** (all → 0 rows): `esa.isDeleted=true`, `a.isActive=false`, `a.isDeleted=true`,
  `orgHierarchy.isDeleted=true`.

## 6. Testing methodology

- **Testers are dummy emails**, not real users. The SP the policy binds `TO` authenticates; the
  effective identity is `claim.user`, which must equal a seeded `subjectID`. Wrong/empty user → 0.
- **Owners bypass row filters** — to see filtering you must run as the SP. Owner-side, validate logic
  by calling a *test wrapper* with a literal ctx (`sql/06`, `sql/11`) — no policy needed.
- **The 61-case JDBC suite + 12 scenarios** (`Runner.java`, cases in `cases/Cases.java`) self-seeds a
  namespaced fixture, injects each claim via the OAuth hot-swap, and asserts row counts / error text.
  `ENGINE` (env var) selects the target — `databricks` (default) or `e6data`; capability gating
  (`Capability` + `Engine.supports()`) reports `SKIP`, not a false PASS/FAIL, for any case or
  scenario the selected engine doesn't support yet. Groups: A (pure ABAC), B-perm (permissions
  branch), R (RBAC_ABAC org tree), C (claim parsing/case), T/O (tenant & org sensitivity), M/N (new
  governed tables), TH (threshold), W/WP/WS (conflict negatives), V (views, `sql/16`), SC (policy
  scope, `sql/17`), TG (tag binding, `sql/18`), UC (UDF contract, `sql/19`), XT (cross-mechanism,
  `sql/20`), EX (`EXCEPT`, `sql/21`), CL (malformed claims). Scenarios: DR2 (hot-swap, `sql/15`), VP
  (view + live policy-swap, reuses `sql/15`/`sql/16`), SEC (secret invariance), MSP (second-principal
  targeting), EXP (token expiry) — SEC/MSP/EXP are Databricks-auth-specific, create no new SQL, and
  `SKIP` by default unless their env vars are set (`CLIENT_SECRET_ALT`; `SP2_CLIENT_ID`+
  `SP2_CLIENT_SECRET`; `ABAC_EXPIRED_TOKEN`) — + 7 `E6-*` placeholders awaiting the e6data ABAC
  identity flow. **All Databricks groups are confirmed live** (2026-07-23): a clean run (no
  Databricks-auth env vars set) is `SUMMARY -> PASS 67 FAIL 0 SKIP 10 INFO 0 ERROR 0`, where SKIP 10
  is the 7 `E6-*` placeholders (the only thing still genuinely untested) plus SEC/MSP/EXP skipping by
  default; each of SEC/MSP/EXP has been **individually** confirmed live (PASS) with its env vars set.
  Full catalog + per-row trace: `docs/testing/jdbc-cases.md`.
- **Deterministic assertions beat data-dependent ones.** E.g. the threshold proof: `count(*) WHERE
  qty < 500` = 0 is guaranteed because the row filter (`qty >= 500`) is ANDed with the query
  predicate — no dependence on the data distribution.
- **Cross-check** the driver path against the REST path (curl §6–7 of `runbook.md`) — counts must match.

## 7. Security notes

- The SP **client secret** stays app-side only — never in the repo. Rotate if leaked.
- Any sensitive table left **unbound** by a policy is wide open to anyone with `SELECT` — bind a
  policy to every governed table.
- ABAC policies **do not grant access**; they only restrict what SELECT already allows.
