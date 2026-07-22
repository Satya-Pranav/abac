-- =====================================================================
-- 18_tag_binding.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- MATCH COLUMNS tag BINDING: does has_tag_value() bind only the column whose tag VALUE matches (not
-- just any column carrying the tag key), what happens when TWO columns carry the SAME tag (the alias
-- binding is ambiguous), and what happens when a MATCH COLUMNS expression matches NO column at all.
--   TG1 = tagval:  id carries abac_column_id='true'; other carries a DIFFERENT registered key
--                  (abac_column_tenant='true'). has_tag_value('abac_column_id','true') must bind ONLY
--                  `id` (the column whose tag VALUE is 'filter'), not `other`. Discriminating power:
--                  id <= 10 of 20 rows -> exactly 10; the SAME predicate mistakenly applied to `other`
--                  (values 10,20,...,200 = id*10) would keep only other <= 10 -> exactly 1 row
--                  (other=10, i.e. id=1). 10 vs 1 is a clean, unambiguous signal that the correct
--                  column was bound.
--   TG2 = dualtag: TWO columns (a, b) carry the IDENTICAL tag (abac_column_id='true') -- MATCH
--                  COLUMNS has_tag('abac_column_id') AS c is genuinely AMBIGUOUS about which column c
--                  refers to. a = 1..20, b = 21-a (20..1); tag_filter keeps <= 10. Binding `a` keeps
--                  rows 1..10; binding `b` keeps rows where b <= 10, i.e. a in 11..20 -- a DIFFERENT
--                  set of 10 rows. Row COUNT alone cannot distinguish the two bindings (10 either
--                  way), so the case selects `a` itself: the observed values reveal which column
--                  Databricks actually bound. UNKNOWN until observed -- ships as INFO; the observed
--                  answer becomes the oracle the e6data planner must later reproduce.
--   TG3 = notag:   *** CORRECTED SEMANTIC -- READ THIS BEFORE TOUCHING TG3 ***
--                  An EARLIER draft of this case used a MATCH COLUMNS has_tag(...) expression keyed
--                  on abac_nonexistent_tag, an UNREGISTERED governed-tag key. A live apply of that draft (against
--                  scope_schema_policy in sql/17, same failure mode) proved that an unregistered key
--                  does NOT fail open at query time -- it fails CLOSED at CREATE POLICY (DDL) time,
--                  with UC_INVALID_POLICY_CONDITION / "Unknown tag policy key". See the verbatim
--                  operator error recorded just above the TG3 statements below. That draft therefore
--                  tested the WRONG THING (a DDL-time rejection) while claiming to test a query-time
--                  fail-open. TG3 now uses has_tag('abac_column_org') -- a key that IS registered in
--                  this workspace (see sql/07) -- and `notag` carries NO abac_column_org tag on any
--                  column. That is the genuine test of the intended semantic: a REGISTERED key whose
--                  MATCH COLUMNS expression matches NO column on this table lets CREATE POLICY
--                  SUCCEED, but the policy then silently DOES NOT APPLY -- querying `notag` returns
--                  ALL rows, unfiltered, with no error at all. Contrast the two failure modes:
--                    unregistered key  -> fails CLOSED at CREATE POLICY (DDL) time, loudly
--                    registered key, zero column matches -> fails OPEN at query time, silently
--                  This is the most dangerous failure mode in the whole model: a BROKEN policy fails
--                  CLOSED (errors, or blocks everything); a NON-MATCHING one fails OPEN, with no
--                  error at all telling you it never bound.
--
-- OPERATOR NOTE: if has_tag_value() is rejected at CREATE POLICY time (i.e. not supported at
-- policy-creation), that is a FINDING, not a test bug -- record the exact error verbatim rather than
-- silently working around it (e.g. by substituting has_tag()), and convert TG1 to
-- Expect.errorContains(...) accordingly.
--
-- Creates an ISOLATED schema (abac_tpcds.abac_tags) so these policies cannot reach any main-suite
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

CREATE SCHEMA IF NOT EXISTS abac_tpcds.abac_tags;

-- TG1: has_tag_value() -- match on a tag's VALUE, not just its presence.
CREATE OR REPLACE TABLE abac_tpcds.abac_tags.tagval (id BIGINT, other BIGINT);
INSERT INTO abac_tpcds.abac_tags.tagval SELECT id, id * 10 FROM range(1, 21);
-- OBSERVED 2026-07-22: a governed tag key constrains its ALLOWED VALUES. Setting a value outside
-- that list is rejected:
--   [INVALID_PARAMETER_VALUE] Tag value filter is not an allowed value for tag policy key
--   abac_column_id. Allowed values: [true]
-- All four registered keys (abac_column_id, abac_column_org, abac_column_type, abac_column_tenant)
-- allow only 'true'. So SAME-KEY / DIFFERENT-VALUE discrimination is NOT testable here without
-- first registering a new governed tag key that permits multiple values.
-- What IS testable, and what TG1 now does: has_tag_value() must match on KEY *and* VALUE, binding
-- only the column carrying that pair and ignoring a column tagged with a DIFFERENT registered key.
ALTER TABLE abac_tpcds.abac_tags.tagval ALTER COLUMN id    SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_tpcds.abac_tags.tagval ALTER COLUMN other SET TAGS ('abac_column_tenant' = 'true');

CREATE OR REPLACE FUNCTION abac_tpcds.abac_tags.tag_filter(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;

-- Binds ONLY the column whose abac_column_id tag VALUE equals 'filter' (id) -- NOT `other`, whose
-- abac_column_id tag value is 'ignore'.
CREATE OR REPLACE POLICY tagval_policy
ON TABLE abac_tpcds.abac_tags.tagval
ROW FILTER abac_tpcds.abac_tags.tag_filter
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag_value('abac_column_id', 'true') AS id
USING COLUMNS (id);

-- TG2: TWO columns carrying the SAME tag -- what does the alias bind to?
CREATE OR REPLACE TABLE abac_tpcds.abac_tags.dualtag (a BIGINT, b BIGINT);
INSERT INTO abac_tpcds.abac_tags.dualtag SELECT id, 21 - id FROM range(1, 21);
ALTER TABLE abac_tpcds.abac_tags.dualtag ALTER COLUMN a SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_tpcds.abac_tags.dualtag ALTER COLUMN b SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE POLICY dualtag_policy
ON TABLE abac_tpcds.abac_tags.dualtag
ROW FILTER abac_tpcds.abac_tags.tag_filter
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS c
USING COLUMNS (c);

-- =====================================================================================
-- TG3 -- OPERATOR-OBSERVED ERROR, RECORDED VERBATIM (evidence for the unregistered-key behaviour):
--
--   [RequestId=95dc320e-5ac8-49ff-aaa2-04061d9e6c8b ErrorClass=INVALID_PARAMETER_VALUE.UC_INVALID_POLICY_CONDITION]
--   Invalid condition in policy 'scope_schema_policy'. Compilation error with message
--   'Unknown tag policy key `abac_scope_id`'.
--
-- That error was raised applying sql/17's scope_schema_policy (same failure mode: an UNREGISTERED
-- governed tag key referenced inside has_tag()/has_tag_value() at CREATE POLICY time). TG3 originally
-- keyed its MATCH COLUMNS on an equally unregistered tag, abac_nonexistent_tag, on the mistaken
-- assumption that it would fail OPEN (silently not apply) at query time. It would instead have
-- failed CLOSED here too, at DDL time, exactly like the error above -- never reaching a queryable
-- state. TG3 below is corrected to use abac_column_org (registered, see sql/07) so it actually
-- exercises the intended semantic: a MATCH COLUMNS expression that matches NO column -- CREATE
-- POLICY succeeds, the policy is created, but it never applies.
-- =====================================================================================

-- TG3: a MATCH COLUMNS expression on a REGISTERED tag key that matches NO column on this table.
-- `notag` carries NO abac_column_org tag anywhere (it has only column `id`, tagged with nothing).
CREATE OR REPLACE TABLE abac_tpcds.abac_tags.notag (id BIGINT);
INSERT INTO abac_tpcds.abac_tags.notag SELECT id FROM range(1, 21);

CREATE OR REPLACE POLICY notag_policy
ON TABLE abac_tpcds.abac_tags.notag
ROW FILTER abac_tpcds.abac_tags.tag_filter
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_org') AS id
USING COLUMNS (id);

GRANT USE SCHEMA ON SCHEMA abac_tpcds.abac_tags TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.abac_tags.tagval  TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.abac_tags.dualtag TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.abac_tags.notag   TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac_tags.tag_filter TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- Expect (as the SP via the suite):
--   TG1: SELECT count(*) FROM tagval             ->  10  (has_tag_value binds `id`; a wrong binding
--        to `other` would give exactly 1 row instead -- a clean, discriminable signal)
--   TG2: SELECT a FROM dualtag ORDER BY a         ->  UNKNOWN -- record which 10 ids come back
--        (1..10 => `a` was bound; 11..20 => `b` was bound), or whether Databricks instead errors on
--        the ambiguous binding. INFO first; convert to Expect.exactIds(...) once observed.
--   TG3: SELECT count(*) FROM notag               ->  20  (no matching column -> policy silently
--        does not apply -> ALL rows; fails OPEN, not closed)

-- ---- TEARDOWN ----
-- DROP POLICY IF EXISTS tagval_policy  ON TABLE abac_tpcds.abac_tags.tagval;
-- DROP POLICY IF EXISTS dualtag_policy ON TABLE abac_tpcds.abac_tags.dualtag;
-- DROP POLICY IF EXISTS notag_policy   ON TABLE abac_tpcds.abac_tags.notag;
-- DROP SCHEMA IF EXISTS abac_tpcds.abac_tags CASCADE;
