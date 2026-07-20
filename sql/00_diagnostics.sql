-- =====================================================================
-- 00_diagnostics.sql   (READ ONLY — creates/changes nothing)
-- Run this first. Every statement is a probe; read its output/error.
-- =====================================================================

-- (a) Who am I / where am I?
SELECT current_user() AS current_user, current_catalog() AS catalog, current_schema() AS schema;

-- (b) Are the 12 target tables present in the schema?
SHOW TABLES IN abac_tpcds.tpcds_1_delta;

-- (c) Sample REAL surrogate keys — we seed assignments from these (see 03).
SELECT 'customer'    AS tbl, CAST(c_customer_sk    AS STRING) AS id,
                             CAST(c_current_addr_sk AS STRING) AS org
FROM abac_tpcds.tpcds_1_delta.customer    ORDER BY c_customer_sk LIMIT 5;

SELECT 'item'        AS tbl, CAST(i_item_sk        AS STRING) AS id
FROM abac_tpcds.tpcds_1_delta.item        ORDER BY i_item_sk LIMIT 5;

SELECT 'store_sales' AS tbl, CAST(ss_customer_sk   AS STRING) AS id,
                             CAST(ss_store_sk       AS STRING) AS org
FROM abac_tpcds.tpcds_1_delta.store_sales ORDER BY ss_customer_sk LIMIT 5;

-- =====================================================================
-- (d) IS UNITY CATALOG ABAC ENABLED?  (policies + has_tag MATCH COLUMNS)
-- =====================================================================
-- There is no single flag. Use the probes below — the ERROR MESSAGE is the answer:
--   * parses / returns rows      -> the POLICY feature is available
--   * "feature not enabled" / "unsupported" / parse error near POLICY
--                                -> ask an account admin to turn on
--                                   "Attribute-Based Access Control" (Preview)
--                                   and "Governed tags", then re-run.
--
-- UI cross-check:
--   Catalog Explorer > (a table) should show a "Policies" tab, and
--   Settings > Catalog/Previews should list "Attribute-Based Access Control".
--   Settings > Catalog > Governed tags is where the 4 tag keys are created.

-- Probe 1: list policies at schema scope (POLICY DDL must be available to parse this)
SHOW POLICIES ON SCHEMA abac_tpcds.tpcds_1_delta;

-- Probe 2 (alternate spelling, try if Probe 1 errors on syntax)
-- SHOW POLICIES IN SCHEMA abac_tpcds.tpcds_1_delta;

-- Probe 3: information_schema exposure (present in some releases when enabled)
-- SELECT * FROM abac_tpcds.information_schema.policies LIMIT 20;

-- Probe 4 (definitive but creates+drops a throwaway policy on a real table):
--   Uncomment after 05/07 exist. If it succeeds, ABAC is fully usable.
-- CREATE OR REPLACE POLICY _abac_feature_probe
-- ON TABLE abac_tpcds.tpcds_1_delta.item
-- ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
-- TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
-- FOR TABLES
-- MATCH COLUMNS has_tag('abac_column_id') as id
-- USING COLUMNS (id, 'item', '100');
-- DROP POLICY _abac_feature_probe ON TABLE abac_tpcds.tpcds_1_delta.item;
