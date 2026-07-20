-- =====================================================================
-- 08_policies_row_filter.sql   (requires ABAC enabled + tags from 07)
-- Customer create_policy_no_type pattern, one policy per table.
--
-- PRINCIPAL: policies bind TO the SERVICE PRINCIPAL (76d5804d-…) — the identity the
-- JDBC/curl session authenticates as. The *effective* ABAC user is the token's
-- claim.user (a dummy email seeded in ABAC_EntitySubjectAssignment), NOT the policy
-- target. The TO clause takes a comma-separated list if you add more principals.
--
-- Tables with a separate org key match id + org. item has no org key -> id only,
-- literal org '100'. Tables where id == org share one tagged column.
-- =====================================================================

CREATE OR REPLACE POLICY tpcds_1_delta_customer_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.customer
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_org') as org
USING COLUMNS (id, 'customer', org);

CREATE OR REPLACE POLICY tpcds_1_delta_customer_address_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.customer_address
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_org') as org
USING COLUMNS (id, 'customer_address', org);

CREATE OR REPLACE POLICY tpcds_1_delta_item_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.item
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id
USING COLUMNS (id, 'item', '100');

CREATE OR REPLACE POLICY tpcds_1_delta_store_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.store
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_org') as org
USING COLUMNS (id, 'store', org);

CREATE OR REPLACE POLICY tpcds_1_delta_store_sales_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.store_sales
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_org') as org
USING COLUMNS (id, 'store_sales', org);

CREATE OR REPLACE POLICY tpcds_1_delta_store_returns_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.store_returns
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_org') as org
USING COLUMNS (id, 'store_returns', org);

CREATE OR REPLACE POLICY tpcds_1_delta_catalog_sales_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.catalog_sales
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_org') as org
USING COLUMNS (id, 'catalog_sales', org);

CREATE OR REPLACE POLICY tpcds_1_delta_catalog_returns_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.catalog_returns
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_org') as org
USING COLUMNS (id, 'catalog_returns', org);

CREATE OR REPLACE POLICY tpcds_1_delta_web_sales_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.web_sales
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_org') as org
USING COLUMNS (id, 'web_sales', org);

CREATE OR REPLACE POLICY tpcds_1_delta_web_returns_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.web_returns
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_org') as org
USING COLUMNS (id, 'web_returns', org);

CREATE OR REPLACE POLICY tpcds_1_delta_web_site_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.web_site
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_org') as org
USING COLUMNS (id, 'web_site', org);

CREATE OR REPLACE POLICY tpcds_1_delta_warehouse_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.warehouse
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_org') as org
USING COLUMNS (id, 'warehouse', org);

-- List what got created
SHOW POLICIES ON SCHEMA abac_tpcds.tpcds_1_delta;
