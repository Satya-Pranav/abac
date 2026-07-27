-- =====================================================================
-- 14_tag_binding.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported 1:1 from sql/18_tag_binding.sql (TPC-DS), for the OneTrust suite (OT-TG1..OT-TG3).
-- MATCH COLUMNS tag BINDING: does has_tag_value() bind only the column whose tag VALUE matches,
-- what happens when TWO columns carry the SAME tag, and what happens when a MATCH COLUMNS
-- expression matches NO column at all.
--   TG1 = tagval:  id carries abac_column_id='filter'; other carries abac_column_id='ignore'.
--                  has_tag_value('abac_column_id','filter') must bind ONLY id.
--   TG2 = dualtag: TWO columns (a, b) carry the IDENTICAL tag -- genuinely ambiguous. Ships as
--                  INFO until observed (see TPC-DS's own TG2 finding: Databricks refuses to bind,
--                  it does not pick the first column -- UC_ABAC_AMBIGUOUS_COLUMN_MATCH).
--   TG3 = notag:   a REGISTERED tag key (abac_column_org) whose MATCH COLUMNS matches NO column on
--                  this table -- CREATE POLICY succeeds, but the policy silently never applies.
--
-- PREREQUISITE: abac_column_id's allowed values must already include 'filter'/'ignore' (governed
-- tag keys are workspace-level, shared across catalogs -- already true if TPC-DS's own sql/18 was
-- applied; if not, add them first via Settings > Catalog > Governed tags).
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_tags;

-- TG1: has_tag_value() -- match on a tag's VALUE, not just its presence.
CREATE OR REPLACE TABLE abac_onetrust.abac_tags.tagval (id BIGINT, other BIGINT);
INSERT INTO abac_onetrust.abac_tags.tagval SELECT id, id * 10 FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_tags.tagval ALTER COLUMN id    SET TAGS ('abac_column_id' = 'filter');
ALTER TABLE abac_onetrust.abac_tags.tagval ALTER COLUMN other SET TAGS ('abac_column_id' = 'ignore');

CREATE OR REPLACE FUNCTION abac_onetrust.abac_tags.tag_filter(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;

CREATE OR REPLACE POLICY tagval_policy
ON TABLE abac_onetrust.abac_tags.tagval
ROW FILTER abac_onetrust.abac_tags.tag_filter
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag_value('abac_column_id', 'filter') AS id
USING COLUMNS (id);

-- TG2: TWO columns carrying the SAME tag -- what does the alias bind to?
CREATE OR REPLACE TABLE abac_onetrust.abac_tags.dualtag (a BIGINT, b BIGINT);
INSERT INTO abac_onetrust.abac_tags.dualtag SELECT id, 21 - id FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_tags.dualtag ALTER COLUMN a SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.abac_tags.dualtag ALTER COLUMN b SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE POLICY dualtag_policy
ON TABLE abac_onetrust.abac_tags.dualtag
ROW FILTER abac_onetrust.abac_tags.tag_filter
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS c
USING COLUMNS (c);

-- TG3: a MATCH COLUMNS expression on a REGISTERED tag key that matches NO column on this table.
CREATE OR REPLACE TABLE abac_onetrust.abac_tags.notag (id BIGINT);
INSERT INTO abac_onetrust.abac_tags.notag SELECT id FROM range(1, 21);

CREATE OR REPLACE POLICY notag_policy
ON TABLE abac_onetrust.abac_tags.notag
ROW FILTER abac_onetrust.abac_tags.tag_filter
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_org') AS id
USING COLUMNS (id);

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_tags TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_tags.tagval  TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_tags.dualtag TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_tags.notag   TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_tags.tag_filter TO `<ONETRUST_SP>`;

-- Expect (as the SP via the suite):
--   OT-TG1: SELECT count(*) FROM tagval             ->  10  (has_tag_value binds id; a wrong
--           binding to `other` would give exactly 1 row instead)
--   OT-TG2: SELECT a FROM dualtag ORDER BY a         ->  INFO (record the observed ids/error;
--           TPC-DS's own TG2 observed Databricks REFUSES to bind -- UC_ABAC_AMBIGUOUS_COLUMN_MATCH
--           -- convert to Expect.errorContains(...) if OneTrust confirms the same)
--   OT-TG3: SELECT count(*) FROM notag               ->  20  (no matching column -> fails OPEN)

-- ---- TEARDOWN ----
--   DROP POLICY IF EXISTS tagval_policy  ON TABLE abac_onetrust.abac_tags.tagval;
--   DROP POLICY IF EXISTS dualtag_policy ON TABLE abac_onetrust.abac_tags.dualtag;
--   DROP POLICY IF EXISTS notag_policy   ON TABLE abac_onetrust.abac_tags.notag;
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_tags CASCADE;
