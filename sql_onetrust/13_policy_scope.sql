-- =====================================================================
-- 13_policy_scope.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported 1:1 from sql/17_policy_scope.sql (TPC-DS), for the OneTrust suite (OT-SC1..OT-SC4).
-- Policy SCOPE: does an ON SCHEMA policy govern every table beneath it, what happens to a table
-- with no matching tag, and what happens when a schema-level and a table-level row filter both
-- target the SAME table.
--   SC1 = scoped_a:    a table governed by a SCHEMA-level policy (ON SCHEMA, not ON TABLE).
--   SC2 = scoped_c:    a THIRD table in the same schema, same governance tag, covered by NOTHING
--                      but the schema-level policy -> proves schema scope governs every matching
--                      member, not just the first.
--   SC3 = ungoverned:  sits inside the policy's ON SCHEMA scope but has NO abac_column_id tag ->
--                      MATCH COLUMNS matches nothing -> policy silently does not apply -> ALL rows
--                      visible. The dangerous case: a BROKEN policy fails CLOSED; a NON-MATCHING
--                      one fails OPEN, with no error at all.
--   SC4 = scoped_b:    carries BOTH the schema-level policy AND a second, TABLE-level policy --
--                      NOT a precedence contest. Both CREATE POLICY succeed; the conflict is only
--                      detected when the table is QUERIED (UC_ABAC_MULTIPLE_ROW_FILTERS, 42KDJ).
--
-- Uses only the already-registered governed tag keys abac_column_id (see sql_onetrust/02_tags.sql).
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_scope;

CREATE OR REPLACE TABLE abac_onetrust.abac_scope.scoped_a (id BIGINT, label STRING);
INSERT INTO abac_onetrust.abac_scope.scoped_a SELECT id, concat('row-', id) FROM range(1, 21);

CREATE OR REPLACE TABLE abac_onetrust.abac_scope.scoped_b (id BIGINT, label STRING);
INSERT INTO abac_onetrust.abac_scope.scoped_b SELECT id, concat('row-', id) FROM range(1, 21);

CREATE OR REPLACE TABLE abac_onetrust.abac_scope.scoped_c (id BIGINT, label STRING);
INSERT INTO abac_onetrust.abac_scope.scoped_c SELECT id, concat('row-', id) FROM range(1, 21);

CREATE OR REPLACE TABLE abac_onetrust.abac_scope.ungoverned (id BIGINT);
INSERT INTO abac_onetrust.abac_scope.ungoverned SELECT id FROM range(1, 21);

ALTER TABLE abac_onetrust.abac_scope.scoped_a ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.abac_scope.scoped_b ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.abac_scope.scoped_c ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE FUNCTION abac_onetrust.abac_scope.scope_filter(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;

CREATE OR REPLACE POLICY scope_schema_policy
ON SCHEMA abac_onetrust.abac_scope
ROW FILTER abac_onetrust.abac_scope.scope_filter
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_scope TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_scope.scoped_a   TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_scope.scoped_b   TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_scope.scoped_c   TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_scope.ungoverned TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_scope.scope_filter TO `<ONETRUST_SP>`;

-- SC4: add a TABLE-level policy on top of the schema-level one, on scoped_b ONLY.
CREATE OR REPLACE FUNCTION abac_onetrust.abac_scope.scope_filter_tbl(id BIGINT)
RETURNS BOOLEAN RETURN id <= 5;

CREATE OR REPLACE POLICY scope_table_policy
ON TABLE abac_onetrust.abac_scope.scoped_b
ROW FILTER abac_onetrust.abac_scope.scope_filter_tbl
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

GRANT EXECUTE ON FUNCTION abac_onetrust.abac_scope.scope_filter_tbl TO `<ONETRUST_SP>`;

-- Expect (as the SP via the suite):
--   OT-SC1: SELECT count(*) FROM scoped_a               ->  10  (schema policy, id <= 10 of 20)
--   OT-SC2: SELECT count(*) FROM scoped_c WHERE id > 10  ->  0   (schema policy also governs this
--           THIRD, otherwise-unrelated table)
--   OT-SC3: SELECT count(*) FROM ungoverned              ->  20  (no tag -> fails OPEN, ALL rows)
--   OT-SC4: SELECT count(*) FROM scoped_b                ->  ERROR UC_ABAC_MULTIPLE_ROW_FILTERS

-- ---- TEARDOWN ----
--   DROP POLICY IF EXISTS scope_table_policy ON TABLE abac_onetrust.abac_scope.scoped_b;
--   DROP POLICY IF EXISTS scope_schema_policy ON SCHEMA abac_onetrust.abac_scope;
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_scope CASCADE;
