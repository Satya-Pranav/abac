-- =====================================================================
-- 21_except_and_defaults.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
--
-- ############################################################################
-- ##   STILL UNVERIFIED -- RUN THIS SEPARATELY. DO NOT SKIP THIS SECTION.   ##
-- ############################################################################
--
-- THE ARITY CHECK (sql/19's UC1) HAS NEVER BEEN EXECUTED.
--
-- sql/19 contains a commented-out `arity_policy` block: a row-filter UDF (two_param) declaring
-- TWO params, bound via a policy whose USING COLUMNS supplies only ONE (id). It is EXPECTED to
-- be REJECTED by CREATE POLICY with an arity-mismatch error -- because a row filter auto-supplies
-- NO argument of its own, unlike a column mask's ON COLUMN (see sql/19's header and Cases.java's
-- UC comment). But that expectation is a HYPOTHESIS, not a confirmed fact: nobody has run it.
--
-- The suite's own service principal CANNOT test this -- issuing CREATE POLICY requires
-- owner / metastore-admin privileges the SP does not have -- so there is no suite case for it,
-- and there never can be one with this SP's grants. This file does NOT close that gap. It is
-- closed ONLY by an operator running these 4 steps, BY THEMSELVES, separately from any scripted
-- apply of this file or of sql/19:
--
--   1. Apply everything ELSE first (this whole file end-to-end, and sql/19 as already applied
--      with its UC1 block still commented out). That part is safe and idempotent.
--   2. Uncomment ONLY the `CREATE OR REPLACE POLICY arity_policy ...` statement in sql/19 (under
--      the header "UC1 -- DO NOT UNCOMMENT AS PART OF A NORMAL APPLY OF THIS SCRIPT") and run
--      that ONE statement ALONE, in isolation from the rest of sql/19.
--   3. Record the EXACT error text VERBATIM -- the full message AND the SQLSTATE -- in
--      docs/testing/jdbc-cases.md. That verbatim error, not a row count, IS the finding.
--   4. Re-comment the block IMMEDIATELY afterward, so any future re-apply of sql/19 never
--      re-attempts the doomed CREATE POLICY.
--
-- Until an operator does this, "a row filter demands all N declared params, no partial binding
-- is possible" remains an assumption carried by DP1 below (and by UC2's writeup in sql/19), not
-- a verified fact. DP1 in this file is a DIFFERENT, related, and separately-unverified question
-- (does a DEFAULT on the omitted param change the outcome?) -- it does NOT substitute for
-- running sql/19's arity check.
-- ############################################################################
--
-- This file closes two further functional-coverage gaps, neither of which touches sql/19's UDF:
--   EX1 = EXCEPT:  does `CREATE POLICY ... TO <principal> EXCEPT <principal>` actually exempt the
--                  excepted principal from the row filter it would otherwise be subject to?
--   DP1 = DEFAULT: does a row filter honour a DEFAULT value for a UDF parameter omitted from
--                  USING COLUMNS, or does Databricks still demand all N declared params
--                  regardless of the DEFAULT?
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
-- DP1 -- DEFAULT UDF parameters: honoured, or still demanded in full?
-- =========================================================
-- 20 fixed rows (id 1..20). A SEPARATE table from `exempt` (its own policy binds the SP directly
-- via TO, not TO ... EXCEPT the SP, so the SP IS subject to it -- needed to observe whether
-- cutoff's DEFAULT is honoured at all).
CREATE OR REPLACE TABLE abac_tpcds.abac_gaps.defparam (id BIGINT);
INSERT INTO abac_tpcds.abac_gaps.defparam SELECT id FROM range(1, 21);
ALTER TABLE abac_tpcds.abac_gaps.defparam ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

-- TWO params; the SECOND has a DEFAULT. USING COLUMNS below supplies ONLY (id).
CREATE OR REPLACE FUNCTION abac_tpcds.abac_gaps.def_filter(id BIGINT, cutoff BIGINT DEFAULT 10)
RETURNS BOOLEAN RETURN id <= cutoff;

-- UNKNOWN until observed: a row filter auto-supplies NO argument of its own (see sql/19's UC1/UC2
-- writeup and the STILL-UNVERIFIED section above), so Databricks may still demand BOTH declared
-- params and REJECT this CREATE POLICY outright (the same arity-mismatch failure UC1
-- hypothesizes), or it may honour cutoff's DEFAULT and accept the policy with cutoff=10. If this
-- statement itself errors, THAT is DP1's finding -- record the verbatim error + SQLSTATE. Do NOT
-- add `cutoff` to USING COLUMNS to force it to succeed; that would test a different (explicit,
-- non-default) binding, not this one.
CREATE OR REPLACE POLICY defparam_policy
ON TABLE abac_tpcds.abac_gaps.defparam
ROW FILTER abac_tpcds.abac_gaps.def_filter
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

GRANT SELECT  ON TABLE    abac_tpcds.abac_gaps.defparam    TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac_gaps.def_filter  TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- Expect (as the SP via the suite):
--   EX1: SELECT count(*) FROM abac_tpcds.abac_gaps.exempt    ->  20 (ALL rows). The SP is
--        EXCEPTed from exempt_policy, so it is NOT subject to except_filter at all. If EX1
--        instead returns 10, EXCEPT failed to exempt the SP -- a significant finding, not a test
--        bug. If the `account users` TO form itself is rejected at CREATE POLICY time, see the
--        OPERATOR NOTE above -- record and report that verbatim, do not substitute.
--   DP1: SELECT count(*) FROM abac_tpcds.abac_gaps.defparam  ->  INFO. Either 10 (the DEFAULT was
--        honoured, cutoff=10 applied) or a CREATE POLICY / query ERROR (all N params still
--        required regardless of the DEFAULT). DO NOT guess a number -- ship as Expect.info() and
--        convert to a hard assertion (Expect.exact(10) or Expect.errorContains(...)) once
--        observed, same pattern as TG2/UC2.

-- ---- TEARDOWN ----
-- Order matters: policies before functions/tables, then the schema CASCADE.
--   DROP POLICY IF EXISTS exempt_policy   ON TABLE abac_tpcds.abac_gaps.exempt;
--   DROP POLICY IF EXISTS defparam_policy ON TABLE abac_tpcds.abac_gaps.defparam;
--   DROP FUNCTION IF EXISTS abac_tpcds.abac_gaps.except_filter;
--   DROP FUNCTION IF EXISTS abac_tpcds.abac_gaps.def_filter;
--   DROP SCHEMA IF EXISTS abac_tpcds.abac_gaps CASCADE;
