-- =====================================================================
-- 08_row_filter_conflict.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported from sql/12_rowfilter_conflict.sql (TPC-DS), for the OneTrust suite (OT-W1/OT-WP1/
-- OT-WP2/OT-WS1). Isolated schema, not a real OneTrust table -- see Task 7's plan note: TPC-DS's
-- W1/WP1/WP2/WS1 attach to real tables (warehouse/web_page/web_site); this port uses 3 throwaway
-- tables instead, matching the isolated-schema pattern the rest of Tier B follows.
--
--   conflict_a = Scenario A (was `warehouse`): two policies, allow-all + deny-all (OT-W1)
--   conflict_b = Scenario B (was `web_page`): two row filters, DIFFERENT column bindings (OT-WP1/OT-WP2)
--   conflict_c = Scenario C (was `web_site`): two row filters, SAME single column (OT-WS1)
--
-- PREDICTION (same mechanism confirmed live for TPC-DS): a row filter is TABLE-WIDE, at most ONE
-- per table (UC_ABAC_MULTIPLE_ROW_FILTERS), enforced at QUERY time, INDEPENDENT of the column list.
-- Both CREATE POLICY succeed per pair; every query on any of the 3 tables errors.
--
-- SP the JDBC suite authenticates as (owners bypass row filters): <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_conflict;

-- =========================================================
-- OT-W1 -- conflict_a: allow-all + deny-all
-- =========================================================
CREATE OR REPLACE TABLE abac_onetrust.abac_conflict.conflict_a (id BIGINT);
INSERT INTO abac_onetrust.abac_conflict.conflict_a SELECT id FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_conflict.conflict_a ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE FUNCTION abac_onetrust.abac_conflict.allow_all(id BIGINT) RETURNS BOOLEAN RETURN true;
CREATE OR REPLACE FUNCTION abac_onetrust.abac_conflict.deny_all(id BIGINT)  RETURNS BOOLEAN RETURN false;

CREATE OR REPLACE POLICY conflict_a_allow_policy
ON TABLE abac_onetrust.abac_conflict.conflict_a
ROW FILTER abac_onetrust.abac_conflict.allow_all
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

-- >>> If THIS 2nd policy errors at CREATE time, the conflict is caught at creation -- that is the finding.
CREATE OR REPLACE POLICY conflict_a_deny_policy
ON TABLE abac_onetrust.abac_conflict.conflict_a
ROW FILTER abac_onetrust.abac_conflict.deny_all
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

-- =========================================================
-- OT-WP1 / OT-WP2 -- conflict_b: DIFFERENT column bindings (col1,col2) vs (col2)
-- =========================================================
CREATE OR REPLACE TABLE abac_onetrust.abac_conflict.conflict_b (col1 BIGINT, col2 BIGINT);
INSERT INTO abac_onetrust.abac_conflict.conflict_b SELECT id, id * 2 FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_conflict.conflict_b ALTER COLUMN col1 SET TAGS ('abac_column_id'  = 'true');
ALTER TABLE abac_onetrust.abac_conflict.conflict_b ALTER COLUMN col2 SET TAGS ('abac_column_org' = 'true');

CREATE OR REPLACE FUNCTION abac_onetrust.abac_conflict.rf_b_1(c1 BIGINT, c2 BIGINT)
  RETURNS BOOLEAN RETURN true;
CREATE OR REPLACE FUNCTION abac_onetrust.abac_conflict.rf_b_2(c2 BIGINT)
  RETURNS BOOLEAN RETURN c2 IS NOT NULL;

CREATE OR REPLACE POLICY conflict_b_rf1_policy
ON TABLE abac_onetrust.abac_conflict.conflict_b
ROW FILTER abac_onetrust.abac_conflict.rf_b_1
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS c1, has_tag('abac_column_org') AS c2
USING COLUMNS (c1, c2);

CREATE OR REPLACE POLICY conflict_b_rf2_policy
ON TABLE abac_onetrust.abac_conflict.conflict_b
ROW FILTER abac_onetrust.abac_conflict.rf_b_2
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_org') AS c2
USING COLUMNS (c2);

-- =========================================================
-- OT-WS1 -- conflict_c: two row filters on the SAME column
-- =========================================================
CREATE OR REPLACE TABLE abac_onetrust.abac_conflict.conflict_c (id BIGINT);
INSERT INTO abac_onetrust.abac_conflict.conflict_c SELECT id FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_conflict.conflict_c ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE FUNCTION abac_onetrust.abac_conflict.rf_c_1(c BIGINT) RETURNS BOOLEAN RETURN true;
CREATE OR REPLACE FUNCTION abac_onetrust.abac_conflict.rf_c_2(c BIGINT) RETURNS BOOLEAN RETURN c IS NOT NULL;

CREATE OR REPLACE POLICY conflict_c_rf1_policy
ON TABLE abac_onetrust.abac_conflict.conflict_c
ROW FILTER abac_onetrust.abac_conflict.rf_c_1
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS c
USING COLUMNS (c);

CREATE OR REPLACE POLICY conflict_c_rf2_policy
ON TABLE abac_onetrust.abac_conflict.conflict_c
ROW FILTER abac_onetrust.abac_conflict.rf_c_2
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS c
USING COLUMNS (c);

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_conflict TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_conflict.conflict_a TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_conflict.conflict_b TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_conflict.conflict_c TO `<ONETRUST_SP>`;

-- Expect (as the SP via the suite): all 4 queries below error with UC_ABAC_MULTIPLE_ROW_FILTERS.
--   OT-W1:  SELECT count(*)  FROM conflict_a
--   OT-WP1: SELECT count(*)  FROM conflict_b
--   OT-WP2: SELECT col1      FROM conflict_b   (bound by rf_b_1 only -- still CONFLICT, table-wide)
--   OT-WS1: SELECT count(*)  FROM conflict_c

-- ---- TEARDOWN ----
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_conflict CASCADE;
