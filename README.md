# ABAC on Azure Databricks — TPC-DS Replication

Replicate the customer's (OneTrust) Attribute-Based Access Control (ABAC) setup on **our**
Azure Databricks, using a copied **TPC-DS Delta** dataset as the test data instead of the
customer's application tables.

- **Target catalog:** `abac_tpcds`
- **Target schema (dataset + ABAC metadata):** `tpcds_1_delta`
- **Shared helper schema:** `abac_tpcds.abac`
- **Data:** the 12 TPC-DS Delta tables are **already loaded** in `abac_tpcds.tpcds_1_delta`.

This README is the single source of context for the effort: what the customer's ABAC system
is, exactly how it works, how it is deployed, how we map it onto TPC-DS, the corrections we
made after reading the real customer code, and the execution plan.

> **Docs & skill:** deployment / testing / archive docs live under [`docs/`](docs/) (start at
> [`docs/README.md`](docs/README.md)); the reusable Databricks UC ABAC semantics + POC playbook are a
> project skill at [`.claude/skills/databricks-abac/`](.claude/skills/databricks-abac/SKILL.md).

---

## Table of Contents

1. [Repository contents](#1-repository-contents)
2. [What ABAC is here (the big picture)](#2-what-abac-is-here-the-big-picture)
3. [The customer architecture](#3-the-customer-architecture)
4. [Runtime flow — how a query gets filtered/masked](#4-runtime-flow--how-a-query-gets-filteredmasked)
5. [The three modes](#5-the-three-modes)
6. [The core UDF logic in plain English](#6-the-core-udf-logic-in-plain-english)
7. [How the customer deploys it (migration order)](#7-how-the-customer-deploys-it-migration-order)
8. [Key findings & corrections vs. the earlier plan](#8-key-findings--corrections-vs-the-earlier-plan)
9. [Mapping the customer setup onto TPC-DS](#9-mapping-the-customer-setup-onto-tpc-ds)
10. [Decisions already made](#10-decisions-already-made)
11. [Open questions](#11-open-questions)
12. [Execution plan (SQL files)](#12-execution-plan-sql-files)
13. [Prerequisites & caveats](#13-prerequisites--caveats)

---

## 1. Repository contents

```
abac/
├── README.md                        ← this file (master: architecture, TPC-DS mapping, sql/ plan)
├── main.py                          ← unrelated PyCharm stub
├── .claude/skills/databricks-abac/  ← SKILL: reusable Databricks ABAC semantics + POC playbook
├── docs/                            ← deployment / testing / archive docs (see docs/README.md)
│   ├── deployment/                  ← oauth-jdbc-flow.md, runbook.md
│   ├── testing/                     ← jdbc-cases.md (61 cases + 12 scenarios), explore-behaviours.md
│   └── archive/                     ← abac-tpcds-setup-plan.md (superseded draft plan, see §8)
├── sql/                             ← the runnable execution plan (00–21, 99)
├── JDBC/                            ← JDBC client + Runner suite (see JDBC/README.md)
└── abac_docs/                       ← SOURCE OF TRUTH: real customer artifacts
    ├── customer_data/                       ← real metadata-table DDLs + sample data & scale estimates (see its README)
    ├── Databricks/
    │   ├── manifest.json                    ← lists the ABAC SQL objects & their files
    │   ├── scripts-templates/               ← the actual SQL function/policy templates
    │   │   ├── create_get_user_context.sql
    │   │   ├── create_entity_type_to_object_type.sql
    │   │   ├── create_object_type_to_permission.sql
    │   │   ├── create_row_filter.sql
    │   │   ├── create_row_filter_wrapper.sql
    │   │   ├── create_mask_column.sql
    │   │   ├── create_policy_default.sql    ← schema-level policy (id+type+org)
    │   │   └── create_policy_no_type.sql    ← per-table policy (id+org+literal type)
    │   └── Sentinel-Migration-Scripts/      ← how it is actually deployed (Python)
    │       ├── V20260710S001_create_ABAC_tags.py
    │       ├── V20260710S001_create_abac_row_filter_functions.py
    │       ├── V20260710S002_create_ABAC_functions.py
    │       ├── V20260710S002_create_abac_policies.py
    │       └── V20260710S003_tag_entity_table.py
    └── Java/                                 ← the app layer that produces the OAuth claim
        ├── NewReportQueryService.java        ← sets masking permissions, builds the query
        ├── QueryBuilder.java                 ← emits the masked/filtered SQL (CTE + struct)
        └── config/
            ├── DatabricksSessionContext.java ← builds {tenant,user,org,mode,root,permissions}
            ├── DatabricksSQLConfig.java       ← OAuth vs PAT; oauth flag gates masking
            ├── DatabricksASTTranslator.java   ← Hibernate→Databricks SQL rendering
            ├── DatabricksQueryDSLTemplates.java
            ├── CatalogResolver.java, DatabricksDialect.java, … (plumbing)
```

> **`abac_docs/` is authoritative.** Where `docs/archive/abac-tpcds-setup-plan.md` disagrees with
> `abac_docs/`, follow `abac_docs/`. See §8.

---

## 2. What ABAC is here (the big picture)

ABAC decides, **per row** and **per column**, whether the current user may see data. Unlike
static Unity Catalog `GRANT`s, the decision is computed at query time from:

- **Who the user is** — carried in an **OAuth custom identity claim** (a JSON blob).
- **Metadata tables** — assignments, permissions, subject→entity mappings, group membership,
  and an org hierarchy.
- **Column tags** — Databricks governed tags mark which columns are the *id*, *type*, *org*
  (and *tenant*) so a single policy can be attached generically.

Enforcement is done by **Unity Catalog ABAC policies** that bind a **row-filter UDF** to
tables via tag matching. Column **masking** is applied by the application layer using a
`should_mask` UDF (only when OAuth is in use).

The application (a Java/Spring service) never sends the user's row/column entitlements as
literal SQL. Instead it authenticates to Databricks with **OAuth**, and Databricks exposes the
per-user claim to the UDFs through `current_oauth_custom_identity_claim()`.

---

## 3. The customer architecture

### 3.1 Two schemas

| Namespace | Holds |
| --- | --- |
| **`ABAC`** (shared helpers) | `get_user_context()`, `entity_type_to_object_type()`, `object_type_to_permission()` |
| **`@DBNAME`** (the application/tenant schema) | the metadata tables, `abac_row_filter()`, `abac_row_filter_wrapper()`, `abac_should_mask_column()`, and the ABAC policies |

In our replication: `ABAC` → **`abac_tpcds.abac`**, and `@DBNAME` → **`abac_tpcds.tpcds_1_delta`**.

### 3.2 The user-context struct (the OAuth claim)

`ABAC.get_user_context()` parses the claim:

```sql
RETURN from_json(current_oauth_custom_identity_claim(),
  'STRUCT<tenant:int, user:string, org:string, mode:string, root:string, permissions:array<string>>')
```

The claim JSON is literally a serialized `DatabricksSessionContext` (see
`DatabricksSessionContext.serialize()`), with these fields:

| Field | Meaning |
| --- | --- |
| `tenant` | tenant id (int) |
| `user` | user id (string) |
| `org` | the user's org id (root of their org subtree) |
| `mode` | `DISABLED` / `ABAC` / `RBAC_ABAC` — **derived**, see §5 |
| `root` | the **root object type** of the current query (e.g. `Risk`) |
| `permissions` | **array of object-*type* strings** the user has related-view access to (e.g. `["Control","Issue"]`) — **not** `xxx.view` permission strings (see §8) |

### 3.3 Metadata tables (in `@DBNAME`)

| Table | Columns (as used by the UDFs) | Purpose |
| --- | --- | --- |
| `ABAC_Assignment` | `id, isActive, isDeleted` | an assignment (grant) record |
| `ABAC_AssignmentPermission` | `assignmentID, name, isDeleted` | permission names attached to an assignment (e.g. `sales.basic.view`) |
| `ABAC_EntitySubjectAssignment` | `entityID, objectType, assignmentID, subjectType, subjectID, isDeleted` | maps a subject (`USER_ID` or `USER_GROUP`) to an entity via an assignment |
| `UserGroupMembers` | `groupID, memberID, isDeleted` | group membership |
| `orgHierarchy` | `orgID, parentOrgID, isDeleted` | org tree (used by RBAC_ABAC) |

> **These are the columns the UDFs read — a subset of the real physical schema.** The customer's
> actual DDL + sample data + per-tenant row-count estimates are in
> [`abac_docs/customer_data/`](abac_docs/customer_data/README.md). Key deltas: every table has a
> **`tenantHash`** (the real tenant isolation — which is why `ctx.tenant` is inert); `ABAC_Assignment.id`
> is a `long` and the table is partitioned by `objectType`; ESA adds `policyId` / `entityOrganizationId`
> (both unread by the filter) and runs **~600M–1B rows/tenant**; and **`orgHierarchy` is a *view* over an
> ancestor-closure**, so in production `parentOrgID = ctx.org` yields the **full org subtree**, not a
> single level (our POC seeds a plain adjacency list, hence single-level here).

### 3.4 Governed column tags

Created once as governed tags (`V20260710S001_create_ABAC_tags.py`):

| Tag | Marks |
| --- | --- |
| `abac_column_id` | the entity **id** column |
| `abac_column_type` | the entity **type** column (per-row object type) |
| `abac_column_org` | the **org** id column |
| `abac_column_tenant` | the **tenant** id column (for "Silverin") |

### 3.5 Policies (two shapes)

- **`create_policy_default.sql`** — attached **`ON SCHEMA @DBNAME`**, matches **three** tagged
  columns and passes them straight through:

  ```sql
  MATCH COLUMNS has_tag('abac_column_id')  as id,
                has_tag('abac_column_type') as type,
                has_tag('abac_column_org')  as org
  USING COLUMNS (id, type, org)
  ```

  Use this when the table has a real **per-row type column** (e.g. the customer's `Entity`
  table with `entityTypeReference`).

- **`create_policy_no_type.sql`** — attached **`ON TABLE @DBNAME.@TABLE`** for tables with no
  type column; the type is a **literal**:

  ```sql
  MATCH COLUMNS has_tag('abac_column_id') as id,
                has_tag('abac_column_org') as org
  USING COLUMNS (id, '@TYPE', org)
  ```

Both bind the row filter `@DBNAME.abac_row_filter_wrapper` `TO @SERVICE_PRINCIPAL`.

---

## 4. Runtime flow — how a query gets filtered/masked

1. The Java service resolves the query's **root object type** and builds a
   `DatabricksSessionContext` → `{tenant, user, org, mode, root, permissions}`.
2. It connects to Databricks over **OAuth** (`DatabricksSQLConfig`: `AuthMech=11`,
   `Auth_Flow=1`). The context is serialized into the **OAuth custom identity claim**.
   - If OAuth is *not* configured, it falls back to a PAT and **masking is disabled**
     (`QueryBuilder.oauth == false`).
3. `NewReportQueryService.query()` loads column metadata, and for every column whose required
   permission the user lacks, marks it with a **masking permission**.
4. `QueryBuilder` emits a query where each selected column is wrapped so that, per row,
   `abac_should_mask_column(...)` decides whether to null/replace the value (rendered client
   side as `****`).
5. **Row** filtering is not added to the SQL by the app — it is enforced by the **Unity
   Catalog ABAC row-filter policy** on the table, which calls
   `abac_row_filter_wrapper(id, type, org)` → `abac_row_filter(id, mapped_type, org, get_user_context())`.
6. Inside the UDFs, `get_user_context()` reads the OAuth claim, so the same physical query
   returns different rows/columns per user.

> Org filtering: when ABAC/RBAC_ABAC is enabled, the app **skips** its usual org `WHERE` filter
> (`QueryBuilder.addOrgFilter()` returns early) because the row-filter policy handles it.

---

## 5. The three modes

Mode is **derived**, not chosen by the user (`DatabricksSessionContext.getMode()`):

| Mode | When | Effect on the root table |
| --- | --- | --- |
| `DISABLED` | no object type, or the user lacks the object's `feature` permission | row filter returns **all** rows (`ctx.mode = 'DISABLE'`) |
| `RBAC_ABAC` | user has the object's **basic or advanced field** permission | see **everything under their org subtree** (via `orgHierarchy`) |
| `ABAC` | user has the feature but **not** field-level permission | see **only entities explicitly assigned** to them (or their groups) |

> Note the claim string is `DISABLE` in the SQL, while the Java enum is `DISABLED`.

---

## 6. The core UDF logic in plain English

### 6.1 `abac_row_filter(entity_id, object_type, org_id, ctx)` → boolean

A row is **visible** if **any** of:

1. `ctx.mode = 'DISABLE'` — ABAC off, show everything; **or**
2. **Not the root type** and the user has view access to that related type:
   `ctx.root <> object_type AND array_contains(ctx.permissions, object_type)`; **or**
3. **The root type** (`ctx.root = object_type`) and either:
   - `ctx.mode = 'RBAC_ABAC'` and `org_id` is in the user's org subtree
     (`orgHierarchy.parentOrgID = ctx.org`); **or**
   - there is an **explicit assignment** of this `entity_id`/`object_type` to the user
     (`subjectType='USER_ID' AND subjectID=ctx.user`) or to a group they belong to
     (`subjectType='USER_GROUP'` joined through `UserGroupMembers`), via an active,
     non-deleted `ABAC_Assignment`.

### 6.2 `abac_should_mask_column(entity_id, object_type, permission, ctx)` → boolean

Returns **true (mask)** when there is **no** active assignment granting `permission` for that
entity to the user/group. Permission hierarchy: an `.advanced.` grant satisfies a `.basic.`
request via `replace(ap.name, '.advanced.', '.basic.') = permission`.

### 6.3 `abac_row_filter_wrapper(entity_id, object_type, org_id)` → boolean

```sql
RETURN abac_row_filter(entity_id,
                       abac.entity_type_to_object_type(object_type),
                       org_id,
                       abac.get_user_context())
```

The policy passes the raw `object_type` (table/entity name or a literal), the wrapper maps it
to the canonical object type, and injects the live user context.

---

## 7. How the customer deploys it (migration order)

From `Sentinel-Migration-Scripts/` (Flyway-style ordered migrations):

1. **`S001_create_ABAC_tags`** — create the 4 governed tags.
2. **`S001_create_abac_row_filter_functions`** — create `row_filter` + `row_filter_wrapper`
   in `@DBNAME`.
3. **`S002_create_ABAC_functions`** — create `get_user_context`, `entity_type_to_object_type`,
   `object_type_to_permission` in `ABAC`.
4. **`S002_create_abac_policies`** — resolve the principal (service principals whose
   `displayName = 'databricks-abac-service-principal'`), then create the **default** schema
   policy plus **per-table `no_type`** policies for `CMB_Assessment→Assessment`,
   `CMB_VendorContract→Contract`, `CMB_ControlImplementation→Control`, `CMB_Risk→Risk`.
5. **`S003_tag_entity_table`** — tag a specific table's id/type/org columns (e.g. `Entity`:
   `entityID` / `entityTypeReference` / `orgID`).

(The `create_mask_column.sql` function is created alongside the others; masking is invoked by
the app layer, not by a standalone policy.)

---

## 8. Key findings & corrections vs. the earlier plan

`docs/archive/abac-tpcds-setup-plan.md` was written **before** the real customer code was available.
After reading `abac_docs/`, these are the corrections we adopt:

1. **`permissions` holds object-*type* strings, not `<prefix>.view` strings.** ⭐ Most important.
   - Real customer `create_row_filter.sql`: `array_contains(ctx.permissions, object_type)`.
   - `DatabricksSessionContext.getPermissions()` adds the **type** string to the set when the
     user has the related permission — so the array is `["Item","StoreSale", …]`, not
     `["items.view", …]`.
   - The earlier plan's
     `array_contains(ctx.permissions, concat(object_type_to_permission(object_type), '.view'))`
     is a **divergence**. **We will follow the customer** and use
     `array_contains(ctx.permissions, object_type)`.
   - Consequence: `get_test_user_context().permissions` for TPC-DS should contain **object
     types** (e.g. `array('Item','StoreSale','CustomerAddress')`), not `.view` strings. This
     also fixes the "store_sales returns 0 rows" surprise noted in the old plan.

2. **`object_type_to_permission()` is an app-layer helper, not used inside the row filter.**
   It maps an object type → a permission infix that the Java layer checks; it does **not**
   appear in `create_row_filter.sql`. Keep the function for fidelity, but do not wire it into
   the row filter.

3. **Two policy shapes, and TPC-DS is almost entirely the `no_type` case.** TPC-DS tables have
   no per-row object-type column, so we use **`create_policy_no_type` per table with a literal
   type**. The schema-level `default` policy (with `abac_column_type`) only applies if we
   synthesize a type column — optional, not needed for the first pass.

4. **Four governed tags, including `abac_column_tenant`.** The old plan listed only id/org.
   We create all four; `tenant` is unused by TPC-DS but kept for fidelity.

5. **Masking requires OAuth.** With PAT auth, `QueryBuilder.oauth == false` and masking is
   skipped. Our `abac_should_mask_column` can still be validated directly via SQL using
   `get_test_user_context()`.

6. **Mode is derived, and the SQL literal is `DISABLE`** (Java enum `DISABLED`). Our test
   contexts must emit `mode = 'DISABLE' | 'ABAC' | 'RBAC_ABAC'`.

---

## 9. Mapping the customer setup onto TPC-DS

### 9.1 Namespace mapping

| Customer | Our replication |
| --- | --- |
| `ABAC.*` | `abac_tpcds.abac.*` |
| `@DBNAME` = tenant schema | `abac_tpcds.tpcds_1_delta` |
| `@SERVICE_PRINCIPAL` | **TBD** (see §11) |

### 9.2 Tables in scope (12)

`customer`, `customer_address`, `item`, `store`, `store_sales`, `store_returns`,
`catalog_sales`, `catalog_returns`, `web_sales`, `web_returns`, `web_site`, `warehouse`.

Excluded first pass: `__unitystorage`, `store_sales_iceberg`, `web_sales_iceberg`,
`time_dim`, `web_page`.

### 9.3 Per-table id / object-type / org mapping

| Table | id column (`abac_column_id`) | object type literal | org column (`abac_column_org`) |
| --- | --- | --- | --- |
| `customer` | `c_customer_sk` | `Customer` | `c_current_addr_sk` |
| `customer_address` | `ca_address_sk` | `CustomerAddress` | `ca_address_sk` |
| `item` | `i_item_sk` | `Item` | *static* `'100'` (no org col) |
| `store` | `s_store_sk` | `Store` | `s_store_sk` |
| `store_sales` | `ss_customer_sk` | `StoreSale` | `ss_store_sk` |
| `store_returns` | `sr_customer_sk` | `StoreReturn` | `sr_store_sk` |
| `catalog_sales` | `cs_bill_customer_sk` | `CatalogSale` | `cs_bill_addr_sk` |
| `catalog_returns` | `cr_returning_customer_sk` | `CatalogReturn` | `cr_returning_addr_sk` |
| `web_sales` | `ws_bill_customer_sk` | `WebSale` | `ws_web_site_sk` |
| `web_returns` | `wr_returning_customer_sk` | `WebReturn` | `wr_returning_addr_sk` |
| `web_site` | `web_site_sk` | `WebSite` | `web_site_sk` |
| `warehouse` | `w_warehouse_sk` | `Warehouse` | `w_warehouse_sk` |

> For tables where the id and org are the same column (e.g. `store`, `web_site`, `warehouse`,
> `customer_address`), set both tags on that one column.

### 9.4 `entity_type_to_object_type` (TPC-DS)

Maps the lowercase table name → object type (`customer→Customer`, `store_sales→StoreSale`, …).
`object_type_to_permission` maps object type → permission infix (`Customer→customers`,
`StoreSale→sales`, …) — kept for fidelity, not used by the row filter.

### 9.5 Seed strategy

Because the row filter matches on real surrogate keys, seed
`ABAC_EntitySubjectAssignment.entityID` with **actual keys pulled from the tables**
(`INSERT … SELECT` a handful of `c_customer_sk`, `i_item_sk`, etc.) so the test wrapper
actually returns rows. Seed `orgHierarchy` with real `*_addr_sk` / store keys if RBAC_ABAC is
to be exercised.

### 9.6 Test-only additions (not in customer scripts)

- `abac_tpcds.abac.get_test_user_context()` — a deterministic context, e.g.
  `mode='ABAC', root='Customer', permissions=array('Item','StoreSale', …)` (object types!).
- `abac_tpcds.tpcds_1_delta.abac_row_filter_test_wrapper()` — same as the real wrapper but uses
  `get_test_user_context()` so we can validate with plain `WHERE …` before OAuth is wired.

---

## 9A. No-OAuth testing model (Phase 1 — superseded)

> **Superseded by OAuth (Phase 2).** OAuth custom claims now work via the token endpoint's
> `custom_claim` parameter, driven by `curl` and the [`JDBC/`](JDBC/) client. See
> [`docs/deployment/oauth-jdbc-flow.md`](docs/deployment/oauth-jdbc-flow.md). `get_user_context()` now uses
> `current_oauth_custom_identity_claim()`; the `ABAC_UserContext` lookup below is the fallback.

We are exercising ABAC **before** OAuth exists. Identity enters the whole system through a
single function, so we change **only that one function** and leave the entire customer design
(row filter, wrapper, metadata, tags, policies) untouched.

### 9A.1 The one change

`abac.get_user_context()` no longer parses an OAuth claim. It looks up the context by the real
Databricks-authenticated identity:

```sql
RETURN (
  SELECT named_struct('tenant',tenant,'user',user_name,'org',org,
                      'mode',mode,'root',root,'permissions',permissions)
  FROM abac_tpcds.tpcds_1_delta.ABAC_UserContext
  WHERE user_name = current_user() AND isDeleted = false
  LIMIT 1
);
```

`ABAC_UserContext` is a new table (one row per principal) that stands in for the OAuth claim.
When OAuth is introduced later, swap this function body back to
`from_json(current_oauth_custom_identity_claim(), …)` — nothing else changes.

### 9A.2 Consequences to remember

- **`ctx.user` is now the Databricks identity** (email for a user, **application id** for a
  service principal). Seed `ABAC_EntitySubjectAssignment.subjectID` / `UserGroupMembers.memberID`
  with that identity.
- **`mode` / `root` / `permissions` are static per principal** (stored in `ABAC_UserContext`)
  instead of computed per request by the app. In particular **`root` is fixed per principal**;
  to test a different root, edit the context row or add another principal.
- **Owners/metastore admins bypass row filters.** To observe filtering you must run **as the
  target principal** (the service principal `76d5804d-…`). The owner validates logic separately
  via `abac_row_filter_test_wrapper` (see `sql/06`).

### 9A.3 Targeting a service principal

Policies bind `TO` the **service principal** directly (`TO \`76d5804d-…\``); `current_user()`
returns the SP's **application id**, which is the key for its `ABAC_UserContext` row (see `sql/03`,
no-OAuth fallback only). The `TO` clause is a comma-separated list if you add more principals. The
JDBC/curl client (M2M OAuth as the SP) connects as that SP and asserts the filtered row counts.

### 9A.4 Checking whether Unity Catalog ABAC is enabled

There is no single boolean. Probe it (see `sql/00_diagnostics.sql`) — the **error message is the
answer**:

- `SHOW POLICIES ON SCHEMA abac_tpcds.tpcds_1_delta;` — parses/returns ⇒ POLICY DDL available.
- Create + drop a throwaway policy on `item` — succeeds ⇒ ABAC fully usable.
- UI: Catalog Explorer shows a **Policies** tab; **Settings → Catalog → Governed tags** is where
  the tag keys (`abac_column_id`, `abac_column_org`, …) are created; **Settings/Previews** lists
  **Attribute-Based Access Control**.

If the probes fail with "feature not enabled"/"unsupported", an **account admin** must enable
ABAC (Preview) + Governed Tags. The functions and `sql/06` validation still work without it; only
the `CREATE POLICY` statements (`sql/08`) need it.

---

## 10. Decisions already made

| Question | Decision |
| --- | --- |
| How to "apply" the rules | **Generate ready-to-run `.sql` files** (no CLI/connection on this machine). Run them in a Databricks notebook / SQL editor. |
| Are the tables loaded? | **Yes** — skip the copy step; build ABAC on the existing `abac_tpcds.tpcds_1_delta` tables. |
| Scope of first pass | **Full** — metadata, UDFs, validation, tags, **and** row-filter policies. |
| OAuth | **Active (Phase 2).** Custom claims are injected via the OAuth token endpoint's `custom_claim` parameter (raw `curl`) and the [`JDBC/`](JDBC/) client (driver token hot-swap, mirroring `DatabricksConnectionProxy.java`). `get_user_context()` now uses `current_oauth_custom_identity_claim()`. The `current_user()`/`ABAC_UserContext` path (§9A) is the no-OAuth fallback. See [`docs/deployment/oauth-jdbc-flow.md`](docs/deployment/oauth-jdbc-flow.md). |
| Policy principal | The **service principal** `76d5804d-…` — the JDBC/curl login the policies bind `TO` (`sql/08`). The *effective* ABAC user is the token's `claim.user` (a dummy email seeded in `ABAC_EntitySubjectAssignment`), not the policy target. |
| Masking | **Out of scope** — the customer masks in the app layer, not via policy. `abac_should_mask_column` is created for fidelity only (`sql/99_optional_masking.sql`) and not tested. |

---

## 11. Open questions

- **Testing is SP-only** — the policy target is the **service principal** `76d5804d-…`; there are
  no real end users. Effective identities are **dummy emails** set in `claim.user`
  (`u.analyst1` / `u.vendor.mgr` / `u.developer@example.com`), matched against seeded `subjectID`s.
- **Is Unity Catalog ABAC + Governed Tags enabled** in the workspace? (`sql/00` probes it; an
  account admin enables it if not.)
- **Should `org_id` come from address/store/site keys or a synthetic org mapping?** Affects
  RBAC_ABAC testing and `orgHierarchy` seeding.
- **Do we synthesize a per-row type column** to also exercise the schema-level `default` policy,
  or stay entirely on per-table `no_type` policies? (Default: `no_type` only.)

_Resolved: OAuth **active** (token `custom_claim`); masking out of scope; policy principal = the **service principal** `76d5804d-…`; effective identity = dummy emails via `claim.user`._

---

## 12. Execution plan (SQL files)

Generated under `sql/` — run in order in a Databricks SQL editor / notebook. **As the owner** for
`00`–`09` and `11`–`21`; validate `10` **via the service principal + claim** (JDBC/curl — see
`docs/testing/jdbc-cases.md`), or owner-direct via `sql/11`.

| File | Purpose |
| --- | --- |
| `00_diagnostics.sql` | verify the 12 tables; **probe whether ABAC is enabled**; sample real keys |
| `01_schemas.sql` | create `abac_tpcds.abac` (catalog + `tpcds_1_delta` already exist) |
| `02_metadata_tables.sql` | the 5 ABAC metadata tables **+ `ABAC_UserContext`** |
| `03_seed_metadata.sql` | seed 3 dummy-email testers (`subjectID` = `claim.user`), assignments, group, org tree; no-OAuth `ABAC_UserContext` fallback keyed on the SP |
| `04_helper_udfs.sql` | `get_user_context` (**`current_user()`-based**), `get_test_user_context`, `entity_type_to_object_type`, `object_type_to_permission` |
| `05_dataset_udfs.sql` | `abac_row_filter` (**customer semantics**), `abac_row_filter_wrapper`, `abac_row_filter_test_wrapper` |
| `06_validate_row_filter.sql` | **owner-side** validation via the test wrapper (no policy needed) — the checkpoint |
| `07_tags.sql` | `SET TAGS` on id/org columns for the 12 tables (governed tag keys created via UI/API) |
| `08_policies_row_filter.sql` | `CREATE POLICY … no_type` per table → the **service principal** `76d5804d-…` |
| `09_grants.sql` | `USE`/`SELECT`/`EXECUTE` grants to the **service principal** |
| `10_live_validation.sql` | **validate via the SP + claim** — count checks + DISABLE/ABAC/RBAC_ABAC scenario switches (see `docs/testing/jdbc-cases.md`) |
| `11_explore_behaviours.sql` | **owner-side** behaviour sweep — ctx/claim/mode/metadata grids + RBAC_ABAC, self-cleaning fixture (see `docs/testing/explore-behaviours.md`) |
| `12_rowfilter_conflict.sql` | two conflicting row-filter policies on `warehouse`/`web_page`/`web_site` → proves `UC_ABAC_MULTIPLE_ROW_FILTERS` (cases W1/WP1/WP2/WS1) |
| `13_onboard_new_tables.sql` | onboard `promotion`/`store`/`call_center`/`ship_mode` under the same wrapper + soft-deleted `orgHierarchy` rows (cases N1–N4, ODEL/OLIVE) |
| `14_threshold_filter.sql` | a **separate** threshold/range row filter on `inventory` (`>=` instead of `=`) (cases TH1–TH3) |
| `15_direct_rls.sql` | classic RLS on `reason` (no tags, no policy) + an ABAC `has_tag()` policy on `income_band` whose inner UDF the suite hot-swaps live (case DR1 + the DR2 scenario) |
| `16_views.sql` | views over the `sql/15`-governed tables — does a view bypass the filter? (cases V1–V3; **confirmed live 2026-07-23** — see `docs/testing/jdbc-cases.md`) |
| `17_policy_scope.sql` | `ON SCHEMA` policy scope, an isolated `abac_scope` schema (cases SC1–SC4; **confirmed live 2026-07-23**) |
| `18_tag_binding.sql` | `MATCH COLUMNS` tag-value binding, an ambiguous dual-tag alias, a no-match fail-open, an isolated `abac_tags` schema (cases TG1–TG3; **confirmed live 2026-07-23**) |
| `19_udf_contract.sql` | `USING COLUMNS` arity + declared-vs-bound type coercion, an isolated `abac_udf` schema (case UC2, **confirmed live 2026-07-23**; UC1 **removed** — DDL-time-only finding, not suite-observable) |
| `20_cross_mechanism.sql` | classic RLS + an ABAC policy on the SAME table, an isolated `abac_xmech` schema (case XT1; **confirmed live 2026-07-23**) |
| `21_except_and_defaults.sql` | `TO ... EXCEPT ...` principal exemption, an isolated `abac_gaps` schema (cases EX2/EX1; **confirmed live 2026-07-23**); also records (DDL-only, not a suite case) that a `DEFAULT` UDF param does not let `USING COLUMNS` omit an argument |
| `99_optional_masking.sql` | fidelity-only mask function; **not** part of the test path |

**Recommended checkpoint:** validate through `06` (as owner) before attaching policies (`07`+).
`16`–`21` have all been applied to a live workspace and confirmed (2026-07-23) — see
`docs/testing/jdbc-cases.md` for the observed results. The only remaining gap is the 7 `E6-*`
scenarios, which stay `SKIP` pending the e6data ABAC identity flow.

---

## 13. Prerequisites & caveats

- **Unity Catalog ABAC** (row-filter policies with `has_tag(...) MATCH COLUMNS`) must be enabled
  in the workspace — it is a preview feature. Policy statements will error clearly if not.
- **SQL UDFs with subqueries** (`EXISTS`/`NOT EXISTS`/`IN (SELECT …)`) require a current DBR /
  serverless SQL warehouse. The customer's `row_filter`/`mask` rely on them.
- **Masking needs OAuth.** Direct SQL validation with `get_test_user_context()` works regardless.
- **`get_user_context()` will fail to create if `current_oauth_custom_identity_claim()` is not a
  resolvable function** in the workspace — run `00_diagnostics.sql` first. The test path does not
  depend on it.
- **Identifier case:** Unity Catalog treats `ABAC` and `abac` as the same schema; we use
  lowercase `abac` throughout.
- Keep TPC-DS **table and column names unchanged** so mappings in §9 stay valid.
