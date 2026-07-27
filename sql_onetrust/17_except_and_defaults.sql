-- =====================================================================
-- 17_except_and_defaults.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported 1:1 from sql/21_except_and_defaults.sql (TPC-DS), for the OneTrust suite (OT-EX1/OT-EX2).
--
-- CONFIRMED FINDING (carried over from TPC-DS, 2026-07-23): row-filter UDF ARITY is STRICT, and a
-- DEFAULT parameter does NOT let USING COLUMNS omit the argument -- a DDL-time rejection the
-- service principal can never observe via its query path (same reasoning as UC1, Task 14). DP1's
-- CREATE POLICY block below is kept COMMENTED OUT as a reproducible demonstration only -- do NOT
-- uncomment it in a normal apply.
--
-- What this file DOES test live, via the service principal:
--   OT-EX1 = EXCEPT:  does CREATE POLICY ... TO <principal> EXCEPT <principal> actually exempt the
--                      excepted principal?
--   OT-EX2 = the CONTROL for OT-EX1 -- disambiguates whether OT-EX1's "all rows" means "the SP was
--            exempted" or "the SP was never subject to the broad TO grant at all".
--
-- OPERATOR NOTE: `account users` is the built-in Unity Catalog group covering every principal in
-- the account (backticked because its name contains a space). If this workspace rejects that
-- group form in a policy's TO clause, STOP and record the exact error verbatim -- do not silently
-- substitute a narrower principal, which would defeat what OT-EX1 tests.
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_gaps;

-- =========================================================
-- OT-EX1 -- the EXCEPT clause
-- =========================================================
CREATE OR REPLACE TABLE abac_onetrust.abac_gaps.exempt (id BIGINT);
INSERT INTO abac_onetrust.abac_gaps.exempt SELECT id FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_gaps.exempt ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE FUNCTION abac_onetrust.abac_gaps.except_filter(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;                -- keeps 10 of 20 rows for a SUBJECT principal

CREATE OR REPLACE POLICY exempt_policy
ON TABLE abac_onetrust.abac_gaps.exempt
ROW FILTER abac_onetrust.abac_gaps.except_filter
TO `account users`
EXCEPT `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_gaps             TO `<ONETRUST_SP>`;
GRANT SELECT   ON TABLE  abac_onetrust.abac_gaps.exempt         TO `<ONETRUST_SP>`;
GRANT EXECUTE  ON FUNCTION abac_onetrust.abac_gaps.except_filter TO `<ONETRUST_SP>`;

-- =========================================================
-- OT-EX2 -- the CONTROL for OT-EX1. Same shape, same filter, same broad TO, but NO EXCEPT.
-- =========================================================
CREATE OR REPLACE TABLE abac_onetrust.abac_gaps.subject (id BIGINT);
INSERT INTO abac_onetrust.abac_gaps.subject SELECT id FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_gaps.subject ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE POLICY subject_policy
ON TABLE abac_onetrust.abac_gaps.subject
ROW FILTER abac_onetrust.abac_gaps.except_filter
TO `account users`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

GRANT SELECT ON TABLE abac_onetrust.abac_gaps.subject TO `<ONETRUST_SP>`;

-- =========================================================
-- DP1 -- DEFAULT UDF parameters: ANSWERED (see TPC-DS's confirmed finding above), kept commented
-- as a reproducible demo only. DO NOT UNCOMMENT IN A NORMAL APPLY -- fails BY DESIGN.
-- =========================================================
-- CREATE OR REPLACE TABLE abac_onetrust.abac_gaps.defparam (id BIGINT);
-- INSERT INTO abac_onetrust.abac_gaps.defparam SELECT id FROM range(1, 21);
-- ALTER TABLE abac_onetrust.abac_gaps.defparam ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
-- CREATE OR REPLACE FUNCTION abac_onetrust.abac_gaps.def_filter(id BIGINT, cutoff BIGINT DEFAULT 10)
-- RETURNS BOOLEAN RETURN id <= cutoff;
-- CREATE OR REPLACE POLICY defparam_policy
-- ON TABLE abac_onetrust.abac_gaps.defparam
-- ROW FILTER abac_onetrust.abac_gaps.def_filter
-- TO `<ONETRUST_SP>`
-- FOR TABLES
-- MATCH COLUMNS has_tag('abac_column_id') AS id
-- USING COLUMNS (id);          -- <-- 1 arg supplied, 2 declared -> REJECTED here

-- Expect (as the SP via the suite):
--   OT-EX2: SELECT count(*) FROM abac_onetrust.abac_gaps.subject  -> 10 (CONTROL: the SP IS subject
--           to subject_policy, no EXCEPT -- proves the SP is in `account users`, without which
--           OT-EX1 proves nothing)
--   OT-EX1: SELECT count(*) FROM abac_onetrust.abac_gaps.exempt   -> 20 (ALL rows), MEANINGFUL ONLY
--           IF OT-EX2 == 10. The SP is EXCEPTed from exempt_policy.

-- ---- TEARDOWN ----
--   DROP POLICY IF EXISTS exempt_policy   ON TABLE abac_onetrust.abac_gaps.exempt;
--   DROP POLICY IF EXISTS subject_policy  ON TABLE abac_onetrust.abac_gaps.subject;
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_gaps CASCADE;
