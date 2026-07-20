-- =====================================================================
-- 12_rowfilter_conflict.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Two ROW-FILTER conflict scenarios on real tables (negative tests — every query ERRORS).
--
--   web_page  = Scenario A: two row filters with DIFFERENT column bindings
--                 rf_page_1 binds (col1, col2) ; rf_page_2 binds (col2)   [col2 shared]
--   web_site  = Scenario B: two row filters on the SAME single column
--                 rf_site_1 and rf_site_2 both bind (web_site_sk)
--
-- PREDICTION (same mechanism as W1 on warehouse): a row filter is TABLE-WIDE, at most ONE per
-- table (UC_ABAC_MULTIPLE_ROW_FILTERS), enforced at QUERY time, INDEPENDENT of the column list.
-- So both CREATE POLICY succeed, and EVERY query on either table errors — even a projection of a
-- column bound by only one filter, even count(*). Suite cases: WP1/WP2 (web_page), WS1 (web_site).
--
-- web_page has NO existing governance tags (sql/07 did not tag it) -> clean to tag col1/col2.
-- web_site.web_site_sk is already id/org-tagged by sql/07; we (re)set abac_column_id on it and bind
-- both filters through that one tag -> both resolve to the same column.
--
-- SP the JDBC suite authenticates as (owners bypass row filters):
--   76d5804d-d302-4014-a1d3-d846f02c84ef
-- If DBR nudges MATCH COLUMNS grammar or the BIGINT arg types, accept its suggestion.
-- =====================================================================

GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.web_page TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.web_site TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- =========================================================
-- Scenario A — web_page : different column bindings (col1,col2) vs (col2)
-- =========================================================
-- col1 = wp_web_page_sk (tag id) ; col2 = wp_access_date_sk (tag org)  [col2 is shared]
ALTER TABLE abac_tpcds.tpcds_1_delta.web_page ALTER COLUMN wp_web_page_sk    SET TAGS ('abac_column_id'  = 'true');
ALTER TABLE abac_tpcds.tpcds_1_delta.web_page ALTER COLUMN wp_access_date_sk SET TAGS ('abac_column_org' = 'true');

CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.rf_page_1(c1 BIGINT, c2 BIGINT)
  RETURNS BOOLEAN RETURN true;            -- 2-column row filter
CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.rf_page_2(c2 BIGINT)
  RETURNS BOOLEAN RETURN c2 IS NOT NULL;  -- 1-column row filter (col2)

CREATE OR REPLACE POLICY web_page_rf1_policy
ON TABLE abac_tpcds.tpcds_1_delta.web_page
ROW FILTER abac_tpcds.tpcds_1_delta.rf_page_1
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS c1, has_tag('abac_column_org') AS c2
USING COLUMNS (c1, c2);

-- >>> If THIS 2nd policy errors at CREATE time, the conflict is caught at creation — that is the finding.
CREATE OR REPLACE POLICY web_page_rf2_policy
ON TABLE abac_tpcds.tpcds_1_delta.web_page
ROW FILTER abac_tpcds.tpcds_1_delta.rf_page_2
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_org') AS c2
USING COLUMNS (c2);

-- =========================================================
-- Scenario B — web_site : two row filters on the SAME column (web_site_sk)
-- =========================================================
ALTER TABLE abac_tpcds.tpcds_1_delta.web_site ALTER COLUMN web_site_sk SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.rf_site_1(c BIGINT)
  RETURNS BOOLEAN RETURN true;
CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.rf_site_2(c BIGINT)
  RETURNS BOOLEAN RETURN c IS NOT NULL;

CREATE OR REPLACE POLICY web_site_rf1_policy
ON TABLE abac_tpcds.tpcds_1_delta.web_site
ROW FILTER abac_tpcds.tpcds_1_delta.rf_site_1
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS c
USING COLUMNS (c);

CREATE OR REPLACE POLICY web_site_rf2_policy
ON TABLE abac_tpcds.tpcds_1_delta.web_site
ROW FILTER abac_tpcds.tpcds_1_delta.rf_site_2
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS c
USING COLUMNS (c);

-- =========================================================
-- Probes (run as the SP via the JDBC suite; owner bypasses row filters)
--   SELECT count(*)         FROM web_page;  -- WP1: different bindings   -> CONFLICT
--   SELECT wp_web_page_sk   FROM web_page;  -- WP2: col bound by rf1 only -> still CONFLICT (table-wide)
--   SELECT count(*)         FROM web_site;  -- WS1: same column, 2 filters -> CONFLICT
-- All expected: [UC_ABAC_MULTIPLE_ROW_FILTERS] at most one row filter per table.
-- =========================================================

-- ---- TEARDOWN ----
--   DROP POLICY IF EXISTS web_page_rf1_policy ON TABLE abac_tpcds.tpcds_1_delta.web_page;
--   DROP POLICY IF EXISTS web_page_rf2_policy ON TABLE abac_tpcds.tpcds_1_delta.web_page;
--   DROP POLICY IF EXISTS web_site_rf1_policy ON TABLE abac_tpcds.tpcds_1_delta.web_site;
--   DROP POLICY IF EXISTS web_site_rf2_policy ON TABLE abac_tpcds.tpcds_1_delta.web_site;
--   DROP FUNCTION IF EXISTS abac_tpcds.tpcds_1_delta.rf_page_1;
--   DROP FUNCTION IF EXISTS abac_tpcds.tpcds_1_delta.rf_page_2;
--   DROP FUNCTION IF EXISTS abac_tpcds.tpcds_1_delta.rf_site_1;
--   DROP FUNCTION IF EXISTS abac_tpcds.tpcds_1_delta.rf_site_2;
