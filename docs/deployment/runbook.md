# First JDBC Script — OAuth ABAC End-to-End Runbook

> How a query flows from the JDBC client → OAuth token (with a custom claim) →
> Databricks policy engine → row-filter UDF → filtered rows.
> Secrets below are for local testing only — **do not commit real values**.

---

## 1. How it works (the flow)

- The user sends **2 args** to the Java (JDBC) script:
  1. the **SQL** itself
  2. the **claim** he has on the object
- If he passes a **false claim** → he is **denied**.
- If he passes the **correct claim** → he is **allowed**.

Inside the JDBC script itself we have **3 things**:
1. the user email
2. the **client id** of the service principal
3. the **client secret** of the service principal

So for that session, the service principal is associated with the user we logged in as.
Once authentication is done and the user's query reaches DBR:

1. First we search for all the **policies** associated with this service principal and collate them.
   (Say he has only one policy as of now.)
2. We check whether this is a **dummy** policy or an **actual** policy — decided by the
   **governance tags**, which act like a `WHERE` condition. Only if they are **true** is the
   policy applied to the user.
3. The policy internally contains a **row-filter wrapper** that receives **3 things**:
   1. the **column** on which to apply the row filter (the tagged `id` column)
   2. the **table / object type**, normalised/capitalised via a UDF
   3. a **3rd column** (`org_id`) — see review note ⓐ below
4. The wrapper sends this info to the **actual row filter**, with one additional parameter —
   `get_user_context()` — which carries our **ctx JSON**.
5. The actual row filter uses the **shared/generic UDFs** on the **metadata tables** together
   with the user context, and evaluates **OR conditions** — if any is true, the user has access
   to at least some rows:
   The deployed filter has **three branches** (see the SQL below):
   - **Condition 1** — allow-all / deny-all (`mode = 'DISABLE'`).
   - **Condition 2** — non-root object type: if `ctx.permissions` lists this object type → sees the whole table (ABAC coarse). *(see review note ⓑ)*
   - **Condition 3** — root object type: either `RBAC_ABAC` (whole org subtree via `org_id`/`orgHierarchy`) **or** partial / per-row access (explicit assignment).
6. The 4 metadata tables are consumed inside the row filter. *(see review note ⓒ)*

---

## 2. Policies

```sql
SHOW POLICIES ON TABLE `customer`;
SHOW POLICIES ON TABLE `item`;
SHOW POLICIES ON TABLE `store_sales`;

DROP POLICY `tpcds_1_delta_customer_abac_policy`   ON TABLE customer;
DROP POLICY `@tpcds_1_delta_item_abac_policy`       ON TABLE item;
DROP POLICY `@SCHEMA_store_sales_abac_policy`        ON TABLE store_sales;
DROP POLICY `@tpcds_1_delta_store_sales_abac_policy` ON TABLE store_sales;
```

```sql
CREATE OR REPLACE POLICY `tpcds_1_delta_customer_abac_policy`
ON TABLE `customer`
ROW FILTER `abac_row_filter_wrapper`
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS
  has_tag('abac_column_id') as id,
  has_tag('abac_column_org') as org
USING COLUMNS (id, 'customer', org);


CREATE OR REPLACE POLICY `tpcds_1_delta_store_sales_abac_policy`
ON TABLE `store_sales`
ROW FILTER `abac_row_filter_wrapper`
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS
  has_tag('abac_column_id') as id,
  has_tag('abac_column_org') as org
USING COLUMNS (id, 'store_sales', org);


CREATE OR REPLACE POLICY `tpcds_1_delta_item_abac_policy`
ON TABLE `item`
ROW FILTER `abac_row_filter_wrapper`
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS
  has_tag('abac_column_id') as id
USING COLUMNS (id, 'item', '100');
```

---

## 3. Functions

Move from the deterministic **test** wrapper to the real **OAuth** wrapper.

```sql
DROP FUNCTION abac_row_filter_test_wrapper;
DROP FUNCTION abac_row_filter;
```

```sql
CREATE OR REPLACE FUNCTION `abac_row_filter_wrapper`(
  entity_id STRING,
  object_type STRING,
  org_id STRING
)
RETURNS BOOLEAN
RETURN `abac_row_filter`(
  entity_id,
  `abac_tpcds`.`abac`.`entity_type_to_object_type`(object_type),
  org_id,
  `abac_tpcds`.`abac`.`get_user_context`()
);
```

```sql
CREATE OR REPLACE FUNCTION `abac_row_filter`(
  entity_id STRING,
  object_type STRING,
  org_id STRING,
  ctx STRUCT<tenant: INT, user: STRING, org: STRING, mode: STRING, root: STRING, permissions: ARRAY<STRING>>
)
RETURNS BOOLEAN
RETURN (
  ctx.mode = 'DISABLE'
  -- Not the root type: allow if the user may view this related object type
  OR (
    ctx.root <> object_type
    AND array_contains(ctx.permissions, object_type)
  )
  -- The root type: real ABAC / RBAC_ABAC checks
  OR (
    ctx.root = object_type
    AND (
      -- RBAC_ABAC: show everything in the user's org subtree (single level)
      (
        ctx.mode = 'RBAC_ABAC'
        AND org_id IN (
          SELECT orgID FROM `orgHierarchy`
          WHERE parentOrgID = ctx.org
            AND isDeleted = false
        )
      )
      OR EXISTS (
        SELECT 1
        FROM `ABAC_EntitySubjectAssignment` esa
        JOIN `ABAC_Assignment` a
          ON esa.assignmentID = a.id
          AND a.isActive
          AND a.isDeleted = false
        LEFT JOIN `UserGroupMembers` ugm
          ON esa.subjectType = 'USER_GROUP'
          AND esa.subjectID = ugm.groupID
          AND ugm.memberID = ctx.user
          AND ugm.isDeleted = false
        WHERE esa.isDeleted = false
          AND esa.entityID = entity_id
          AND esa.objectType = object_type
          AND (
            ugm.memberID IS NOT NULL
            OR (esa.subjectType = 'USER_ID' AND esa.subjectID = ctx.user)
          )
      )
    )
  )
);
```

```sql
CREATE OR REPLACE FUNCTION `abac_tpcds`.`abac`.`get_user_context`()
RETURNS STRUCT<
  tenant: INT,
  user: STRING,
  org: STRING,
  mode: STRING,
  root: STRING,
  permissions: ARRAY<STRING>
>
RETURN from_json(
  current_oauth_custom_identity_claim(),
  'STRUCT<tenant: int, user: string, org: string, mode: string, root: string, permissions: array<string>>'
);

SELECT current_oauth_custom_identity_claim();

DROP FUNCTION abac_tpcds.abac.get_test_user_context;
```

---

## 4. Metadata tables (used by the row filter)

```text
-- being used in the row filter — should have actual data instead of dummy?
-- abac_tpcds.tpcds_1_delta.abac_assignment
-- abac_tpcds.tpcds_1_delta.abac_entitysubjectassignment
-- abac_tpcds.tpcds_1_delta.orghierarchy
-- abac_tpcds.tpcds_1_delta.usergroupmembers
```
*(See review note ⓒ for which of these actually need real rows.)*

---

## 5. Environment variables

> OAuth token generation runs as the service principal (data-editor access granted).
>
> ⚠️ **Never commit real secrets.** The values below are placeholders — supply the real
> `CLIENT_SECRET` from your local environment / a secret manager at runtime, and rotate it if it
> was ever committed. The SP client secret must stay app-side only, never in the repo.

```bash
export CLIENT_ID="<SP_APPLICATION_ID>"
export CLIENT_SECRET="<CLIENT_SECRET>"          # from your secret store — do NOT hard-code
export WORKSPACE_HOST="<workspace-host>.azuredatabricks.net"
export WAREHOUSE_ID="<WAREHOUSE_ID>"
export CUSTOM_CLAIM='{"tenant":1,"user":"76d5804d-d302-4014-a1d3-d846f02c84ef","org":"100","mode":"ABAC","root":"Customer","permissions":["customers.view","customers.basic.view","items.view","sales.view","sales.basic.view"]}'
```

---

## 6. Get the OAuth access token (with the custom claim)

```bash
curl -s -u "${CLIENT_ID}:${CLIENT_SECRET}" \
  -X POST "https://${WORKSPACE_HOST}/oidc/v1/token" \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "scope=all-apis" \
  --data-urlencode "custom_claim=${CUSTOM_CLAIM}" \
  | tee /tmp/token_response.json
```

Then export the token from the response:

```bash
export ACCESS_TOKEN="<access_token from /tmp/token_response.json>"
```

---

## 7. Verify the claim + (re)create the OAuth functions via the SQL Statements API

```bash
# See the claim the warehouse receives for this token
curl -s -X POST "https://${WORKSPACE_HOST}/api/2.0/sql/statements" \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"warehouse_id\": \"${WAREHOUSE_ID}\", \"statement\": \"SELECT current_oauth_custom_identity_claim()\", \"wait_timeout\": \"30s\"}"
```

```bash
# get_user_context (OAuth body)
curl -s -X POST "https://${WORKSPACE_HOST}/api/2.0/sql/statements" \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"warehouse_id\": \"${WAREHOUSE_ID}\", \"statement\": \"CREATE OR REPLACE FUNCTION abac_tpcds.abac.get_user_context() RETURNS STRUCT< tenant: INT, user: STRING, org: STRING, mode: STRING, root: STRING, permissions: ARRAY<STRING> > RETURN from_json( current_oauth_custom_identity_claim(), 'STRUCT<tenant: int, user: string, org: string, mode: string, root: string, permissions: array<string>>' );\", \"wait_timeout\": \"30s\"}"
```

```bash
# abac_row_filter_wrapper (fully qualified)
curl -s -X POST "https://${WORKSPACE_HOST}/api/2.0/sql/statements" \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"warehouse_id\": \"${WAREHOUSE_ID}\", \"statement\": \"CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper( entity_id STRING, object_type STRING, org_id STRING ) RETURNS BOOLEAN RETURN abac_tpcds.tpcds_1_delta.abac_row_filter( entity_id, abac_tpcds.abac.entity_type_to_object_type(object_type), org_id, abac_tpcds.abac.get_user_context() );\", \"wait_timeout\": \"30s\"}"
```

---

## 8. Run the JDBC client

Arg 1 = claim JSON, Arg 2 = SQL.

```bash
java -jar target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar \
  '{"tenant":1,"user":"","org":"100","mode":"ABAC","root":"Customer","permissions":["customers.view","customers.basic.view","items.view","sales.view","sales.basic.view"]}' \
  "SELECT * FROM abac_tpcds.tpcds_1_delta.customer ORDER BY 1"
```

---

## ⚠️ Review notes (added — answers to the inline questions)

**ⓐ Why is the 3rd column (`org_id`) sent?**
It is the **org id**, used by the `RBAC_ABAC` branch of the deployed customer row
filter: `org_id IN (SELECT orgID FROM orgHierarchy WHERE parentOrgID = ctx.org …)`. The
deployed `abac_row_filter` above **does** include the `RBAC_ABAC` branch, so `org_id` is
**used** (whenever `ctx.mode = 'RBAC_ABAC'` on the root type). For `item` you pass the literal
`'100'` because it is a non-root type and takes the middle (permissions) branch instead.

**ⓑ Your `abac_row_filter` now has all 3 conditions.**
The middle branch — "not the root type but I hold this object type" — is **present**:
```sql
OR ( ctx.root <> object_type AND array_contains(ctx.permissions, object_type) )
```
Consequence with the deployed 3-branch version:
- `customer` (root = Customer) → root branch: `RBAC_ABAC` org-subtree check **or** per-row assignment check ✅ works.
- `item`, `store_sales` (non-root) → **now return rows** when the object type is listed in
  `ctx.permissions` (via the middle branch). No longer the "store_sales returns nothing" surprise.
The permission format still has to match (see below) for the middle branch to fire.

**⚠️ Permission format mismatch (important).**
The middle branch checks `array_contains(ctx.permissions, object_type)` — i.e. it expects
**object-type strings** like `'Item'`, `'StoreSale'`. But your `CUSTOM_CLAIM` permissions are
`.view` strings (`"items.view"`, `"sales.view"`). Pick ONE convention and make the claim and the
filter agree:
- object-type style (customer source of truth): claim `permissions:["Item","StoreSale"]`; filter `array_contains(ctx.permissions, object_type)`.
- `.view` style (older plan): filter `array_contains(ctx.permissions, concat(object_type_to_permission(object_type), '.view'))`.

**⚠️ `ctx.user` must equal `ABAC_EntitySubjectAssignment.subjectID` — or customer returns 0 rows.**
With OAuth, `ctx.user` is whatever the **claim** says — a **dummy email** the caller chooses,
decoupled from the SP that authenticates. It must equal a `subjectID` seeded in
`ABAC_EntitySubjectAssignment`. The current seed (03) uses `u.analyst1@example.com` (Customer),
`u.vendor.mgr@example.com` (Item), `u.developer@example.com` (StoreSale). Note the **JDBC client**
claim above sets `user = ""` (empty) → matches nothing → **denied** (fine as a "false claim" test;
the "allow" test needs `user` = one of those dummy emails, with `root` matching that tester's type).
Full case catalog: `../testing/jdbc-cases.md`.

**ⓒ Which metadata tables actually need real data (deployed 3-branch)?**
- `ABAC_EntitySubjectAssignment` — **yes** (root branch, ABAC/USER_ID), with real `entityID` = actual `c_customer_sk` values, `objectType='Customer'`, `subjectID = ctx.user`.
- `ABAC_Assignment` — **yes**, the referenced assignment must be `isActive=true, isDeleted=false`.
- `UserGroupMembers` — only if you use `USER_GROUP` assignments (else unused).
- `orgHierarchy` — **used** by the `RBAC_ABAC` branch (root type, `mode = 'RBAC_ABAC'`); needs real `orgID`/`parentOrgID` rows for that path to match.
- `ABAC_AssignmentPermission` — **not** used by the row filter at all (only by the masking function).

**Minor:** `WORKSPACE_HOST` has a trailing `/` → URLs become `https://host//oidc…`. Usually
tolerated, but safer to drop it: `adb-536298298.18.azuredatabricks.net`.
