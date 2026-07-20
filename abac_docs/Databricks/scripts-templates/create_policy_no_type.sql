CREATE OR REPLACE POLICY `@DBNAME_@TABLE_abac_policy`
ON TABLE `@DBNAME`.`@TABLE`
ROW FILTER `@DBNAME`.`abac_row_filter_wrapper`
TO @SERVICE_PRINCIPAL
FOR TABLES
MATCH COLUMNS
	has_tag('abac_column_id') as id,
	has_tag('abac_column_org') as org
USING COLUMNS (id, '@TYPE', org)
