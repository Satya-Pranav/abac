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
  OAuth `custom_claim` hot-swap, the deploy order, and the 61-case + 12-scenario JDBC test methodology
  (classic-RLS/live-UDF-swap cases, views, policy scope, tag binding, the UDF contract,
  cross-mechanism conflicts, the `EXCEPT` clause, malformed claims, Databricks-auth scenarios — secret
  invariance, second-principal targeting, token expiry — and e6data-engine scenario placeholders).
  **Read this for "how do I author/deploy/test one of these end to end?"**
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
| **A governed tag KEY must be REGISTERED before any policy references it** (Settings → Catalog → Governed tags). Registering the key is separate from applying it to a column | an unregistered key fails **at `CREATE POLICY` time**, not at query time: `INVALID_PARAMETER_VALUE.UC_INVALID_POLICY_CONDITION` … `Unknown tag policy key \`<key>\`` |
| **A governed tag key constrains its ALLOWED VALUES** | setting a value outside the list is rejected at `ALTER … SET TAGS`: `Tag value <v> is not an allowed value for tag policy key <key>. Allowed values: [true]`. If every key permits one value, `has_tag_value()` can only discriminate by **key**, not by value |
| **Plain column tags ≠ governed tags** | `ALTER … SET TAGS ('anything'='x')` succeeds for *any* key and shows up in `system.information_schema.column_tags`. That says nothing about whether the key is usable in `has_tag()` — only a registered tag **policy** is. A tag can exist on the column and still fail the policy |
| **Columns never inherit tags** — table/schema/catalog tags do inherit (different keys accumulate; same key overrides) | tag every target/input column **directly** |
| **Row-filter UDF arity is STRICT — a `DEFAULT` does not let you omit the argument** | `USING COLUMNS` must supply exactly as many arguments as the UDF declares, even if the omitted param has a `DEFAULT`. `CREATE POLICY` is rejected: `INVALID_PARAMETER_VALUE` … "The policy definition requires N argument(s), but the referred function `<fn>` takes M argument(s)". (A column mask differs: `ON COLUMN` auto-binds arg 1.) |
| **Two columns sharing one tag make the `USING COLUMNS` alias ambiguous — Databricks REFUSES to bind, it does not pick the first column** | query errors `UC_ABAC_AMBIGUOUS_COLUMN_MATCH`. **Trap:** this shares SQLSTATE `42KDJ` with `UC_ABAC_MULTIPLE_ROW_FILTERS`, a *different* condition — match on the error **class**, never on the SQLSTATE alone |
| **A declared-type UDF param bound (via `USING COLUMNS`) to a column of a different type is silently COERCED, not rejected** | e.g. a `DATE` param bound to a `TIMESTAMP` column: Databricks coerces TIMESTAMP → DATE at bind time and the filter applies with no error. A planner replicating this must coerce the same way — a widening/narrowing difference would silently change which rows are visible |
| **`TO <principal> EXCEPT <principal>` is valid syntax and actually exempts** | the excepted principal is removed from the policy's subject set entirely — the row filter never runs against it, same as an unfiltered/owner read |
| **Fail-closed**: deleted UDF/governed tag, conflicting filters, unsupported compute/time-travel | the query is **blocked**, not silently unfiltered |
| **Owners / metastore admins bypass row filters** | to observe filtering, query **as the target principal** (e.g. the service principal in `TO`) |
| **A row-filter policy governs ONLY the principals in its `TO` set** (confirmed live 2026-07-23) | a **different** principal with `SELECT` (and compute access) on the same table sees it **UNFILTERED** — the policy is simply not applied to it, no claim required. Governance completeness requires **every** SELECT-capable principal to be in `TO` (or a group in it) |
| **An expired OAuth token fails CLOSED at AUTHENTICATION** (HTTP 403 at session open, confirmed live 2026-07-23) | rejected **before any query**, regardless of the `custom_claim` it carries — it cannot return data. (Relatedly: the auth **secret** is not a policy input; the same SP with a different secret sees identical results, also confirmed live 2026-07-23) |

**Two failure modes that look alike and are not** (confirmed live, 2026-07-22 and 2026-07-23):

| Situation | When it fails | Direction |
|---|---|---|
| Tag key **not registered** as a governed tag | at **`CREATE POLICY`** (DDL) | **fail-CLOSED** — the policy never exists |
| Tag key registered, but **matches no column** on the table | never — the policy is created and is **inert** | **fail-OPEN** — the table returns ALL rows (confirmed by POC cases SC3 and TG3, both `sql/17`/`sql/18`) |

Both present as "my policy isn't filtering." The first is loud and safe; the second is silent and
dangerous. A planner replicating this must not collapse them into one behaviour. This distinction
was found the hard way: a POC case meant to test the second condition used an *unregistered* key,
so it would have hit the first and proved nothing.

**The one-row-filter-per-table limit is not scoped to "table-level ABAC vs table-level ABAC" — it is
broader, confirmed on two more axes live 2026-07-23:**

| Situation | Confirms |
|---|---|
| A **schema-level (`ON SCHEMA`) + a table-level (`ON TABLE`) row filter on the SAME table** (POC case SC4, `sql/17`) | hits the identical `UC_ABAC_MULTIPLE_ROW_FILTERS` (SQLSTATE `42KDJ`) conflict two table-level policies hit — **not** "the more specific (table) policy wins". Scope granularity is not a precedence mechanism; design one filter per table regardless of the scope it's attached at |
| A **classic `ALTER TABLE ... SET ROW FILTER` + an ABAC `CREATE POLICY` row filter on the SAME table** (POC case XT1, `sql/20`) | also errors `UC_ABAC_MULTIPLE_ROW_FILTERS` — the one-row-filter-per-table limit spans **both attachment mechanisms**, not just ABAC-vs-ABAC |

Full per-case traces and observed values: `docs/testing/jdbc-cases.md`'s SC4 and XT1 sections.

**Principal-targeting and expired-token findings above are from three Databricks-auth-specific POC
scenarios** — SEC (secret invariance), MSP (second-principal / `TO`-set targeting), EXP (token
expiry) — each gated on env vars and `SKIP`-by-default; all three confirmed live 2026-07-23. Full
per-check traces, observed values, and setup: `docs/testing/jdbc-cases.md`'s SEC, MSP, and EXP
sections.

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
