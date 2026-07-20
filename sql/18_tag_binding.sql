-- =====================================================================
-- 18_tag_binding.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- MATCH COLUMNS tag BINDING: does has_tag_value() bind only the column whose tag VALUE matches (not
-- just any column carrying the tag key), what happens when TWO columns carry the SAME tag (the alias
-- binding is ambiguous), and what happens when a MATCH COLUMNS expression matches NO column at all.
--   TG1 = tagval:  two columns (id, other) both carry abac_role, but with DIFFERENT tag values
--                  ('filter' vs 'ignore'). has_tag_value('abac_role','filter') must bind ONLY `id`
--                  (the column whose tag VALUE is 'filter'), not `other`. Discriminating power: id <=
--                  10 of 20 rows -> exactly 10; the SAME predicate mistakenly applied to `other`
--                  (values 10,20,...,200 = id*10) would keep only other <= 10 -> exactly 1 row
--                  (other=10, i.e. id=1). 10 vs 1 is a clean, unambiguous signal that the correct
--                  column was bound.
--   TG2 = dualtag: TWO columns (a, b) carry the IDENTICAL tag (abac_dual='true') -- MATCH COLUMNS
--                  has_tag('abac_dual') AS c is genuinely AMBIGUOUS about which column c refers to.
--                  a = 1..20, b = 21-a (20..1); tag_filter keeps <= 10. Binding `a` keeps rows 1..10;
--                  binding `b` keeps rows where b <= 10, i.e. a in 11..20 -- a DIFFERENT set of 10
--                  rows. Row COUNT alone cannot distinguish the two bindings (10 either way), so the
--                  case selects `a` itself: the observed values reveal which column Databricks
--                  actually bound. UNKNOWN until observed -- ships as INFO; the observed answer
--                  becomes the oracle the e6data planner must later reproduce.
--   TG3 = notag:   MATCH COLUMNS has_tag('abac_nonexistent_tag') matches NO column on `notag` (it
--                  carries no tags at all). The policy is created SUCCESSFULLY -- no DDL error -- but
--                  silently DOES NOT APPLY: querying the table returns ALL rows, unfiltered. This is
--                  the most dangerous failure mode in the whole model: a BROKEN policy fails CLOSED
--                  (errors, or blocks everything); a NON-MATCHING one fails OPEN, with no error at
--                  all telling you it never bound.
--
-- OPERATOR NOTE: if has_tag_value() is rejected at CREATE POLICY time (i.e. not supported at
-- policy-creation), that is a FINDING, not a test bug -- record the exact error verbatim rather than
-- silently working around it (e.g. by substituting has_tag()), and convert TG1 to
-- Expect.errorContains(...) accordingly.
--
-- Creates an ISOLATED schema (abac_tpcds.abac_tags) so these policies cannot reach any main-suite
-- table (abac_tpcds.tpcds_1_delta.*).
--
-- Apply as OWNER / metastore admin. Prerequisites: none beyond catalog abac_tpcds already existing
-- (sql/01). Teardown at the bottom.
-- SP the JDBC suite authenticates as: 76d5804d-d302-4014-a1d3-d846f02c84ef
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_tpcds.abac_tags;

-- TG1: has_tag_value() -- match on a tag's VALUE, not just its presence.
CREATE OR REPLACE TABLE abac_tpcds.abac_tags.tagval (id BIGINT, other BIGINT);
INSERT INTO abac_tpcds.abac_tags.tagval SELECT id, id * 10 FROM range(1, 21);
ALTER TABLE abac_tpcds.abac_tags.tagval ALTER COLUMN id    SET TAGS ('abac_role' = 'filter');
ALTER TABLE abac_tpcds.abac_tags.tagval ALTER COLUMN other SET TAGS ('abac_role' = 'ignore');

CREATE OR REPLACE FUNCTION abac_tpcds.abac_tags.tag_filter(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;

-- Binds ONLY the column whose abac_role tag VALUE equals 'filter' (id) -- NOT `other`, whose
-- abac_role tag value is 'ignore'.
CREATE OR REPLACE POLICY tagval_policy
ON TABLE abac_tpcds.abac_tags.tagval
ROW FILTER abac_tpcds.abac_tags.tag_filter
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag_value('abac_role', 'filter') AS id
USING COLUMNS (id);

-- TG2: TWO columns carrying the SAME tag -- what does the alias bind to?
CREATE OR REPLACE TABLE abac_tpcds.abac_tags.dualtag (a BIGINT, b BIGINT);
INSERT INTO abac_tpcds.abac_tags.dualtag SELECT id, 21 - id FROM range(1, 21);
ALTER TABLE abac_tpcds.abac_tags.dualtag ALTER COLUMN a SET TAGS ('abac_dual' = 'true');
ALTER TABLE abac_tpcds.abac_tags.dualtag ALTER COLUMN b SET TAGS ('abac_dual' = 'true');

CREATE OR REPLACE POLICY dualtag_policy
ON TABLE abac_tpcds.abac_tags.dualtag
ROW FILTER abac_tpcds.abac_tags.tag_filter
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_dual') AS c
USING COLUMNS (c);

-- TG3: a MATCH COLUMNS expression that matches NO column.
CREATE OR REPLACE TABLE abac_tpcds.abac_tags.notag (id BIGINT);
INSERT INTO abac_tpcds.abac_tags.notag SELECT id FROM range(1, 21);

CREATE OR REPLACE POLICY notag_policy
ON TABLE abac_tpcds.abac_tags.notag
ROW FILTER abac_tpcds.abac_tags.tag_filter
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_nonexistent_tag') AS id
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
