-- =====================================================================
-- 21_except_and_defaults.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
--
-- ############################################################################
-- ##   CONFIRMED FINDING (2026-07-23): row-filter UDF ARITY is STRICT.       ##
-- ############################################################################
--
-- The DP1 CREATE POLICY below (a UDF declaring TWO params, one with a DEFAULT, bound via a policy
-- whose USING COLUMNS supplies only ONE) was applied live and REJECTED with:
--
--   ErrorClass=INVALID_PARAMETER_VALUE.INVALID_PARAMETER_VALUE
--   "The policy definition requires 1 argument(s), but the referred function 'def_filter'
--    takes 2 argument(s)"
--
-- This settles BOTH open questions at once:
--   * DEFAULT parameters (gap 2 / DP1): a DEFAULT does NOT let you omit the argument. USING COLUMNS
--     must supply exactly as many arguments as the UDF declares. The default is never reached.
--   * ARITY (gap 3 / sql/19's UC1): confirmed, and MORE strongly than UC1's plain-arity case --
--     even WITH a DEFAULT on the omitted param, the mismatch is rejected. sql/19's separate
--     arity_policy run is now redundant; leave it commented. A row filter auto-supplies NO
--     argument of its own (unlike a column mask's ON COLUMN), so all N declared params are
--     mandatory in USING COLUMNS.
--
-- Because this is a CREATE-POLICY-time (DDL) rejection, the suite's service principal cannot
-- observe it (only an owner issues CREATE POLICY; the SP's SELECT would just see an ungoverned
-- table returning all rows -- a fail-open masquerade). There is therefore NO suite case for DP1;
-- it was dropped, exactly as UC1 was. The DP1 block below is kept COMMENTED OUT as a reproducible
-- demonstration only -- do NOT uncomment it in a normal apply; it will fail by design.
-- ############################################################################
--
-- What this file DOES still test live, via the service principal:
--   EX1 = EXCEPT:  does `CREATE POLICY ... TO <principal> EXCEPT <principal>` actually exempt the
--                  excepted principal from the row filter it would otherwise be subject to?
--
-- Creates an ISOLATED schema (abac_tpcds.abac_gaps) so these policies cannot reach any
-- main-suite table (abac_tpcds.tpcds_1_delta.*).
--
-- PREREQUISITE: Uses only the already-registered governed tag key abac_column_id (see sql/07).
-- Do NOT introduce a new tag key without registering it first in Settings > Catalog > Governed
-- tags -- an unregistered key fails at CREATE POLICY time with UC_INVALID_POLICY_CONDITION
-- 'Unknown tag policy key'. Do NOT use a tag value outside abac_column_id's allowed list
-- ('true', 'filter', 'ignore') -- an out-of-list value fails at ALTER ... SET TAGS.
--
-- Apply as OWNER / metastore admin. Prerequisites: none beyond catalog abac_tpcds already
-- existing (sql/01). Teardown at the bottom.
-- SP the JDBC suite authenticates as: 76d5804d-d302-4014-a1d3-d846f02c84ef
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_tpcds.abac_gaps;

-- =========================================================
-- EX1 -- the EXCEPT clause: does it actually exempt a principal from the row filter?
-- =========================================================
-- 20 fixed rows (id 1..20). except_filter keeps id <= 10 for anyone SUBJECT to the policy.
CREATE OR REPLACE TABLE abac_tpcds.abac_gaps.exempt (id BIGINT);
INSERT INTO abac_tpcds.abac_gaps.exempt SELECT id FROM range(1, 21);
ALTER TABLE abac_tpcds.abac_gaps.exempt ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE FUNCTION abac_tpcds.abac_gaps.except_filter(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;                -- keeps 10 of 20 rows for a SUBJECT principal

-- >>> OPERATOR NOTE -- READ BEFORE APPLYING <<<
-- `account users` below is the built-in Unity Catalog group covering every user/principal in the
-- account (referenced as a backticked identifier because its name contains a space -- the same
-- form used for GRANT ... TO `account users` elsewhere in Unity Catalog). If this workspace does
-- NOT accept that exact group form in a policy's TO clause (wrong spelling, unresolvable group,
-- or a syntax rejection), CREATE POLICY below will fail. DO NOT silently substitute a different
-- principal (e.g. a specific user, or dropping back to `TO <sp> EXCEPT <sp>` which is vacuous) to
-- make the statement succeed -- that would silently change what EX1 tests. EX1 must exempt the SP
-- from a policy that otherwise binds a BROAD principal set; swapping in a narrower TO defeats the
-- point. If this statement errors, STOP, record the exact error text VERBATIM (message +
-- SQLSTATE) in docs/testing/jdbc-cases.md, and report "the `account users` group form is
-- rejected" as EX1's finding in its own right -- do not retry under the same case name with a
-- substitute principal.
CREATE OR REPLACE POLICY exempt_policy
ON TABLE abac_tpcds.abac_gaps.exempt
ROW FILTER abac_tpcds.abac_gaps.except_filter
TO `account users`
EXCEPT `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

GRANT USE SCHEMA ON SCHEMA abac_tpcds.abac_gaps          TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT   ON TABLE  abac_tpcds.abac_gaps.exempt      TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT EXECUTE  ON FUNCTION abac_tpcds.abac_gaps.except_filter TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- =========================================================
-- EX2 -- the CONTROL for EX1. Same table shape, same filter (id <= 10), same broad TO principal,
-- but NO EXCEPT clause -- so the SP IS subject to the filter here. This disambiguates EX1:
--   * EX1 returns 20 (all rows). That alone is ambiguous -- it could mean "EXCEPT exempted the SP"
--     OR "the SP was never in `account users`, so the policy never applied to it" (a fail-open
--     masquerade). EX1 cannot tell those apart.
--   * EX2 pins it down. If EX2 returns 10, the SP IS in `account users` and IS filtered by the
--     broad grant -- so EX1's 20 is attributable ONLY to the EXCEPT clause. EXCEPT proven.
--   * If EX2 instead returns 20, then `account users` does NOT scope this service principal at all
--     (SPs may not be members), EX1 is INCONCLUSIVE, and both cases must be rebound to a custom
--     group that the SP is explicitly added to. A FAILing EX2 is that signal -- record it and stop.
CREATE OR REPLACE TABLE abac_tpcds.abac_gaps.subject (id BIGINT);
INSERT INTO abac_tpcds.abac_gaps.subject SELECT id FROM range(1, 21);
ALTER TABLE abac_tpcds.abac_gaps.subject ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE POLICY subject_policy
ON TABLE abac_tpcds.abac_gaps.subject
ROW FILTER abac_tpcds.abac_gaps.except_filter
TO `account users`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

GRANT SELECT ON TABLE abac_tpcds.abac_gaps.subject TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- =========================================================
-- DP1 -- DEFAULT UDF parameters: ANSWERED, kept commented as a reproducible demo (see banner).
-- =========================================================
-- DO NOT UNCOMMENT IN A NORMAL APPLY. Running the CREATE POLICY below fails BY DESIGN with:
--   INVALID_PARAMETER_VALUE.INVALID_PARAMETER_VALUE
--   "The policy definition requires 1 argument(s), but the referred function 'def_filter'
--    takes 2 argument(s)"
-- confirming that a DEFAULT does not permit omitting the argument (see the banner at the top).
-- To reproduce: uncomment this whole block, run the CREATE POLICY alone, observe the error,
-- re-comment. No suite case depends on it (DP1 was dropped -- a DDL rejection is not observable
-- from the SP's query path).
--
-- CREATE OR REPLACE TABLE abac_tpcds.abac_gaps.defparam (id BIGINT);
-- INSERT INTO abac_tpcds.abac_gaps.defparam SELECT id FROM range(1, 21);
-- ALTER TABLE abac_tpcds.abac_gaps.defparam ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
-- CREATE OR REPLACE FUNCTION abac_tpcds.abac_gaps.def_filter(id BIGINT, cutoff BIGINT DEFAULT 10)
-- RETURNS BOOLEAN RETURN id <= cutoff;
-- CREATE OR REPLACE POLICY defparam_policy
-- ON TABLE abac_tpcds.abac_gaps.defparam
-- ROW FILTER abac_tpcds.abac_gaps.def_filter
-- TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
-- FOR TABLES
-- MATCH COLUMNS has_tag('abac_column_id') AS id
-- USING COLUMNS (id);          -- <-- 1 arg supplied, 2 declared -> REJECTED here

-- Expect (as the SP via the suite):
--   EX2: SELECT count(*) FROM abac_tpcds.abac_gaps.subject   ->  10 (CONTROL). The SP is subject to
--        subject_policy (TO account users, no EXCEPT), so except_filter (id <= 10) applies. A
--        result of 10 proves the SP is in `account users` -- WITHOUT which EX1 proves nothing.
--        If EX2 returns 20, `account users` does not scope the SP; rebind both to a custom group.
--   EX1: SELECT count(*) FROM abac_tpcds.abac_gaps.exempt    ->  20 (ALL rows), MEANINGFUL ONLY IF
--        EX2 == 10. The SP is EXCEPTed from exempt_policy, so except_filter does not apply. If EX1
--        returns 10, EXCEPT failed to exempt the SP. If the `account users` TO form is rejected at
--        CREATE POLICY time, see the OPERATOR NOTE above -- record verbatim, do not substitute.
--   DP1: dropped (DEFAULT question answered at DDL; not suite-observable -- see banner).

-- ---- TEARDOWN ----
-- Order matters: policies before functions/tables, then the schema CASCADE.
--   DROP POLICY IF EXISTS exempt_policy   ON TABLE abac_tpcds.abac_gaps.exempt;
--   DROP FUNCTION IF EXISTS abac_tpcds.abac_gaps.except_filter;
--   DROP SCHEMA IF EXISTS abac_tpcds.abac_gaps CASCADE;   -- also removes defparam if you demo'd DP1
