# Databricks Unity Catalog ABAC: Row Filters and Column Masks

> Verified against the current Databricks on AWS documentation on **15 July 2026**. This note covers ABAC row-filter and column-mask policies, not ABAC GRANT policies.

## 1. Syntax

```sql
CREATE [ OR REPLACE ] POLICY policy_name
ON { CATALOG catalog_name | SCHEMA schema_name | TABLE table_name }
[ COMMENT description ]
{ row_filter_body | column_mask_body }

row_filter_body
  ROW FILTER function_name
  TO principal [, ...]
  [ EXCEPT principal [, ...] ]
  FOR TABLES
  [ WHEN condition ]
  [ MATCH COLUMNS condition [ [ AS ] alias ] [, ...] ]
  [ USING COLUMNS ( function_arg [, ...] ) ]

column_mask_body
  COLUMN MASK function_name
  TO principal [, ...]
  [ EXCEPT principal [, ...] ]
  FOR TABLES
  [ WHEN condition ]
  [ MATCH COLUMNS condition [ [ AS ] alias ] [, ...] ]
  ON COLUMN alias
  [ USING COLUMNS ( function_arg [, ...] ) ]
```

Source: [CREATE POLICY](https://docs.databricks.com/aws/en/sql/language-manual/sql-ref-syntax-ddl-create-policy)

## 2. Meaning of each term

- **`policy_name`**: Name of the policy. It must be unique among policies attached to the same securable object.
- **`ON CATALOG | SCHEMA | TABLE`**: Where the policy is attached and the boundary of its scope.
- **`ROW FILTER function_name`**: UDF that runs for each row and must return a Boolean. Rows returning `FALSE` are excluded.
- **`COLUMN MASK function_name`**: UDF that transforms a matched column value. Its result must be castable to the target column type.
- **`TO principal`**: Users, groups, or service principals subject to the policy.
- **`EXCEPT principal`**: Principals exempt from this policy.
- **`FOR TABLES`**: The target securable type. For row-filter and column-mask ABAC policies, only tables are supported; this includes regular tables, streaming tables, and materialized views.
- **`WHEN`**: Optional table-level tag condition. If omitted, it defaults to `TRUE`, so all tables in scope are considered.
- **`MATCH COLUMNS`**: Up to three column-tag expressions that find target or supporting columns. Every comma-separated expression must match at least one column for the policy to apply.
- **`alias`**: A name for columns found by one `MATCH COLUMNS` expression. It can be referenced by `ON COLUMN` or `USING COLUMNS`.
- **`ON COLUMN alias`**: Column-mask only. Identifies the matched column or columns to mask.
- **`USING COLUMNS`**: Positional arguments passed to the UDF.

Sources: [Create and manage policies](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/policies), [ABAC core concepts](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/core-concepts)

## 3. UDF argument binding

### Row filter

No argument is supplied automatically. If the row-filter UDF has `n` required parameters, `USING COLUMNS` must supply all `n` arguments in order.

```sql
CREATE FUNCTION filter_region(region STRING, allowed STRING)
RETURNS BOOLEAN
RETURN region = allowed;

-- Effective call: filter_region(region_col, 'EMEA')
MATCH COLUMNS has_tag('region') AS region_col
USING COLUMNS (region_col, 'EMEA')
```

A zero-argument row-filter UDF can omit `MATCH COLUMNS` and `USING COLUMNS`.

### Column mask

The column selected by `ON COLUMN` is automatically bound as the first UDF argument. `USING COLUMNS` supplies only the remaining arguments.

```sql
CREATE FUNCTION mask_last(value STRING, keep INT)
RETURNS STRING
RETURN CONCAT('***', RIGHT(value, keep));

-- Effective call for every matching column: mask_last(pii_col, 4)
MATCH COLUMNS has_tag('pii') AS pii_col
ON COLUMN pii_col
USING COLUMNS (4)
```

For a column-mask UDF with `n` required parameters, the policy normally needs `n - 1` arguments in `USING COLUMNS`. If fewer required arguments are supplied, the UDF invocation cannot be resolved and policy creation or validation fails; Databricks does not invent values. Extra arguments also produce an argument-count mismatch. Incompatible input/output casts can fail at query time.

Sources: [ABAC core concepts](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/core-concepts), [Create and manage policies](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/policies), [Databricks error conditions](https://docs.databricks.com/aws/en/error-messages/error-classes), [Policy evaluation and type casting](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/policy-evaluation)

## 4. Allowed expressions

### `WHEN`

The only user-facing tag functions are:

```sql
has_tag('tag_key')
has_tag_value('tag_key', 'tag_value')
```

They can be combined with `AND`, `OR`, `NOT`, and parentheses. They inspect the table's effective tags, including tags inherited from its schema or catalog.

### `MATCH COLUMNS`

It uses the same two functions and Boolean operators, but checks tags applied directly to each column. Columns do not inherit tags from tables, schemas, or catalogs.

### `USING COLUMNS`

This is an argument list, not a Boolean predicate. It can contain:

- An alias declared by `MATCH COLUMNS`
- A constant expression or literal, such as `'***'`, `4`, `TRUE`, or `NULL`
- `get_tag_value('tag_key')`, which returns the effective table tag value
- `get_column_tag_value(column_alias, 'tag_key')`, which returns a tag value applied directly to the matched column

The two getter functions can appear only in `USING COLUMNS`. Creating a policy that uses them requires Databricks Runtime 18.3 or above, though querying governed tables does not carry that creation-time requirement.

Sources: [CREATE POLICY conditions](https://docs.databricks.com/aws/en/sql/language-manual/sql-ref-syntax-ddl-create-policy), [Conditions and tag-introspection functions](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/core-concepts)

## 5. Policy scope and tag inheritance

### Policy defined on a catalog or schema

A policy is not copied to child objects. Its scope extends to descendant tables:

- `ON CATALOG`: evaluates all tables in that catalog, across all schemas
- `ON SCHEMA`: evaluates all tables in that schema
- `ON TABLE`: evaluates only that table

New descendant tables are covered automatically when the principal and tag conditions match. A lower-level table policy does not override a catalog/schema policy; all applicable policies are evaluated.

Sources: [ABAC core concepts: policy scope](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/core-concepts), [ABAC vs table-level controls](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/abac-vs-rls-cm)

### Tag applied on a catalog or schema

ABAC conditions use **governed tags**, not ungoverned tags. Governed tags are inherited through the catalog/schema hierarchy, but not by columns:

```text
catalog tag -> schema -> table
                         X-> column
```

A schema or table can override an inherited tag value. Columns must be tagged directly and cannot inherit a table tag.

Sources: [Governed tags](https://docs.databricks.com/aws/en/admin/governed-tags/), [ABAC core concepts: tag inheritance](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/core-concepts)

### `FOR TABLES` cannot be replaced

`FOR TABLES` is fixed for row-filter and column-mask policies. You cannot write `FOR SCHEMAS` or `FOR CATALOGS`. Use the `ON` clause to select catalog/schema/table scope; `FOR TABLES` says that descendant table-like objects are the policy targets.

Source: [ABAC core concepts: supported scopes and target type](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/core-concepts)

## 6. `MATCH COLUMNS` edge cases

### One of several expressions finds no column

All comma-separated `MATCH COLUMNS` expressions must match. If even one expression finds no column, the policy does not apply to that table. This policy then performs no row filtering or masking.

```sql
MATCH COLUMNS
  has_tag_value('pii', 'email') AS email_col,
  has_tag('consent') AS consent_col
```

If `email_col` matches but `consent_col` does not, the policy does not apply.

Sources: [ABAC core concepts: all expressions must match](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/core-concepts), [Policy evaluation](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/policy-evaluation)

### One target expression finds multiple columns

For a column mask, the single alias after `ON COLUMN` may represent multiple physical columns. The masking UDF is applied independently to each matching column value.

```sql
MATCH COLUMNS has_tag('pii') AS pii_col
ON COLUMN pii_col
```

If `email`, `phone`, and `ssn` all have the `pii` tag, all three are mask targets.

Sources: [Create and manage policies: matching columns are masked](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/policies), [Performance: UDF runs for every matching column value](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/performance)

### A UDF-input alias finds multiple columns

An alias passed through `USING COLUMNS` must provide a determinable scalar input for each UDF parameter. If an input alias matches multiple columns, Databricks cannot choose one and the query fails; it does not select the first column.

Use distinct tag values and aliases when multiple source columns are intentional:

```sql
MATCH COLUMNS
  has_tag_value('geo_region', 'billing') AS billing_region,
  has_tag_value('geo_region', 'shipping') AS shipping_region
USING COLUMNS (billing_region, shipping_region)
```

Source: [Policy evaluation: ambiguous matched aliases](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/policy-evaluation)

## 7. Multiple policies matching the same table

Multiple policies may be in scope, including policies attached at catalog, schema, and table levels. Runtime resolution follows these rules:

- Only one **distinct row filter** may resolve for a given user and table.
- Only one **distinct column mask per column** may resolve for a given user.
- If distinct filters or masks conflict, Databricks blocks access and returns an error; it does not combine row filters with `AND` or chain masks.
- Multiple policies are allowed when they resolve to the same UDF with the same arguments.
- Different masks can protect different columns without conflict.
- A row-filter policy and column-mask policies can apply together, but a single `CREATE POLICY` statement can define only one effect: row filter **or** column mask.
- A manually assigned table-level filter/mask can conflict with an ABAC-derived filter/mask on the same target.
- A masked column cannot be used as a `USING COLUMNS` input to another policy.

Sources: [Policy evaluation: multiple filters and masks](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/policy-evaluation), [ABAC requirements and limitations](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/requirements), [ABAC vs table-level controls](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/abac-vs-rls-cm)

## 8. When a policy does not apply

A particular policy does not apply when any of these is true:

1. The table is outside the `ON` scope.
2. The querying identity is not included by `TO`.
3. The querying identity is included by `EXCEPT`.
4. The `WHEN` expression evaluates to `FALSE`.
5. Any comma-separated `MATCH COLUMNS` expression matches no column.

When a policy does not apply, that policy performs no filtering or masking. The user sees full, unmodified data only if they already have the required Unity Catalog privileges and no other applicable policy/filter/mask restricts the result. ABAC row-filter and column-mask policies do not grant table access.

Sources: [Policy evaluation flow](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/policy-evaluation), [Create and manage policies](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/policies), [ABAC does not grant access](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/abac-vs-rls-cm)

## 9. Non-match versus fail-closed errors

A normal condition non-match means the policy does not apply. A broken or unsafe policy state can instead block the query under Databricks' fail-closed model. Examples include:

- Unsupported compute/runtime for an ABAC-secured table
- Conflicting distinct row filters or column masks
- A referenced governed tag definition is deleted
- The policy's UDF is deleted
- Unsupported operations such as time travel or cloning by a non-exempt principal
- Certain schema-evolution cases where a previously protected tagged column/tag dependency is removed and not restored

Source: [Policy evaluation: fail-closed design](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/policy-evaluation)

## 10. Tag and policy limits worth remembering

- A table or column can have multiple tags, but the same tag key cannot be assigned twice to the same object.
- Maximum 50 tag assignments per securable object.
- Maximum 1,000 total column-tag assignments across all columns of one table.
- Maximum 3 expressions in one `MATCH COLUMNS` clause.
- Maximum 100 policies per catalog or schema and 50 per table.
- Maximum 20 principals per policy; the documented limit applies to the `TO` and `EXCEPT` clauses.

Sources: [Tag constraints](https://docs.databricks.com/aws/en/database-objects/tags), [SET TAG](https://docs.databricks.com/aws/en/sql/language-manual/sql-ref-syntax-ddl-set-tag), [ABAC requirements and quotas](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/requirements)

## 11. Compact mental model

```text
ON ...           = Where should Databricks search for candidate tables?
TO / EXCEPT      = For which querying identities?
FOR TABLES       = Which descendant object type is governed?
WHEN             = Which tables match by effective table tags?
MATCH COLUMNS    = Which direct column tags identify targets/inputs?
ON COLUMN        = Which matched alias is masked? First mask-UDF argument.
USING COLUMNS    = What remaining positional arguments go to the UDF?
UDF              = What filtering or masking logic runs at query time?
```

## 12. Descendant tags versus descendant policies

### Governed tags

Different tag keys accumulate through inheritance during ABAC evaluation.

```text
Catalog: A = a
Schema:  B = b
```

The schema, and tables beneath it, effectively have both:

```text
A = a   # inherited from catalog
B = b   # directly assigned to schema
```

An override occurs only when a descendant defines the **same tag key**:

```text
Catalog: A = a
Schema:  A = x
```

The schema's effective value is `A = x`; the inherited `A = a` is overridden. Columns are the exception: they do not inherit tags from catalogs, schemas, or tables and must be tagged directly.

Sources: [ABAC core concepts: tag inheritance and overrides](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/core-concepts), [Implicit tag inheritance in ABAC](https://docs.databricks.com/aws/en/database-objects/tags)

### ABAC policies

Policies do not use descendant override semantics. A policy remains attached to the catalog, schema, or table specified by `ON`, while its scope covers eligible descendant tables.

```text
Catalog policy  -> candidate for tables in the catalog
Schema policy   -> candidate for tables in the schema
Table policy    -> candidate for that table only
```

For a queried table, Databricks evaluates all applicable policies from these scopes. A schema- or table-level policy does not replace a catalog-level policy.

Applicable policies must resolve compatibly:

- A row filter and column masks can apply together.
- Different mask policies can apply to different columns.
- Multiple policies resolving to the same UDF with the same arguments are compatible.
- Two distinct row filters for the same user and table cause an error; they are not combined with `AND`.
- Two distinct masks for the same user and column cause an error; a lower-level mask does not override a higher-level mask.

Sources: [ABAC policy scope](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/abac-vs-rls-cm), [Policy evaluation and conflicts](https://docs.databricks.com/aws/en/data-governance/unity-catalog/abac/policy-evaluation)

```text
Tags:
  Different keys accumulate.
  The same key at a lower level overrides the inherited value.

Policies:
  Applicable policies accumulate by scope.
  A lower-level policy does not override a higher-level policy.
  Incompatible results cause the query to fail.
```
