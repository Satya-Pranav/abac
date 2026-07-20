---
name: databricks-abac
description: Use when working on Databricks Unity Catalog ABAC row-filter or column-mask policies — CREATE POLICY, MATCH COLUMNS / USING COLUMNS binding, has_tag governed tags, one-row-filter-per-table conflicts (UC_ABAC_MULTIPLE_ROW_FILTERS / SQLSTATE 42KDJ), fail-closed behavior, the get_user_context / current_oauth_custom_identity_claim OAuth context, or replicating these semantics in the e6data query planner.
---

# Databricks Unity Catalog ABAC (row filters & column masks)

## Overview

ABAC decides **per row** and **per column**, at query time, whether the current identity may see
data. A `CREATE POLICY` binds a **UDF** (a row filter returning BOOLEAN, or a column mask returning
the column's type) to tables via **governed column tags** (`has_tag(...)`), and passes the current
identity through an **OAuth custom claim**. Unlike `GRANT`, the decision is computed from live
metadata + the claim — but **ABAC never grants access; it only filters/masks what the identity can
already SELECT.**

Reference material under this skill:
- **`references/databricks-policy-reference.md`** — the doc-verified Databricks ABAC semantics (the
  e6data planner-side view): full `CREATE POLICY` grammar, argument binding, tag inheritance, conflict
  rules, limits, fail-closed. **Read this for "how does Databricks ABAC actually behave?"**
- **`references/poc-playbook.md`** — the concrete POC on TPC-DS: the deployed 3-branch row filter, the
  OAuth `custom_claim` hot-swap, the deploy order, and the 43-case JDBC test methodology (+ classic-RLS and live-UDF-swap cases). **Read this
  for "how do I author/deploy/test one of these end to end?"**
- **`references/unity-catalog-governance.md`** — the broader Unity Catalog governance model (RBAC,
  ABAC, RLS/CLS, workspace–catalog bindings, storage credentials) and how these row-filter/column-mask
  policies coexist with RBAC. **Read this for "where does ABAC sit among Databricks' other governance
  layers?"**

The **authoritative customer source** the POC replicates lives in
[`../../../abac_docs/`](../../../abac_docs/): `Databricks/scripts-templates/` (the real
`create_row_filter.sql` + wrapper / mask / `get_user_context` / `create_policy_default` /
`create_policy_no_type` templates), `Databricks/Sentinel-Migration-Scripts/` (how it's actually
deployed), and `Java/` (the app layer that builds the OAuth claim). **When the reference and a
customer template disagree, the template wins.**

## When to use

- Writing or reviewing a `CREATE POLICY … ROW FILTER` / `… COLUMN MASK`.
- A governed table errors (`UC_ABAC_MULTIPLE_ROW_FILTERS`, "no column matches the tag", a fail-closed
  block) or a policy silently does nothing.
- Deciding how `MATCH COLUMNS` / `USING COLUMNS` bind UDF arguments.
- Designing the row-filter UDF logic, the OAuth claim struct, or the metadata tables behind it.
- Replicating any of this behavior in the e6data planner.

**Not for:** static Unity Catalog `GRANT`s, or ABAC **GRANT** policies (this skill is row-filter &
column-mask policies only).

## Quick reference — the mental model

```
ON  CATALOG|SCHEMA|TABLE = where to search for candidate tables (scope; NOT copied to children)
TO / EXCEPT              = which querying identities are subject / exempt
FOR TABLES               = the only supported target type for RF/CM policies (fixed)
WHEN <tag cond>          = which tables apply, by effective TABLE tags (default TRUE)
MATCH COLUMNS <tag cond> AS alias = find target/input columns by DIRECT COLUMN tags (≤3 exprs; ALL must match)
ON COLUMN alias          = (mask only) the column to mask = the UDF's FIRST arg (auto-bound)
USING COLUMNS (args…)    = positional UDF args: aliases + literals + get_tag_value(...)
UDF                      = the BOOLEAN filter / masking logic run per row/value at query time
```

**Argument binding (the #1 confusion):**
- **Row filter:** *no* argument is auto-supplied. A UDF with `n` params needs **all `n`** in `USING COLUMNS`.
- **Column mask:** `ON COLUMN` auto-binds the matched column as **arg 1**; `USING COLUMNS` supplies the **remaining `n-1`**.

**Two ways to attach a row filter (both are UC RLS):**
- **ABAC policy** (this skill's focus): `CREATE POLICY … ROW FILTER fn TO principal FOR TABLES MATCH COLUMNS has_tag(...) USING COLUMNS(...)` — **tag-driven**, principal-targeted, catalog/schema/table-scoped, owners bypass.
- **Classic table-managed**: `ALTER TABLE t SET ROW FILTER fn ON (col[, …])` (drop with `ALTER TABLE t DROP ROW FILTER`) — bound **directly to columns**, **no tags, no policy, no `TO`** (applies to all non-exempt principals; the UDF itself must check identity to exempt). Simpler, but not attribute-driven.

## Non-negotiable gotchas

| Rule | Consequence |
|---|---|
| **DBR requires the `has_tag()` MATCH COLUMNS form** — a bare column name in `USING COLUMNS` is rejected | tag the column, reference it via an alias |
| **At most ONE row filter per table** (`UC_ABAC_MULTIPLE_ROW_FILTERS`, SQLSTATE `42KDJ`) | both `CREATE POLICY` **succeed**; the query **errors at evaluation** — **table-wide**, independent of the `USING COLUMNS` columns. Encode all logic in ONE filter |
| **One distinct mask per column**; different masks on different columns coexist | conflicting distinct masks on the same column → error, not chained |
| **Every `MATCH COLUMNS` expression must match ≥1 column** | if one doesn't, the policy **silently does not apply** (no filtering) |
| **Columns never inherit tags** — table/schema/catalog tags do inherit (different keys accumulate; same key overrides) | tag every target/input column **directly** |
| **Fail-closed**: deleted UDF/governed tag, conflicting filters, unsupported compute/time-travel | the query is **blocked**, not silently unfiltered |
| **Owners / metastore admins bypass row filters** | to observe filtering, query **as the target principal** (e.g. the service principal in `TO`) |

## The row-filter UDF pattern (as deployed in the POC)

The policy calls a thin **wrapper** that injects the live claim and maps the object type, then the
real filter runs the logic:

```sql
-- wrapper: policy passes (id, '<TableTypeLiteral>', org); wrapper adds object-type mapping + the claim
RETURN abac_row_filter(entity_id,
                       abac.entity_type_to_object_type(object_type),
                       org_id,
                       abac.get_user_context());          -- get_user_context() =
-- from_json(current_oauth_custom_identity_claim(), 'STRUCT<tenant:int,user:string,org:string,
--                                                          mode:string,root:string,permissions:array<string>>')
-- NB: current_oauth_custom_identity_claim() HARD-ERRORS if the token carries no claim.
```

The deployed filter is **3 additive branches** (`DISABLE` · a `permissions` related-table branch ·
`root=object_type AND (RBAC_ABAC org-subtree 3a OR per-row assignment 3b)`). Full body, per-row
trace, and every edge case: **`references/poc-playbook.md`** and `docs/testing/jdbc-cases.md`.

## Common mistakes

- Binding a column directly in `USING COLUMNS` instead of via `has_tag()` → DBR rejects it.
- Expecting two row filters on one table to AND/OR → they don't; the query fails.
- Assuming a policy that "doesn't apply" is safe — a *broken* policy fail-closes (blocks); a
  *non-matching* one silently shows unfiltered data if you already have SELECT.
- Comparing STRING ids with `>=`/`<` for range/threshold filters → lexicographic; `try_cast(... AS
  BIGINT)` first.
- Testing as the table owner and concluding "no filtering happens" — owners bypass. Test as the `TO` principal.
- Case: object-type / mode / identity comparisons are exact-string and **case-sensitive**
  (`'disable' ≠ 'DISABLE'`, `'customer' ≠ 'Customer'`).
