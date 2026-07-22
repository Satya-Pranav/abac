-- =====================================================================
-- 19_udf_contract.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- The UDF CONTRACT between a row-filter function's declared signature and USING COLUMNS: what
-- happens when USING COLUMNS supplies FEWER arguments than the UDF declares, and what happens when
-- the UDF's declared parameter TYPE differs from the bound column's type.
--   UC1 = arity: two_param(id BIGINT, extra STRING) declares TWO params; the (commented-out) POLICY
--                below supplies only ONE (id) via USING COLUMNS. A row filter (unlike a column mask,
--                whose ON COLUMN auto-binds arg 1) auto-supplies NO argument of its own, so ALL n
--                declared params must appear in USING COLUMNS. EXPECTED: CREATE POLICY is REJECTED
--                with an arity-mismatch error.
--   UC2 = type:  date_param(d DATE) is bound, via USING COLUMNS, to `ts` -- a TIMESTAMP column, not a
--                DATE column. Either Databricks coerces TIMESTAMP -> DATE at bind time and the filter
--                applies (keeping ts < 2020-01-11, i.e. 9 of 20 rows), or it rejects the type
--                mismatch and the query errors. Both are legitimate findings; which one is TRUE is
--                unknown until observed.
--
-- *** THE UC1 CREATE POLICY BLOCK BELOW IS COMMENTED OUT AND MUST STAY THAT WAY BY DEFAULT. ***
-- It is EXPECTED TO FAIL (arity mismatch) and must never be applied as part of a normal run of this
-- script. To observe the exact error:
--   1. Apply everything ABOVE the UC1 POLICY block (schema, table, tags, both functions, UC2's
--      policy, grants) normally -- that part is safe and idempotent.
--   2. Uncomment ONLY the UC1 `CREATE OR REPLACE POLICY arity_policy ...` statement below and run it
--      ALONE, in isolation from the rest of this script.
--   3. Record the exact error text VERBATIM (message + SQLSTATE) in docs/testing/jdbc-cases.md --
--      that error text, not a row count, is UC1's real finding.
--   4. Re-comment the block immediately afterward, so re-applying this whole script never re-attempts
--      the doomed CREATE POLICY.
--
-- NOTE ON UC1/UC2 SHARING ONE TABLE: both cases query `arity`. UC1's real assertion is "arity_policy
-- was never created" -- proved by the SQL script (the error recorded above), not by a row count.
-- Whatever count `SELECT count(*) FROM arity` returns reflects ONLY UC2's type_policy (or its
-- absence, if UC2's TIMESTAMP->DATE binding is instead rejected) -- it says nothing about UC1. See
-- the case comment in Cases.java for how both cases ship as Expect.info() until observed.
--
-- Creates an ISOLATED schema (abac_tpcds.abac_udf) so these policies cannot reach any main-suite
-- table (abac_tpcds.tpcds_1_delta.*).
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

CREATE SCHEMA IF NOT EXISTS abac_tpcds.abac_udf;

CREATE OR REPLACE TABLE abac_tpcds.abac_udf.arity (id BIGINT, ts TIMESTAMP);
INSERT INTO abac_tpcds.abac_udf.arity
  SELECT id, timestamp(date_add(DATE'2020-01-01', CAST(id AS INT))) FROM range(1, 21);
ALTER TABLE abac_tpcds.abac_udf.arity ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_tpcds.abac_udf.arity ALTER COLUMN ts SET TAGS ('abac_column_org' = 'true');

-- UC1: the UDF declares TWO params; the commented-out policy below would supply only ONE.
CREATE OR REPLACE FUNCTION abac_tpcds.abac_udf.two_param(id BIGINT, extra STRING)
RETURNS BOOLEAN RETURN id <= 10;

-- =====================================================================================
-- UC1 -- DO NOT UNCOMMENT AS PART OF A NORMAL APPLY OF THIS SCRIPT.
-- EXPECTED TO FAIL: arity mismatch. The row filter auto-supplies NO argument of its own, so all 2
-- declared params (id, extra) must appear in USING COLUMNS; only 1 (id) is given here on purpose.
-- Uncomment and run this ONE statement ALONE, record the verbatim error (message + SQLSTATE), then
-- re-comment it. See the operator note in the header above.
--
-- CREATE OR REPLACE POLICY arity_policy
-- ON TABLE abac_tpcds.abac_udf.arity
-- ROW FILTER abac_tpcds.abac_udf.two_param
-- TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
-- FOR TABLES
-- MATCH COLUMNS has_tag('abac_column_id') AS id
-- USING COLUMNS (id);
-- =====================================================================================

-- UC2: declared param type DATE, bound column type TIMESTAMP -- coerced, or rejected?
CREATE OR REPLACE FUNCTION abac_tpcds.abac_udf.date_param(d DATE)
RETURNS BOOLEAN RETURN d < DATE'2020-01-11';

CREATE OR REPLACE POLICY type_policy
ON TABLE abac_tpcds.abac_udf.arity
ROW FILTER abac_tpcds.abac_udf.date_param
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_org') AS ts
USING COLUMNS (ts);

GRANT USE SCHEMA ON SCHEMA abac_tpcds.abac_udf TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.abac_udf.arity TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac_udf.date_param TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac_udf.two_param  TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- Expect (as the SP via the suite):
--   UC1: SELECT count(*) FROM arity  ->  INFO. The real assertion is that CREATE POLICY
--        arity_policy was REJECTED (arity mismatch) -- see the operator note above; the observed
--        count here reflects only type_policy (UC2), or its absence, and is incidental to UC1.
--   UC2: SELECT count(*) FROM arity  ->  INFO. ts runs 2020-01-02..2020-01-21 (20 rows); the filter
--        keeps d < 2020-01-11. IF Databricks coerces TIMESTAMP -> DATE at bind time, that keeps
--        2020-01-02..2020-01-10 -> exactly 9 rows. IF it instead rejects the type mismatch, the query
--        ERRORs. INFO first; convert BOTH UC1 and UC2 to hard assertions (Expect.exact(n) or
--        Expect.errorContains(...)) once observed -- same pattern as A3/C6/TH3 and TG2.

-- ---- TEARDOWN ----
-- DROP POLICY IF EXISTS arity_policy ON TABLE abac_tpcds.abac_udf.arity;  -- only if UC1 was run
-- DROP POLICY IF EXISTS type_policy  ON TABLE abac_tpcds.abac_udf.arity;
-- DROP FUNCTION IF EXISTS abac_tpcds.abac_udf.date_param;
-- DROP FUNCTION IF EXISTS abac_tpcds.abac_udf.two_param;
-- DROP SCHEMA IF EXISTS abac_tpcds.abac_udf CASCADE;
