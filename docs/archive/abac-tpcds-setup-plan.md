> ## ⚠️ ARCHIVED — superseded, historical only
> This is the **earlier draft plan**, written *before* the real customer code (`abac_docs/`) was
> available. Several assumptions in it were later corrected. **Do not follow this document.**
> - The authoritative source of the design is the root [`README.md`](../../README.md) (see its §8
>   "Key findings & corrections vs. the earlier plan").
> - The reusable Databricks ABAC semantics live in the skill:
>   [`.claude/skills/databricks-abac/`](../../.claude/skills/databricks-abac/SKILL.md).
> - Kept only for provenance / to show what changed and why.

# ABAC Setup Plan for Copied TPC-DS Delta Dataset

## Summary

We will copy the source Delta dataset from Azure Blob Storage into a new Databricks testing catalog/schema, then create an ABAC setup modeled after the customer-provided Databricks scripts.

Source dataset:

```text
abfss://e6data-dataset@e6dataengine.dfs.core.windows.net/standard-datasets/tpcds_1_delta/
```

Use these placeholders in the scripts below:

```text
@CATALOG            New test catalog
@SCHEMA             Copied TPC-DS schema/database
<service_principal>       Databricks principal/group/service principal used in GRANT and CREATE POLICY TO clauses
<service_principal_user>  Exact value returned by SELECT current_user() when connected as the service principal; used in ABAC seed data and get_test_user_context()
```

The initial ABAC rollout should focus on these TPC-DS tables:

```text
customer
customer_address
item
store
store_sales
store_returns
catalog_sales
catalog_returns
web_sales
web_returns
web_site
warehouse
```

Exclude these from the first ABAC pass:

```text
__unitystorage
store_sales_iceberg
web_sales_iceberg
time_dimweb_page
```

## Customer Script Layout vs TPC-DS Layout

The customer scripts use two locations:

1. A shared ABAC schema for helper UDFs:

```sql
`ABAC`.`get_user_context`
`ABAC`.`entity_type_to_object_type`
`ABAC`.`object_type_to_permission`
```

2. The target application schema, represented by `@DBNAME`, for dataset-specific UDFs, metadata tables, and policies:

```sql
`@DBNAME`.`abac_row_filter`
`@DBNAME`.`abac_row_filter_wrapper`
`@DBNAME`.`abac_should_mask_column`
`@DBNAME`.ABAC_EntitySubjectAssignment
`@DBNAME`.ABAC_Assignment
`@DBNAME`.ABAC_AssignmentPermission
`@DBNAME`.UserGroupMembers
`@DBNAME`.orgHierarchy
```

For our TPC-DS setup, use the equivalent structure:

```text
@CATALOG.abac
  get_user_context()
  get_test_user_context()
  entity_type_to_object_type()
  object_type_to_permission()

@CATALOG.@SCHEMA
  copied TPC-DS tables
  ABAC metadata tables
  abac_row_filter()
  abac_row_filter_wrapper()
  abac_row_filter_test_wrapper()
  abac_should_mask_column()
  row-filter policies
```

The two `*_test_*` functions are intentionally added for deterministic exploratory testing. They are not in the customer scripts.

## Customer UDF Mapping

| Customer object | TPC-DS equivalent |
| --- | --- |
| `ABAC.get_user_context()` | `@CATALOG.abac.get_user_context()` using OAuth custom claims |
| `ABAC.entity_type_to_object_type()` | `@CATALOG.abac.entity_type_to_object_type()` mapping TPC-DS table names to object types |
| `ABAC.object_type_to_permission()` | `@CATALOG.abac.object_type_to_permission()` mapping object types to permission prefixes |
| `@DBNAME.abac_row_filter()` | `@CATALOG.@SCHEMA.abac_row_filter()` using TPC-DS IDs and org-like keys |
| `@DBNAME.abac_row_filter_wrapper()` | `@CATALOG.@SCHEMA.abac_row_filter_wrapper()` for policies and explicit query tests |
| `@DBNAME.abac_should_mask_column()` | `@CATALOG.@SCHEMA.abac_should_mask_column()` for masking PII and sensitive measures |
| `CREATE POLICY ... MATCH COLUMNS has_tag(...)` | Databricks policy layer using tags on selected TPC-DS columns |

## Conceptual Policy Enforcement Flow

This section is a mental model only. Do not execute this section as the runbook.

The executable setup starts at **Implementation Runbook** below. All schema creation, service-principal setup, table copy, UDF creation, grants, tags, policies, and validations are kept in the implementation sections.

This is the mental model for how Databricks policy enforcement and the ABAC UDFs work together.

### Setup-Time Objects

These objects must exist before a protected query runs:

1. Shared helper schema:

```text
@CATALOG.abac
```

2. Shared helper UDFs:

```sql
`@CATALOG`.`abac`.`get_user_context`()
`@CATALOG`.`abac`.`get_test_user_context`()
`@CATALOG`.`abac`.`entity_type_to_object_type`(entity_type)
`@CATALOG`.`abac`.`object_type_to_permission`(object_type)
```

3. Dataset schema:

```text
@CATALOG.@SCHEMA
```

4. ABAC metadata tables:

```sql
`@CATALOG`.`@SCHEMA`.`ABAC_Assignment`
`@CATALOG`.`@SCHEMA`.`ABAC_AssignmentPermission`
`@CATALOG`.`@SCHEMA`.`ABAC_EntitySubjectAssignment`
`@CATALOG`.`@SCHEMA`.`UserGroupMembers`
`@CATALOG`.`@SCHEMA`.`orgHierarchy`
```

5. Dataset-specific UDFs:

```sql
`@CATALOG`.`@SCHEMA`.`abac_row_filter`(...)
`@CATALOG`.`@SCHEMA`.`abac_row_filter_wrapper`(...)
`@CATALOG`.`@SCHEMA`.`abac_row_filter_test_wrapper`(...)
`@CATALOG`.`@SCHEMA`.`abac_should_mask_column`(...)
```

6. Tags on protected table columns:

```sql
abac_column_id
abac_column_org
```

7. Row filter policy attached to a target table and principal:

```sql
CREATE OR REPLACE POLICY `@SCHEMA_customer_abac_policy`
ON TABLE `@CATALOG`.`@SCHEMA`.`customer`
ROW FILTER `@CATALOG`.`@SCHEMA`.`abac_row_filter_wrapper`
TO `<service_principal>`
FOR TABLES
MATCH COLUMNS
  has_tag('abac_column_id') as id,
  has_tag('abac_column_org') as org
USING COLUMNS (id, 'customer', org);
```

### Query-Time Flow

Assume the service principal runs this query:

```sql
SELECT *
FROM `@CATALOG`.`@SCHEMA`.`customer`;
```

Databricks enforces the policy in this order:

1. Databricks identifies the query principal.

```sql
SELECT current_user();
```

Expected identity:

```text
<service_principal>
```

Record the returned value as:

```text
<service_principal_user>
```

2. Databricks checks whether a policy applies to that principal and table.

```text
Table:     @CATALOG.@SCHEMA.customer
Policy principal: <service_principal>
Effective user:   <service_principal_user>
Policy:           @SCHEMA_customer_abac_policy
```

3. Databricks finds policy input columns by tags.

```text
id  = column tagged abac_column_id
org = column tagged abac_column_org
```

For `customer`:

```text
id  = c_customer_sk
org = c_current_addr_sk
type = fixed literal 'customer'
```

4. Databricks effectively applies the row filter UDF per row.

Conceptually, the query behaves like:

```sql
SELECT *
FROM `@CATALOG`.`@SCHEMA`.`customer`
WHERE `@CATALOG`.`@SCHEMA`.`abac_row_filter_wrapper`(
  CAST(c_customer_sk AS STRING),
  'customer',
  CAST(c_current_addr_sk AS STRING)
);
```

5. The wrapper normalizes the object type and gets the ABAC user context.

Production/OAuth wrapper:

```sql
CREATE OR REPLACE FUNCTION `@CATALOG`.`@SCHEMA`.`abac_row_filter_wrapper`(
  entity_id STRING,
  object_type STRING,
  org_id STRING
)
RETURNS BOOLEAN
RETURN `@CATALOG`.`@SCHEMA`.`abac_row_filter`(
  entity_id,
  `@CATALOG`.`abac`.`entity_type_to_object_type`(object_type),
  org_id,
  `@CATALOG`.`abac`.`get_user_context`()
);
```

For deterministic service-principal testing, use the test wrapper in explicit queries:

```sql
CREATE OR REPLACE FUNCTION `@CATALOG`.`@SCHEMA`.`abac_row_filter_test_wrapper`(
  entity_id STRING,
  object_type STRING,
  org_id STRING
)
RETURNS BOOLEAN
RETURN `@CATALOG`.`@SCHEMA`.`abac_row_filter`(
  entity_id,
  `@CATALOG`.`abac`.`entity_type_to_object_type`(object_type),
  org_id,
  `@CATALOG`.`abac`.`get_test_user_context`()
);
```

6. The object-type helper maps TPC-DS table names to ABAC object types.

Example:

```sql
SELECT `@CATALOG`.`abac`.`entity_type_to_object_type`('customer');
```

Expected:

```text
Customer
```

7. The context UDF decides who the ABAC user is.

Production/customer style:

```sql
CREATE OR REPLACE FUNCTION `@CATALOG`.`abac`.`get_user_context`()
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
```

Deterministic test style:

```sql
CREATE OR REPLACE FUNCTION `@CATALOG`.`abac`.`get_test_user_context`()
RETURNS STRUCT<
  tenant: INT,
  user: STRING,
  org: STRING,
  mode: STRING,
  root: STRING,
  permissions: ARRAY<STRING>
>
RETURN named_struct(
  'tenant', 1,
  'user', '<service_principal_user>',
  'org', '100',
  'mode', 'ABAC',
  'root', 'Customer',
  'permissions', array('customers.view', 'items.view', 'sales.basic.view')
);
```

Key requirement:

```text
ctx.user from the context UDF must match ABAC_EntitySubjectAssignment.subjectID
```

8. The row-filter UDF checks whether the ABAC context can see the row.

Simplified logic:

```sql
CREATE OR REPLACE FUNCTION `@CATALOG`.`@SCHEMA`.`abac_row_filter`(
  entity_id STRING,
  object_type STRING,
  org_id STRING,
  ctx STRUCT<tenant: INT, user: STRING, org: STRING, mode: STRING, root: STRING, permissions: ARRAY<STRING>>
)
RETURNS BOOLEAN
RETURN (
  ctx.mode = 'DISABLE'
  OR (
    ctx.root = object_type
    AND EXISTS (
      SELECT 1
      FROM `@CATALOG`.`@SCHEMA`.`ABAC_EntitySubjectAssignment` esa
      JOIN `@CATALOG`.`@SCHEMA`.`ABAC_Assignment` a
        ON esa.assignmentID = a.id
        AND a.isActive
        AND a.isDeleted = false
      LEFT JOIN `@CATALOG`.`@SCHEMA`.`UserGroupMembers` ugm
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
);
```

9. Metadata lookup decides true or false for each row.

For a customer row:

```text
entity_id  = c_customer_sk
objectType = Customer
ctx.user   = <service_principal_user> or OAuth claim user
```

The row is visible only if metadata has a matching assignment:

```sql
SELECT *
FROM `@CATALOG`.`@SCHEMA`.`ABAC_EntitySubjectAssignment`
WHERE entityID = CAST(<c_customer_sk> AS STRING)
  AND objectType = 'Customer'
  AND subjectType = 'USER_ID'
  AND subjectID = '<ctx.user>'
  AND isDeleted = false;
```

10. Databricks returns only rows where the policy UDF returns true.

```text
abac_row_filter_wrapper(...) = true   -> row visible
abac_row_filter_wrapper(...) = false  -> row hidden
```

### Masking Flow

Column masking is similar, but the function answers "should this value be masked?"

Example explicit query:

```sql
SELECT
  c_customer_sk,
  CASE
    WHEN `@CATALOG`.`@SCHEMA`.`abac_should_mask_column`(
      CAST(c_customer_sk AS STRING),
      'Customer',
      'customers.basic.view',
      `@CATALOG`.`abac`.`get_test_user_context`()
    )
    THEN 'MASKED'
    ELSE c_email_address
  END AS c_email_address
FROM `@CATALOG`.`@SCHEMA`.`customer`;
```

Masking UDF logic:

```text
If matching assignment + matching permission exists:
  return false  -- do not mask

If no matching permission exists:
  return true   -- mask
```

The permission check uses:

```sql
`@CATALOG`.`@SCHEMA`.`ABAC_AssignmentPermission`
```

For example:

```text
assignment_customer_1 grants customers.basic.view
```

### Minimal End-to-End Test Setup

For the simplest service-principal test:

```text
<service_principal> = principal name used in GRANT and CREATE POLICY TO
<service_principal_user> = exact SELECT current_user() result from the service-principal connector session
```

Seed:

```sql
INSERT INTO `@CATALOG`.`@SCHEMA`.`ABAC_EntitySubjectAssignment`
VALUES
  ('1', 'Customer', 'assignment_customer_1', 'USER_ID', 'spn-abac-test', false);
```

Test context:

```sql
RETURN named_struct(
  'tenant', 1,
  'user', 'spn-abac-test',
  'org', '100',
  'mode', 'ABAC',
  'root', 'Customer',
  'permissions', array('customers.view', 'customers.basic.view')
);
```

Explicit validation:

```sql
SELECT `@CATALOG`.`@SCHEMA`.`abac_row_filter_test_wrapper`(
  '1',
  'customer',
  '100'
) AS visible;
```

Expected:

```text
true
```

Then validate automatic policy enforcement by connecting as `spn-abac-test` through JDBC/Python/API and running:

```sql
SELECT COUNT(*)
FROM `@CATALOG`.`@SCHEMA`.`customer`;
```

The policy should automatically call `abac_row_filter_wrapper` for that table/principal.

## Implementation Runbook

Follow the sections below in order for actual setup and validation.

## Create Schemas

Create the test catalog and schemas:

```sql
CREATE CATALOG IF NOT EXISTS `@CATALOG`;

CREATE SCHEMA IF NOT EXISTS `@CATALOG`.`@SCHEMA`;

CREATE SCHEMA IF NOT EXISTS `@CATALOG`.`abac`;
```

The `abac` schema is the shared helper namespace. The copied TPC-DS tables and dataset-specific ABAC metadata stay in `@CATALOG.@SCHEMA`.

Validate:

```sql
SHOW CATALOGS LIKE '@CATALOG';

SHOW SCHEMAS IN `@CATALOG` LIKE '@SCHEMA';

SHOW SCHEMAS IN `@CATALOG` LIKE 'abac';

DESCRIBE SCHEMA `@CATALOG`.`@SCHEMA`;

DESCRIBE SCHEMA `@CATALOG`.`abac`;
```

Expected result:

```text
@CATALOG exists.
@SCHEMA exists under @CATALOG.
abac exists under @CATALOG.
DESCRIBE SCHEMA returns location/owner metadata for both schemas.
```

## Create or Select Test Principal

Use an admin user for setup, but use a non-admin user or service principal for policy validation. Admin users can create or alter policies and may not be a reliable proof of enforcement.

If a suitable service principal already exists, record its application/client ID or Databricks principal name and use it as `<service_principal>`.

If one does not exist, create one through the Databricks account/workspace identity flow used by your environment. The exact command depends on whether your workspace manages service principals through the Databricks account console, Azure Entra ID, SCIM, Terraform, or workspace admin APIs.

After it exists in Databricks, grant only the minimum privileges needed to query the test dataset:

```sql
GRANT USE CATALOG ON CATALOG `@CATALOG` TO `<service_principal>`;

GRANT USE SCHEMA ON SCHEMA `@CATALOG`.`@SCHEMA` TO `<service_principal>`;

GRANT USE SCHEMA ON SCHEMA `@CATALOG`.`abac` TO `<service_principal>`;

GRANT SELECT ON SCHEMA `@CATALOG`.`@SCHEMA` TO `<service_principal>`;

GRANT EXECUTE ON SCHEMA `@CATALOG`.`@SCHEMA` TO `<service_principal>`;

GRANT EXECUTE ON SCHEMA `@CATALOG`.`abac` TO `<service_principal>`;
```

Connect as the service principal through JDBC/Python/API and run `SELECT current_user();`. Use that exact returned value as `<service_principal_user>` in ABAC seed data and `get_test_user_context()`.

Validate privileges as admin:

```sql
SHOW GRANTS ON CATALOG `@CATALOG`;

SHOW GRANTS ON SCHEMA `@CATALOG`.`@SCHEMA`;

SHOW GRANTS ON SCHEMA `@CATALOG`.`abac`;
```

Validate as the test principal:

```sql
SELECT current_user();

SHOW TABLES IN `@CATALOG`.`@SCHEMA`;

SELECT COUNT(*)
FROM `@CATALOG`.`@SCHEMA`.`customer`;
```

Expected result:

```text
current_user() returns the value you will use as `<service_principal_user>`.
The test principal can see/query the test tables but does not have admin/manage privileges.
```

Service-principal validation is normally not done through the Databricks web UI, because a service principal is a machine identity rather than an interactive user. Use one of these paths to run the validation SQL as the service principal:

```text
Databricks SQL Connector for Python
Databricks JDBC driver
Databricks ODBC driver
Databricks SQL Statement Execution API
Databricks Jobs configured to run as the service principal
```

In that connector/API session, first validate the effective identity:

```sql
SELECT current_user();
```

Expected result:

```text
current_user() should show the service-principal identity or the Databricks principal name associated with it.
Use that exact value as `<service_principal_user>` in ABAC seed data and get_test_user_context(), unless OAuth custom claims return a different ctx.user value.
```

Important OAuth note:

```text
Using a service principal through JDBC/Python/API proves policy enforcement for that principal.
It does not automatically prove current_oauth_custom_identity_claim() is populated.
If current_oauth_custom_identity_claim() returns NULL for the service-principal session, continue using get_test_user_context() for deterministic ABAC UDF validation, or wire the upstream OAuth/custom-claim path separately.
```

## Copy Dataset

Copy/register the source Delta tables into the new test catalog/schema. Keep the original TPC-DS table names where possible.

Example pattern:

```sql
CREATE TABLE `@CATALOG`.`@SCHEMA`.`customer`
LOCATION 'abfss://e6data-dataset@e6dataengine.dfs.core.windows.net/standard-datasets/tpcds_1_delta/customer';

CREATE TABLE `@CATALOG`.`@SCHEMA`.`store_sales`
LOCATION 'abfss://e6data-dataset@e6dataengine.dfs.core.windows.net/standard-datasets/tpcds_1_delta/store_sales';
```

Repeat for the selected tables. If the goal is a physical copy rather than external registration, use the Databricks-supported clone/copy mechanism preferred by the environment.

Validate after registering/copying each table:

```sql
SHOW TABLES IN `@CATALOG`.`@SCHEMA`;

SHOW TABLES IN `@CATALOG`.`@SCHEMA` LIKE 'customer';

DESCRIBE DETAIL `@CATALOG`.`@SCHEMA`.`customer`;

DESCRIBE TABLE `@CATALOG`.`@SCHEMA`.`customer`;

SELECT COUNT(*) AS customer_count
FROM `@CATALOG`.`@SCHEMA`.`customer`;

SELECT *
FROM `@CATALOG`.`@SCHEMA`.`customer`
LIMIT 5;
```

Expected result:

```text
The copied/registered table appears in SHOW TABLES.
DESCRIBE DETAIL reports format = delta.
DESCRIBE TABLE lists TPC-DS columns such as c_customer_sk.
COUNT(*) returns a non-zero count.
LIMIT 5 returns sample rows.
```

For partitioned fact tables, also validate partition metadata:

```sql
DESCRIBE DETAIL `@CATALOG`.`@SCHEMA`.`store_sales`;

SHOW PARTITIONS `@CATALOG`.`@SCHEMA`.`store_sales`;
```

Expected result:

```text
store_sales is Delta.
SHOW PARTITIONS returns ss_sold_date_sk partitions if the table is registered with partition metadata.
```

## ABAC Metadata Tables

Create the ABAC metadata tables in the copied dataset schema:

```sql
CREATE TABLE IF NOT EXISTS `@CATALOG`.`@SCHEMA`.`ABAC_Assignment` (
  id STRING,
  isActive BOOLEAN,
  isDeleted BOOLEAN
);

CREATE TABLE IF NOT EXISTS `@CATALOG`.`@SCHEMA`.`ABAC_AssignmentPermission` (
  assignmentID STRING,
  name STRING,
  isDeleted BOOLEAN
);

CREATE TABLE IF NOT EXISTS `@CATALOG`.`@SCHEMA`.`ABAC_EntitySubjectAssignment` (
  entityID STRING,
  objectType STRING,
  assignmentID STRING,
  subjectType STRING,
  subjectID STRING,
  isDeleted BOOLEAN
);

CREATE TABLE IF NOT EXISTS `@CATALOG`.`@SCHEMA`.`UserGroupMembers` (
  groupID STRING,
  memberID STRING,
  isDeleted BOOLEAN
);

CREATE TABLE IF NOT EXISTS `@CATALOG`.`@SCHEMA`.`orgHierarchy` (
  orgID STRING,
  parentOrgID STRING,
  isDeleted BOOLEAN
);
```

Seed a minimal test setup:

```sql
INSERT INTO `@CATALOG`.`@SCHEMA`.`ABAC_Assignment` VALUES
  ('assignment_customer_1', true, false),
  ('assignment_item_1', true, false),
  ('assignment_sales_1', true, false);

INSERT INTO `@CATALOG`.`@SCHEMA`.`ABAC_AssignmentPermission` VALUES
  ('assignment_customer_1', 'customers.view', false),
  ('assignment_customer_1', 'customers.basic.view', false),
  ('assignment_item_1', 'items.view', false),
  ('assignment_sales_1', 'sales.basic.view', false);

INSERT INTO `@CATALOG`.`@SCHEMA`.`ABAC_EntitySubjectAssignment` VALUES
  ('1', 'Customer', 'assignment_customer_1', 'USER_ID', '<service_principal_user>', false),
  ('1', 'Item', 'assignment_item_1', 'USER_ID', '<service_principal_user>', false),
  ('1', 'StoreSale', 'assignment_sales_1', 'USER_ID', '<service_principal_user>', false);

INSERT INTO `@CATALOG`.`@SCHEMA`.`UserGroupMembers` VALUES
  ('test_group_1', '<service_principal_user>', false);

INSERT INTO `@CATALOG`.`@SCHEMA`.`orgHierarchy` VALUES
  ('100', '100', false),
  ('101', '100', false),
  ('102', '100', false);
```

Adjust entity IDs after checking actual sample keys from copied TPC-DS tables.

Validate metadata table creation:

```sql
SHOW TABLES IN `@CATALOG`.`@SCHEMA` LIKE 'ABAC_Assignment';

SHOW TABLES IN `@CATALOG`.`@SCHEMA` LIKE 'ABAC_AssignmentPermission';

SHOW TABLES IN `@CATALOG`.`@SCHEMA` LIKE 'ABAC_EntitySubjectAssignment';

SHOW TABLES IN `@CATALOG`.`@SCHEMA` LIKE 'UserGroupMembers';

SHOW TABLES IN `@CATALOG`.`@SCHEMA` LIKE 'orgHierarchy';

DESCRIBE TABLE `@CATALOG`.`@SCHEMA`.`ABAC_Assignment`;

DESCRIBE TABLE `@CATALOG`.`@SCHEMA`.`ABAC_EntitySubjectAssignment`;
```

Validate seed rows:

```sql
SELECT COUNT(*) AS assignments
FROM `@CATALOG`.`@SCHEMA`.`ABAC_Assignment`;

SELECT COUNT(*) AS assignment_permissions
FROM `@CATALOG`.`@SCHEMA`.`ABAC_AssignmentPermission`;

SELECT COUNT(*) AS entity_subject_assignments
FROM `@CATALOG`.`@SCHEMA`.`ABAC_EntitySubjectAssignment`;

SELECT *
FROM `@CATALOG`.`@SCHEMA`.`ABAC_EntitySubjectAssignment`
ORDER BY objectType, entityID
LIMIT 20;
```

Expected result:

```text
All five ABAC metadata tables exist.
Seed counts are non-zero.
Entity assignments include Customer, Item, and StoreSale entries for <service_principal_user>.
```

## Shared Helper UDFs

Create these in `@CATALOG.abac`.

### User Context From OAuth Claim

```sql
CREATE OR REPLACE FUNCTION `@CATALOG`.`abac`.`get_user_context`()
RETURNS STRUCT<
  tenant: INT,
  user: STRING,
  org: STRING,
  mode: STRING,
  root: STRING,
  permissions: ARRAY<STRING>
>
COMMENT 'Return ABAC context from OAuth custom identity claim'
RETURN from_json(
  current_oauth_custom_identity_claim(),
  'STRUCT<tenant: int, user: string, org: string, mode: string, root: string, permissions: array<string>>'
);
```

### Deterministic Test Context

Use this while OAuth claims are not wired or while validating UDF behavior directly:

```sql
CREATE OR REPLACE FUNCTION `@CATALOG`.`abac`.`get_test_user_context`()
RETURNS STRUCT<
  tenant: INT,
  user: STRING,
  org: STRING,
  mode: STRING,
  root: STRING,
  permissions: ARRAY<STRING>
>
RETURN named_struct(
  'tenant', 1,
  'user', '<service_principal_user>',
  'org', '100',
  'mode', 'ABAC',
  'root', 'Customer',
  'permissions', array('customers.view', 'items.view', 'sales.basic.view')
);
```

### TPC-DS Entity Type to Object Type

```sql
CREATE OR REPLACE FUNCTION `@CATALOG`.`abac`.`entity_type_to_object_type`(entity_type STRING)
RETURNS STRING
COMMENT 'Convert TPC-DS table/type string to ABAC object type'
RETURN CASE lower(entity_type)
  WHEN 'customer' THEN 'Customer'
  WHEN 'customer_address' THEN 'CustomerAddress'
  WHEN 'item' THEN 'Item'
  WHEN 'store' THEN 'Store'
  WHEN 'store_sales' THEN 'StoreSale'
  WHEN 'store_returns' THEN 'StoreReturn'
  WHEN 'catalog_sales' THEN 'CatalogSale'
  WHEN 'catalog_returns' THEN 'CatalogReturn'
  WHEN 'web_sales' THEN 'WebSale'
  WHEN 'web_returns' THEN 'WebReturn'
  WHEN 'web_site' THEN 'WebSite'
  WHEN 'warehouse' THEN 'Warehouse'
  ELSE entity_type
END;
```

### Object Type to Permission Prefix

```sql
CREATE OR REPLACE FUNCTION `@CATALOG`.`abac`.`object_type_to_permission`(object_type STRING)
RETURNS STRING
COMMENT 'Convert TPC-DS ABAC object type to permission prefix'
RETURN CASE object_type
  WHEN 'Customer' THEN 'customers'
  WHEN 'CustomerAddress' THEN 'customer-addresses'
  WHEN 'Item' THEN 'items'
  WHEN 'Store' THEN 'stores'
  WHEN 'StoreSale' THEN 'sales'
  WHEN 'StoreReturn' THEN 'returns'
  WHEN 'CatalogSale' THEN 'sales'
  WHEN 'CatalogReturn' THEN 'returns'
  WHEN 'WebSale' THEN 'sales'
  WHEN 'WebReturn' THEN 'returns'
  WHEN 'WebSite' THEN 'web-sites'
  WHEN 'Warehouse' THEN 'warehouses'
  ELSE lower(object_type)
END;
```

Validate shared helper UDFs:

```sql
SHOW USER FUNCTIONS IN `@CATALOG`.`abac`;

SHOW USER FUNCTIONS IN `@CATALOG`.`abac` LIKE 'get_test_user_context';

DESCRIBE FUNCTION EXTENDED `@CATALOG`.`abac`.`entity_type_to_object_type`;

SELECT `@CATALOG`.`abac`.`entity_type_to_object_type`('customer') AS object_type;

SELECT `@CATALOG`.`abac`.`object_type_to_permission`('Customer') AS permission_prefix;

SELECT `@CATALOG`.`abac`.`get_test_user_context`() AS ctx;
```

Expected result:

```text
SHOW USER FUNCTIONS lists the helper UDFs.
customer maps to Customer.
Customer maps to customers.
get_test_user_context returns user = <service_principal_user>, org = 100, mode = ABAC.
```

## Dataset-Specific UDFs

Create these in `@CATALOG.@SCHEMA`.

### Column Mask Decision

```sql
CREATE OR REPLACE FUNCTION `@CATALOG`.`@SCHEMA`.`abac_should_mask_column`(
  entity_id STRING,
  object_type STRING,
  permission STRING,
  ctx STRUCT<tenant: INT, user: STRING, org: STRING, mode: STRING, root: STRING, permissions: ARRAY<STRING>>
)
RETURNS BOOLEAN
RETURN NOT EXISTS (
  SELECT 1
  FROM `@CATALOG`.`@SCHEMA`.`ABAC_EntitySubjectAssignment` esa
  JOIN `@CATALOG`.`@SCHEMA`.`ABAC_Assignment` a
    ON esa.assignmentID = a.id
    AND a.isActive
    AND a.isDeleted = false
  JOIN `@CATALOG`.`@SCHEMA`.`ABAC_AssignmentPermission` ap
    ON ap.assignmentID = a.id
    AND (
      ap.name = permission
      OR replace(ap.name, '.advanced.', '.basic.') = permission
    )
    AND ap.isDeleted = false
  LEFT JOIN `@CATALOG`.`@SCHEMA`.`UserGroupMembers` ugm
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
);
```

### Row Filter

```sql
CREATE OR REPLACE FUNCTION `@CATALOG`.`@SCHEMA`.`abac_row_filter`(
  entity_id STRING,
  object_type STRING,
  org_id STRING,
  ctx STRUCT<tenant: INT, user: STRING, org: STRING, mode: STRING, root: STRING, permissions: ARRAY<STRING>>
)
RETURNS BOOLEAN
RETURN (
  ctx.mode = 'DISABLE'
  OR (
    ctx.root <> object_type
    AND array_contains(
      ctx.permissions,
      concat(`@CATALOG`.`abac`.`object_type_to_permission`(object_type), '.view')
    )
  )
  OR (
    ctx.root = object_type
    AND (
      (
        ctx.mode = 'RBAC_ABAC'
        AND org_id IN (
          SELECT orgID
          FROM `@CATALOG`.`@SCHEMA`.`orgHierarchy`
          WHERE parentOrgID = ctx.org
            AND isDeleted = false
        )
      )
      OR EXISTS (
        SELECT 1
        FROM `@CATALOG`.`@SCHEMA`.`ABAC_EntitySubjectAssignment` esa
        JOIN `@CATALOG`.`@SCHEMA`.`ABAC_Assignment` a
          ON esa.assignmentID = a.id
          AND a.isActive
          AND a.isDeleted = false
        LEFT JOIN `@CATALOG`.`@SCHEMA`.`UserGroupMembers` ugm
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

### Row Filter Wrapper

```sql
CREATE OR REPLACE FUNCTION `@CATALOG`.`@SCHEMA`.`abac_row_filter_wrapper`(
  entity_id STRING,
  object_type STRING,
  org_id STRING
)
RETURNS BOOLEAN
RETURN `@CATALOG`.`@SCHEMA`.`abac_row_filter`(
  entity_id,
  `@CATALOG`.`abac`.`entity_type_to_object_type`(object_type),
  org_id,
  `@CATALOG`.`abac`.`get_user_context`()
);
```

### Test Row Filter Wrapper

```sql
CREATE OR REPLACE FUNCTION `@CATALOG`.`@SCHEMA`.`abac_row_filter_test_wrapper`(
  entity_id STRING,
  object_type STRING,
  org_id STRING
)
RETURNS BOOLEAN
RETURN `@CATALOG`.`@SCHEMA`.`abac_row_filter`(
  entity_id,
  `@CATALOG`.`abac`.`entity_type_to_object_type`(object_type),
  org_id,
  `@CATALOG`.`abac`.`get_test_user_context`()
);
```

Validate dataset-specific UDFs:

```sql
SHOW USER FUNCTIONS IN `@CATALOG`.`@SCHEMA`;

SHOW USER FUNCTIONS IN `@CATALOG`.`@SCHEMA` LIKE 'abac_row_filter';

SHOW USER FUNCTIONS IN `@CATALOG`.`@SCHEMA` LIKE 'abac_row_filter_wrapper';

SHOW USER FUNCTIONS IN `@CATALOG`.`@SCHEMA` LIKE 'abac_should_mask_column';

DESCRIBE FUNCTION EXTENDED `@CATALOG`.`@SCHEMA`.`abac_row_filter`;

SELECT `@CATALOG`.`@SCHEMA`.`abac_row_filter_test_wrapper`(
  '1',
  'customer',
  '100'
) AS can_see_seed_customer;

SELECT `@CATALOG`.`@SCHEMA`.`abac_row_filter_test_wrapper`(
  '999999999',
  'customer',
  '100'
) AS can_see_unassigned_customer;
```

Expected result:

```text
SHOW USER FUNCTIONS lists the dataset-specific UDFs.
The seed customer check returns true.
The unassigned customer check returns false in ABAC mode.
```

## TPC-DS Column Mapping for ABAC

Use this first-pass mapping:

| Table | Entity ID | Object type literal | Org-like column |
| --- | --- | --- | --- |
| `customer` | `c_customer_sk` | `'customer'` | `c_current_addr_sk` |
| `customer_address` | `ca_address_sk` | `'customer_address'` | `ca_address_sk` |
| `item` | `i_item_sk` | `'item'` | static/test value |
| `store` | `s_store_sk` | `'store'` | `s_store_sk` |
| `store_sales` | `ss_customer_sk` | `'store_sales'` | `ss_store_sk` |
| `store_returns` | `sr_customer_sk` | `'store_returns'` | `sr_store_sk` |
| `catalog_sales` | `cs_bill_customer_sk` | `'catalog_sales'` | `cs_bill_addr_sk` |
| `catalog_returns` | `cr_returning_customer_sk` | `'catalog_returns'` | `cr_returning_addr_sk` |
| `web_sales` | `ws_bill_customer_sk` | `'web_sales'` | `ws_web_site_sk` |
| `web_returns` | `wr_returning_customer_sk` | `'web_returns'` | `wr_returning_addr_sk` |
| `web_site` | `web_site_sk` | `'web_site'` | `web_site_sk` |
| `warehouse` | `w_warehouse_sk` | `'warehouse'` | `w_warehouse_sk` |

For `item`, use a constant org value during early testing:

```sql
CAST('100' AS STRING)
```

## Explicit Validation Queries

Validate explicit UDF behavior before attaching policies.

### Customer Row Filtering

```sql
SELECT *
FROM `@CATALOG`.`@SCHEMA`.`customer`
WHERE `@CATALOG`.`@SCHEMA`.`abac_row_filter_test_wrapper`(
  CAST(c_customer_sk AS STRING),
  'customer',
  CAST(c_current_addr_sk AS STRING)
);
```

Validate with counts:

```sql
SELECT COUNT(*) AS visible_customer_count
FROM `@CATALOG`.`@SCHEMA`.`customer`
WHERE `@CATALOG`.`@SCHEMA`.`abac_row_filter_test_wrapper`(
  CAST(c_customer_sk AS STRING),
  'customer',
  CAST(c_current_addr_sk AS STRING)
);
```

Expected result:

```text
The count should be smaller than or equal to the total customer count.
With the provided seed data, only rows matching seeded entity IDs are expected unless permissions/root mode allow more.
```

### Item Row Filtering

```sql
SELECT *
FROM `@CATALOG`.`@SCHEMA`.`item`
WHERE `@CATALOG`.`@SCHEMA`.`abac_row_filter_test_wrapper`(
  CAST(i_item_sk AS STRING),
  'item',
  CAST('100' AS STRING)
);
```

Validate with counts:

```sql
SELECT COUNT(*) AS visible_item_count
FROM `@CATALOG`.`@SCHEMA`.`item`
WHERE `@CATALOG`.`@SCHEMA`.`abac_row_filter_test_wrapper`(
  CAST(i_item_sk AS STRING),
  'item',
  CAST('100' AS STRING)
);
```

### Store Sales Row Filtering

```sql
SELECT *
FROM `@CATALOG`.`@SCHEMA`.`store_sales`
WHERE `@CATALOG`.`@SCHEMA`.`abac_row_filter_test_wrapper`(
  CAST(ss_customer_sk AS STRING),
  'store_sales',
  CAST(ss_store_sk AS STRING)
);
```

Validate with counts:

```sql
SELECT COUNT(*) AS visible_store_sales_count
FROM `@CATALOG`.`@SCHEMA`.`store_sales`
WHERE `@CATALOG`.`@SCHEMA`.`abac_row_filter_test_wrapper`(
  CAST(ss_customer_sk AS STRING),
  'store_sales',
  CAST(ss_store_sk AS STRING)
);
```

### Customer PII Masking

```sql
SELECT
  c_customer_sk,
  CASE
    WHEN `@CATALOG`.`@SCHEMA`.`abac_should_mask_column`(
      CAST(c_customer_sk AS STRING),
      'Customer',
      'customers.basic.view',
      `@CATALOG`.`abac`.`get_test_user_context`()
    )
    THEN 'MASKED'
    ELSE c_email_address
  END AS c_email_address
FROM `@CATALOG`.`@SCHEMA`.`customer`;
```

Validate masked vs unmasked rows:

```sql
SELECT
  SUM(CASE WHEN masked_email = 'MASKED' THEN 1 ELSE 0 END) AS masked_rows,
  SUM(CASE WHEN masked_email <> 'MASKED' THEN 1 ELSE 0 END) AS unmasked_rows
FROM (
  SELECT
    CASE
      WHEN `@CATALOG`.`@SCHEMA`.`abac_should_mask_column`(
        CAST(c_customer_sk AS STRING),
        'Customer',
        'customers.basic.view',
        `@CATALOG`.`abac`.`get_test_user_context`()
      )
      THEN 'MASKED'
      ELSE c_email_address
    END AS masked_email
  FROM `@CATALOG`.`@SCHEMA`.`customer`
);
```

### Sales Amount Masking

```sql
SELECT
  ss_customer_sk,
  ss_item_sk,
  CASE
    WHEN `@CATALOG`.`@SCHEMA`.`abac_should_mask_column`(
      CAST(ss_customer_sk AS STRING),
      'StoreSale',
      'sales.basic.view',
      `@CATALOG`.`abac`.`get_test_user_context`()
    )
    THEN NULL
    ELSE ss_net_paid
  END AS ss_net_paid
FROM `@CATALOG`.`@SCHEMA`.`store_sales`;
```

Validate the mask decision directly:

```sql
SELECT
  `@CATALOG`.`@SCHEMA`.`abac_should_mask_column`(
    '1',
    'StoreSale',
    'sales.basic.view',
    `@CATALOG`.`abac`.`get_test_user_context`()
  ) AS should_mask_seed_sale,
  `@CATALOG`.`@SCHEMA`.`abac_should_mask_column`(
    '999999999',
    'StoreSale',
    'sales.basic.view',
    `@CATALOG`.`abac`.`get_test_user_context`()
  ) AS should_mask_unassigned_sale;
```

Expected result:

```text
should_mask_seed_sale should be false when the seed assignment grants sales.basic.view.
should_mask_unassigned_sale should be true.
```

## Tags and Policies

After explicit UDF validation, add Databricks tags to policy-relevant columns.

Example for `customer`:

```sql
ALTER TABLE `@CATALOG`.`@SCHEMA`.`customer`
ALTER COLUMN c_customer_sk SET TAGS ('abac_column_id' = 'true');

ALTER TABLE `@CATALOG`.`@SCHEMA`.`customer`
ALTER COLUMN c_current_addr_sk SET TAGS ('abac_column_org' = 'true');
```

For tables without a type column, use table-level policy templates with a fixed object type.

Customer policy:

```sql
CREATE OR REPLACE POLICY `@SCHEMA_customer_abac_policy`
ON TABLE `@CATALOG`.`@SCHEMA`.`customer`
ROW FILTER `@CATALOG`.`@SCHEMA`.`abac_row_filter_wrapper`
TO `<service_principal>`
FOR TABLES
MATCH COLUMNS
  has_tag('abac_column_id') as id,
  has_tag('abac_column_org') as org
USING COLUMNS (id, 'customer', org);
```

Validate customer tags and policy:

```sql
DESCRIBE EXTENDED `@CATALOG`.`@SCHEMA`.`customer` c_customer_sk;

DESCRIBE EXTENDED `@CATALOG`.`@SCHEMA`.`customer` c_current_addr_sk;

SHOW POLICIES ON TABLE `@CATALOG`.`@SCHEMA`.`customer`;
```

Expected result:

```text
c_customer_sk has abac_column_id.
c_current_addr_sk has abac_column_org.
SHOW POLICIES lists @SCHEMA_customer_abac_policy.
```

Store sales policy:

```sql
ALTER TABLE `@CATALOG`.`@SCHEMA`.`store_sales`
ALTER COLUMN ss_customer_sk SET TAGS ('abac_column_id' = 'true');

ALTER TABLE `@CATALOG`.`@SCHEMA`.`store_sales`
ALTER COLUMN ss_store_sk SET TAGS ('abac_column_org' = 'true');

CREATE OR REPLACE POLICY `@SCHEMA_store_sales_abac_policy`
ON TABLE `@CATALOG`.`@SCHEMA`.`store_sales`
ROW FILTER `@CATALOG`.`@SCHEMA`.`abac_row_filter_wrapper`
TO `<service_principal>`
FOR TABLES
MATCH COLUMNS
  has_tag('abac_column_id') as id,
  has_tag('abac_column_org') as org
USING COLUMNS (id, 'store_sales', org);
```

Validate store sales tags and policy:

```sql
DESCRIBE EXTENDED `@CATALOG`.`@SCHEMA`.`store_sales` ss_customer_sk;

DESCRIBE EXTENDED `@CATALOG`.`@SCHEMA`.`store_sales` ss_store_sk;

SHOW POLICIES ON TABLE `@CATALOG`.`@SCHEMA`.`store_sales`;
```

Item policy with a static org-like value:

```sql
ALTER TABLE `@CATALOG`.`@SCHEMA`.`item`
ALTER COLUMN i_item_sk SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE POLICY `@SCHEMA_item_abac_policy`
ON TABLE `@CATALOG`.`@SCHEMA`.`item`
ROW FILTER `@CATALOG`.`@SCHEMA`.`abac_row_filter_wrapper`
TO `<service_principal>`
FOR TABLES
MATCH COLUMNS
  has_tag('abac_column_id') as id
USING COLUMNS (id, 'item', '100');
```

Validate item tag and policy:

```sql
DESCRIBE EXTENDED `@CATALOG`.`@SCHEMA`.`item` i_item_sk;

SHOW POLICIES ON TABLE `@CATALOG`.`@SCHEMA`.`item`;
```

Repeat this pattern for the remaining selected tables.

## Step-by-Step Execution Order

1. Create the new test catalog and schemas.
   Validate with `SHOW CATALOGS`, `SHOW SCHEMAS`, and `DESCRIBE SCHEMA`.
2. Create or select a non-admin test user/service principal.
   Use its Databricks policy/grant name as `<service_principal>`.
   Validate with `current_user()` when running through JDBC/Python/API, and use that exact returned identity as `<service_principal_user>`.
3. Grant minimal catalog/schema/table/function privileges to `<service_principal>`.
   Validate with `SHOW GRANTS` and a simple `SELECT COUNT(*)` as the test principal.
4. Register or copy selected TPC-DS Delta tables into `@CATALOG.@SCHEMA`.
   Validate each table with `SHOW TABLES`, `DESCRIBE DETAIL`, `DESCRIBE TABLE`, `COUNT(*)`, and `LIMIT 5`.
5. Create ABAC metadata tables in `@CATALOG.@SCHEMA`.
   Validate with `SHOW TABLES LIKE ...` and `DESCRIBE TABLE`.
6. Seed minimal ABAC assignments and permissions using `<service_principal_user>`.
   Validate with `COUNT(*)` and sample `SELECT` queries from the metadata tables.
7. Create shared helper UDFs in `@CATALOG.abac`.
   Validate with `SHOW USER FUNCTIONS`, `DESCRIBE FUNCTION EXTENDED`, and direct `SELECT` calls.
8. Create dataset-specific UDFs in `@CATALOG.@SCHEMA`.
   Validate with `SHOW USER FUNCTIONS`, `DESCRIBE FUNCTION EXTENDED`, and direct wrapper calls.
9. Validate explicit row-filter queries using `abac_row_filter_test_wrapper`.
   Compare filtered counts against total table counts.
10. Validate explicit masking queries using `abac_should_mask_column`.
   Check both granted and unassigned entity IDs.
11. Replace test wrapper usage with OAuth-backed `abac_row_filter_wrapper` once user claims are available.
   Validate `get_user_context()` directly for the target principal.
12. Add column tags for selected tables.
    Validate with `DESCRIBE EXTENDED table column`.
13. Create row-filter policies for `<service_principal>`.
    Validate with `SHOW POLICIES ON TABLE`.
14. Run validation queries through the target principal/service principal.
    Compare policy-filtered results to explicit-wrapper results for the same user context.

## Test Scenarios

Validate these scenarios:

```text
DISABLE mode returns all rows.
ABAC mode returns only explicitly assigned rows.
RBAC_ABAC mode returns rows under the configured org hierarchy.
Missing customers.basic.view masks customer PII fields.
Missing sales.basic.view masks sales amount fields.
Joins across store_sales, customer, and item continue to work with filters applied.
Policy results match explicit WHERE abac_row_filter_test_wrapper(...) results for the same context.
```

## Open Decisions

These should be finalized before production-like testing:

```text
Actual test catalog name.
Actual copied schema name.
Whether tables are external registrations or physical copies.
Exact principal/group/service principal for policy application.
Whether OAuth custom claims are already available for test users.
Which TPC-DS columns should be treated as sensitive and masked.
Whether org_id should be derived from address/store/site/warehouse keys or a separate synthetic mapping.
```

## Assumptions

The copied dataset preserves TPC-DS table and column names.

ABAC metadata tables are test-only and live beside the copied dataset.

The shared helper schema should be lowercase `abac` unless your Databricks convention requires uppercase `ABAC`.

Explicit UDF validation should happen before Databricks policy attachment.

The `CREATE POLICY` syntax is Databricks-specific and should be validated in the target workspace before broad rollout.
