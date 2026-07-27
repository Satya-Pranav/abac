-- =====================================================================
-- 15_udf_contract.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported 1:1 from sql/19_udf_contract.sql (TPC-DS), for the OneTrust suite (OT-UC2 only).
-- The UDF CONTRACT between a row-filter function's declared signature and USING COLUMNS.
--   UC1 = arity: NOT a suite case (see class doc above) -- kept commented out below, exactly as
--                in TPC-DS. A row filter auto-supplies NO argument of its own, so ALL declared
--                params must appear in USING COLUMNS; this is a DDL-time rejection, never
--                observable from the SP's query path.
--   UC2 = type:  date_param(d DATE) is bound, via USING COLUMNS, to `ts` -- a TIMESTAMP column.
--                Databricks COERCES TIMESTAMP -> DATE at bind time (confirmed live for TPC-DS
--                2026-07-22); the filter applies, keeping ts < 2020-01-11 (9 of 20 rows).
--
-- *** THE UC1 CREATE POLICY BLOCK BELOW IS COMMENTED OUT AND MUST STAY THAT WAY BY DEFAULT. ***
-- Uncomment ONLY to reproduce the arity-mismatch error in isolation; re-comment immediately after.
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_udf;

CREATE OR REPLACE TABLE abac_onetrust.abac_udf.arity (id BIGINT, ts TIMESTAMP);
INSERT INTO abac_onetrust.abac_udf.arity
  SELECT id, timestamp(date_add(DATE'2020-01-01', CAST(id AS INT))) FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_udf.arity ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.abac_udf.arity ALTER COLUMN ts SET TAGS ('abac_column_org' = 'true');

-- UC1: the UDF declares TWO params; a policy supplying only ONE is REJECTED at CREATE POLICY time.
CREATE OR REPLACE FUNCTION abac_onetrust.abac_udf.two_param(id BIGINT, extra STRING)
RETURNS BOOLEAN RETURN id <= 10;

-- =====================================================================================
-- UC1 -- DO NOT UNCOMMENT AS PART OF A NORMAL APPLY OF THIS SCRIPT. EXPECTED TO FAIL
-- (arity mismatch). See the class doc above.
--
-- CREATE OR REPLACE POLICY arity_policy
-- ON TABLE abac_onetrust.abac_udf.arity
-- ROW FILTER abac_onetrust.abac_udf.two_param
-- TO `<ONETRUST_SP>`
-- FOR TABLES
-- MATCH COLUMNS has_tag('abac_column_id') AS id
-- USING COLUMNS (id);
-- =====================================================================================

-- UC2: declared param type DATE, bound column type TIMESTAMP -- coerced, not rejected.
CREATE OR REPLACE FUNCTION abac_onetrust.abac_udf.date_param(d DATE)
RETURNS BOOLEAN RETURN d < DATE'2020-01-11';

CREATE OR REPLACE POLICY type_policy
ON TABLE abac_onetrust.abac_udf.arity
ROW FILTER abac_onetrust.abac_udf.date_param
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_org') AS ts
USING COLUMNS (ts);

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_udf TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_udf.arity TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_udf.date_param TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_udf.two_param  TO `<ONETRUST_SP>`;

-- Expect (as the SP via the suite):
--   OT-UC2: SELECT count(*) FROM arity -> 9 (ts runs 2020-01-02..2020-01-21; TIMESTAMP->DATE
--           coercion keeps d < 2020-01-11, i.e. 2020-01-02..2020-01-10)

-- ---- TEARDOWN ----
--   DROP POLICY IF EXISTS type_policy  ON TABLE abac_onetrust.abac_udf.arity;
--   DROP FUNCTION IF EXISTS abac_onetrust.abac_udf.date_param;
--   DROP FUNCTION IF EXISTS abac_onetrust.abac_udf.two_param;
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_udf CASCADE;
