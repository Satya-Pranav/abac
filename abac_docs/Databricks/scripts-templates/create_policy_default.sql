CREATE OR REPLACE POLICY `@DBNAME_default_abac_policy_with_type_column`
ON SCHEMA `@DBNAME`
ROW FILTER `@DBNAME`.`abac_row_filter_wrapper`
TO @SERVICE_PRINCIPAL
FOR TABLES
MATCH COLUMNS
	has_tag('abac_column_id') as id,
	has_tag('abac_column_type') as type,
	has_tag('abac_column_org') as org
USING COLUMNS (id, type, org)
