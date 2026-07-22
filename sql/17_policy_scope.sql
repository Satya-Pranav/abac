-- =====================================================================
-- 17_policy_scope.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Policy SCOPE: does an ON SCHEMA policy govern every table beneath it, what happens to a table with
-- no matching tag, and what happens when a schema-level and a table-level row filter both target the
-- SAME table.
--   SC1 = scoped_a:    a table governed by a SCHEMA-level policy (ON SCHEMA, not ON TABLE) -> filtered.
--   SC2 = scoped_c:    a THIRD table in the same schema, carrying the SAME governance tag and covered
--                      by NOTHING but the schema-level policy -> proves schema scope governs EVERY
--                      matching member, not just the first one it happened to bind.
--   SC3 = ungoverned:  sits inside the policy's ON SCHEMA scope but has NO abac_column_id tag on any
--                      column -> MATCH COLUMNS matches nothing for this table, so the policy silently
--                      does not apply -> ALL rows are visible. This is the dangerous case: a BROKEN
--                      policy fails CLOSED (errors, or blocks everything); a NON-MATCHING one fails
--                      OPEN (returns everything, unfiltered, with no error at all).
--   SC4 = scoped_b:    carries BOTH the schema-level policy AND a second, TABLE-level policy. This is
--                      NOT a precedence contest. Unity Catalog allows at most ONE row filter per
--                      table, enforced at query time, table-wide. Both CREATE POLICY statements below
--                      SUCCEED -- the conflict is only detected when the table is QUERIED, and the
--                      query errors with UC_ABAC_MULTIPLE_ROW_FILTERS (SQLSTATE 42KDJ). A planner that
--                      silently picked "the more specific one" (table beats schema) would be WRONG --
--                      disproving exactly that silent-precedence assumption is the point of SC4.
--
-- DESIGN NOTE (deliberate deviation from an earlier draft of this script): that draft had SC2 and SC4
-- BOTH querying `scoped_b`, so adding SC4's table-level policy on top of the schema-level one made
-- EVERY query against scoped_b fail with UC_ABAC_MULTIPLE_ROW_FILTERS -- including SC2's, forcing a
-- two-stage apply (verify SC1-SC3, THEN apply SC4's policy, at which point SC2 flips to an error).
-- That is a bad property for a suite that must be re-runnable in one pass. Fixed structurally: a THIRD
-- table, `scoped_c`, same shape/data as scoped_a/scoped_b, same abac_column_id tag on its id column,
-- covered by the SCHEMA-level policy and by NOTHING else. SC2 now queries scoped_c (still proving
-- schema scope covers every matching member); scoped_b exists SOLELY to carry SC4's schema+table
-- conflict. Result: all four SC cases hold SIMULTANEOUSLY, and this whole script applies in ONE pass.
--
-- Creates an ISOLATED schema (abac_tpcds.abac_scope) so the schema-level policy cannot reach any of
-- the main suite's tables (abac_tpcds.tpcds_1_delta.*).
--
-- PREREQUISITE: Uses only the already-registered governed tag keys abac_column_id / abac_column_org
-- (see sql/07). Do NOT introduce a new tag key without registering it first in Settings > Catalog >
-- Governed tags -- an unregistered key fails at CREATE POLICY time with UC_INVALID_POLICY_CONDITION
-- 'Unknown tag policy key'.
--
-- Apply as OWNER / metastore admin. Prerequisites: none beyond catalog abac_tpcds already existing
-- (sql/01). Teardown at the bottom.
-- SP the JDBC suite authenticates as: 76d5804d-d302-4014-a1d3-d846f02c84ef
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_tpcds.abac_scope;

-- SC1: a table inside the scoped schema, governed by a SCHEMA-level policy.
CREATE OR REPLACE TABLE abac_tpcds.abac_scope.scoped_a (id BIGINT, label STRING);
INSERT INTO abac_tpcds.abac_scope.scoped_a
  SELECT id, concat('row-', id) FROM range(1, 21);

-- SC4: a second table in the SAME schema -- exists SOLELY to carry the schema+table conflict below.
-- No other case relies on scoped_b returning a real row count.
CREATE OR REPLACE TABLE abac_tpcds.abac_scope.scoped_b (id BIGINT, label STRING);
INSERT INTO abac_tpcds.abac_scope.scoped_b
  SELECT id, concat('row-', id) FROM range(1, 21);

-- SC2: a THIRD table, same shape/data, governed by NOTHING but the schema-level policy (unlike
-- scoped_b) -- proves schema scope covers every matching member, not just the first.
CREATE OR REPLACE TABLE abac_tpcds.abac_scope.scoped_c (id BIGINT, label STRING);
INSERT INTO abac_tpcds.abac_scope.scoped_c
  SELECT id, concat('row-', id) FROM range(1, 21);

-- SC3: a table with NO matching tag -- must return ALL rows (the policy cannot see it).
CREATE OR REPLACE TABLE abac_tpcds.abac_scope.ungoverned (id BIGINT);
INSERT INTO abac_tpcds.abac_scope.ungoverned SELECT id FROM range(1, 21);

-- Governance tag: scoped_a / scoped_b / scoped_c carry it; `ungoverned` deliberately does not.
ALTER TABLE abac_tpcds.abac_scope.scoped_a ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_tpcds.abac_scope.scoped_b ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_tpcds.abac_scope.scoped_c ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE FUNCTION abac_tpcds.abac_scope.scope_filter(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;

-- The SCHEMA-level policy: scoped_a, scoped_b, scoped_c are governed (tagged id column);
-- `ungoverned` is not (no tag -> MATCH COLUMNS finds nothing there, so it is silently ungoverned).
CREATE OR REPLACE POLICY scope_schema_policy
ON SCHEMA abac_tpcds.abac_scope
ROW FILTER abac_tpcds.abac_scope.scope_filter
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

GRANT USE SCHEMA ON SCHEMA abac_tpcds.abac_scope TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.abac_scope.scoped_a   TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.abac_scope.scoped_b   TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.abac_scope.scoped_c   TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.abac_scope.ungoverned TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac_scope.scope_filter TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- SC4: add a TABLE-level policy on top of the schema-level one, on scoped_b ONLY (scoped_a / scoped_c
-- are untouched by this block, so SC1 / SC2 are unaffected).
-- EXPECTATION: NOT a precedence contest -- two row filters on one table is a CONFLICT, not a choice
-- between "schema wins" or "table wins". Both CREATE POLICY statements below succeed; the QUERY
-- against scoped_b fails with UC_ABAC_MULTIPLE_ROW_FILTERS (SQLSTATE 42KDJ).
CREATE OR REPLACE FUNCTION abac_tpcds.abac_scope.scope_filter_tbl(id BIGINT)
RETURNS BOOLEAN RETURN id <= 5;

CREATE OR REPLACE POLICY scope_table_policy
ON TABLE abac_tpcds.abac_scope.scoped_b
ROW FILTER abac_tpcds.abac_scope.scope_filter_tbl
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

GRANT EXECUTE ON FUNCTION abac_tpcds.abac_scope.scope_filter_tbl TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- Expect (as the SP via the suite):
--   SC1: SELECT count(*) FROM scoped_a               ->  10  (schema policy, id <= 10 of 20 rows)
--   SC2: SELECT count(*) FROM scoped_c WHERE id > 10  ->  0   (schema policy also governs this THIRD,
--        otherwise-unrelated table -- scope is not limited to the first table it happens to bind)
--   SC3: SELECT count(*) FROM ungoverned              ->  20  (no tag -> policy silently does not
--        apply -> ALL rows; fails OPEN, not closed)
--   SC4: SELECT count(*) FROM scoped_b                ->  ERROR UC_ABAC_MULTIPLE_ROW_FILTERS (42KDJ)
--        -- schema-level + table-level row filters on the SAME table is a conflict, not a
--        precedence order
--
-- All four cases hold SIMULTANEOUSLY -- this script is applied in ONE pass; no staged apply or
-- re-run sequencing is required, and the suite stays re-runnable end to end.

-- ---- TEARDOWN ----
-- DROP POLICY IF EXISTS scope_table_policy ON TABLE abac_tpcds.abac_scope.scoped_b;
-- DROP POLICY IF EXISTS scope_schema_policy ON SCHEMA abac_tpcds.abac_scope;
-- DROP SCHEMA IF EXISTS abac_tpcds.abac_scope CASCADE;
