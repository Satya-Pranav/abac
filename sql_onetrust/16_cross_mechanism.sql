-- =====================================================================
-- 16_cross_mechanism.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported 1:1 from sql/20_cross_mechanism.sql (TPC-DS), for the OneTrust suite (OT-XT1).
-- CROSS-MECHANISM conflict: a tag-driven ABAC CREATE POLICY row filter AND a classic
-- ALTER TABLE ... SET ROW FILTER row filter, attached to the SAME table.
--
-- DESIGN: deliberately DISJOINT predicates --
--   abac_fn    (ABAC policy)    keeps id <= 10  -> 10 of 20 rows if it alone applies
--   classic_fn (classic filter) keeps id > 15   ->  5 of 20 rows if it alone applies
-- DECODE TABLE for `SELECT count(*) FROM abac_onetrust.abac_xmech.both`:
--   ERROR (UC_ABAC_AND_NATIVE_ROW_FILTERS) => the one-filter-per-table limit spans BOTH mechanisms
--   0     => the two filters were ANDed together (id <= 10 AND id > 15 is empty)
--   10    => the ABAC policy won; classic was ignored
--   5     => the classic filter won; the ABAC policy was ignored
--   20    => NEITHER mechanism applied to this query
-- If a COUNT comes back instead of an error, that is a significant finding, not a test bug --
-- record the observed number and read it against the decode table, rather than treating it as broken.
--
-- CONFIRMED LIVE 2026-07-28: this native-vs-ABAC combination raises a DISTINCT, more specific
-- error class -- UC_ABAC_AND_NATIVE_ROW_FILTERS -- than the two-ABAC-policies case (which raises
-- UC_ABAC_MULTIPLE_ROW_FILTERS, see 08_row_filter_conflict.sql/13_policy_scope.sql). Both share
-- SQLSTATE 42KDJ, but the ErrorClass name differs. The underlying limit (per table, not per
-- mechanism) is confirmed either way.
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_xmech;

CREATE OR REPLACE TABLE abac_onetrust.abac_xmech.both (id BIGINT);
INSERT INTO abac_onetrust.abac_xmech.both SELECT id FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_xmech.both ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE FUNCTION abac_onetrust.abac_xmech.abac_fn(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;                -- ABAC policy predicate: keeps 10 of 20 rows

CREATE OR REPLACE FUNCTION abac_onetrust.abac_xmech.classic_fn(id BIGINT)
RETURNS BOOLEAN RETURN id > 15;                 -- classic RLS predicate: keeps 5 of 20 rows (disjoint)

-- Mechanism 1: the ABAC policy (tag-driven, has_tag() MATCH COLUMNS binding).
CREATE OR REPLACE POLICY xmech_policy
ON TABLE abac_onetrust.abac_xmech.both
ROW FILTER abac_onetrust.abac_xmech.abac_fn
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

-- Mechanism 2: classic table-managed RLS, bound directly to the column (no tag, no policy).
-- >>> If THIS statement errors because xmech_policy already occupies the table's one row-filter
-- slot, that IS the finding this case exists to surface -- record the error verbatim.
ALTER TABLE abac_onetrust.abac_xmech.both SET ROW FILTER abac_onetrust.abac_xmech.classic_fn ON (id);

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_xmech TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_xmech.both TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_xmech.abac_fn    TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_xmech.classic_fn TO `<ONETRUST_SP>`;

-- Expect (as the SP via the suite):
--   OT-XT1: SELECT count(*) FROM abac_onetrust.abac_xmech.both
--           -> ERROR UC_ABAC_AND_NATIVE_ROW_FILTERS (CONFIRMED LIVE 2026-07-28: the per-table limit
--              spans BOTH mechanisms). If instead a COUNT comes back, decode it per the table above.

-- ---- TEARDOWN ----
-- Order matters: drop the classic row filter FIRST, then the ABAC policy, then the schema.
--   ALTER TABLE abac_onetrust.abac_xmech.both DROP ROW FILTER;
--   DROP POLICY IF EXISTS xmech_policy ON TABLE abac_onetrust.abac_xmech.both;
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_xmech CASCADE;
