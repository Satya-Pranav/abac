-- =====================================================================
-- 07_tags.sql   (RUN BEFORE 08 — policies bind to columns via these tags)
-- =====================================================================
-- Governed tags: the tag KEYS below may need to exist as GOVERNED tags before
-- has_tag() can match them inside a policy. Create the keys once via
--   Settings > Catalog > Governed tags   (or the REST API / SDK, as the customer's
--   GovernedTagMigration does). Keys used here:
--     abac_column_id, abac_column_org         (abac_column_type, abac_column_tenant
--     are part of the customer set but unused by TPC-DS per-table policies).
-- The ALTER statements below assign the tag VALUES to columns.
-- id and org are tagged on the SAME column where the table has no separate org key.
-- =====================================================================

-- customer : id, org (distinct columns)
ALTER TABLE abac_tpcds.tpcds_1_delta.customer ALTER COLUMN c_customer_sk     SET TAGS ('abac_column_id'  = 'true');
ALTER TABLE abac_tpcds.tpcds_1_delta.customer ALTER COLUMN c_current_addr_sk SET TAGS ('abac_column_org' = 'true');

-- customer_address : id and org on the same column
ALTER TABLE abac_tpcds.tpcds_1_delta.customer_address ALTER COLUMN ca_address_sk SET TAGS ('abac_column_id' = 'true', 'abac_column_org' = 'true');

-- item : id only (org is a literal '100' in the policy)
ALTER TABLE abac_tpcds.tpcds_1_delta.item ALTER COLUMN i_item_sk SET TAGS ('abac_column_id' = 'true');

-- store : id and org on the same column
ALTER TABLE abac_tpcds.tpcds_1_delta.store ALTER COLUMN s_store_sk SET TAGS ('abac_column_id' = 'true', 'abac_column_org' = 'true');

-- store_sales : id, org
ALTER TABLE abac_tpcds.tpcds_1_delta.store_sales ALTER COLUMN ss_customer_sk SET TAGS ('abac_column_id'  = 'true');
ALTER TABLE abac_tpcds.tpcds_1_delta.store_sales ALTER COLUMN ss_store_sk    SET TAGS ('abac_column_org' = 'true');

-- store_returns : id, org
ALTER TABLE abac_tpcds.tpcds_1_delta.store_returns ALTER COLUMN sr_customer_sk SET TAGS ('abac_column_id'  = 'true');
ALTER TABLE abac_tpcds.tpcds_1_delta.store_returns ALTER COLUMN sr_store_sk    SET TAGS ('abac_column_org' = 'true');

-- catalog_sales : id, org
ALTER TABLE abac_tpcds.tpcds_1_delta.catalog_sales ALTER COLUMN cs_bill_customer_sk SET TAGS ('abac_column_id'  = 'true');
ALTER TABLE abac_tpcds.tpcds_1_delta.catalog_sales ALTER COLUMN cs_bill_addr_sk     SET TAGS ('abac_column_org' = 'true');

-- catalog_returns : id, org
ALTER TABLE abac_tpcds.tpcds_1_delta.catalog_returns ALTER COLUMN cr_returning_customer_sk SET TAGS ('abac_column_id'  = 'true');
ALTER TABLE abac_tpcds.tpcds_1_delta.catalog_returns ALTER COLUMN cr_returning_addr_sk     SET TAGS ('abac_column_org' = 'true');

-- web_sales : id, org
ALTER TABLE abac_tpcds.tpcds_1_delta.web_sales ALTER COLUMN ws_bill_customer_sk SET TAGS ('abac_column_id'  = 'true');
ALTER TABLE abac_tpcds.tpcds_1_delta.web_sales ALTER COLUMN ws_web_site_sk      SET TAGS ('abac_column_org' = 'true');

-- web_returns : id, org
ALTER TABLE abac_tpcds.tpcds_1_delta.web_returns ALTER COLUMN wr_returning_customer_sk SET TAGS ('abac_column_id'  = 'true');
ALTER TABLE abac_tpcds.tpcds_1_delta.web_returns ALTER COLUMN wr_returning_addr_sk     SET TAGS ('abac_column_org' = 'true');

-- web_site : id and org on the same column
ALTER TABLE abac_tpcds.tpcds_1_delta.web_site ALTER COLUMN web_site_sk SET TAGS ('abac_column_id' = 'true', 'abac_column_org' = 'true');

-- warehouse : id and org on the same column
ALTER TABLE abac_tpcds.tpcds_1_delta.warehouse ALTER COLUMN w_warehouse_sk SET TAGS ('abac_column_id' = 'true', 'abac_column_org' = 'true');
