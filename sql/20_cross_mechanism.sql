-- =====================================================================
-- 20_cross_mechanism.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- CROSS-MECHANISM conflict: a tag-driven ABAC `CREATE POLICY` row filter AND a classic
-- table-managed `ALTER TABLE ... SET ROW FILTER` row filter, attached to the SAME table.
--
-- Every other row-filter-conflict case in this suite (W1, WP1/WP2, WS1 in sql/12; SC4 in
-- sql/17) proves the one-row-filter-per-table limit WITHIN a single mechanism — two ABAC
-- policies, or a schema-level policy plus a table-level policy. This script asks the question
-- those cases cannot: does the same per-table budget span BOTH attachment mechanisms, or is
-- classic RLS tracked separately from ABAC policies (so one table could carry one of each)?
--
-- DESIGN: the two predicates are made DELIBERATELY DISJOINT --
--   abac_fn    (the ABAC policy)   keeps id <= 10   -> 10 of 20 rows if it alone applies
--   classic_fn (the classic filter) keeps id > 15   ->  5 of 20 rows if it alone applies
-- Disjoint predicates turn every possible non-error outcome into a diagnostic signal instead
-- of an ambiguous number. DECODE TABLE for `SELECT count(*) FROM abac_tpcds.abac_xmech.both`:
--   ERROR (UC_ABAC_MULTIPLE_ROW_FILTERS) => the one-filter-per-table limit spans BOTH
--                                           mechanisms (the expected / hypothesised result)
--   0                                    => the two filters were ANDed together
--                                           (id <= 10 AND id > 15 is empty)
--   10                                   => the ABAC policy won; classic was ignored
--   5                                    => the classic filter won; the ABAC policy was ignored
--   20 (unfiltered)                      => NEITHER mechanism applied to this query
-- If a COUNT comes back instead of an error, that is a significant finding, not a test bug:
-- record the observed number and read it against the decode table above rather than treating
-- it as a broken case.
--
-- Row arithmetic check: seeded via `range(1, 21)` -> ids 1..20 (20 rows total).
--   id <= 10 matches {1..10}  -> 10 rows.
--   id > 15  matches {16..20} ->  5 rows.
-- The two sets do not overlap, so an AND of both predicates yields 0, matching the decode table.
--
-- Creates an ISOLATED schema (abac_tpcds.abac_xmech) so neither mechanism can reach any
-- main-suite table (abac_tpcds.tpcds_1_delta.*).
--
-- Apply as OWNER / metastore admin. Prerequisites: none beyond catalog abac_tpcds already
-- existing (sql/01). Teardown at the bottom.
-- SP the JDBC suite authenticates as: 76d5804d-d302-4014-a1d3-d846f02c84ef
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_tpcds.abac_xmech;

CREATE OR REPLACE TABLE abac_tpcds.abac_xmech.both (id BIGINT);
INSERT INTO abac_tpcds.abac_xmech.both SELECT id FROM range(1, 21);
ALTER TABLE abac_tpcds.abac_xmech.both ALTER COLUMN id SET TAGS ('abac_xmech_id' = 'true');

CREATE OR REPLACE FUNCTION abac_tpcds.abac_xmech.abac_fn(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;                -- ABAC policy predicate: keeps 10 of 20 rows

CREATE OR REPLACE FUNCTION abac_tpcds.abac_xmech.classic_fn(id BIGINT)
RETURNS BOOLEAN RETURN id > 15;                 -- classic RLS predicate: keeps 5 of 20 rows (disjoint)

-- Mechanism 1: the ABAC policy (tag-driven, has_tag() MATCH COLUMNS binding).
CREATE OR REPLACE POLICY xmech_policy
ON TABLE abac_tpcds.abac_xmech.both
ROW FILTER abac_tpcds.abac_xmech.abac_fn
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_xmech_id') AS id
USING COLUMNS (id);

-- Mechanism 2: classic table-managed RLS, bound directly to the column (no tag, no policy).
-- >>> If THIS statement errors because xmech_policy already occupies the table's one row-filter
-- slot, that IS the finding this case exists to surface -- record the error verbatim.
ALTER TABLE abac_tpcds.abac_xmech.both SET ROW FILTER abac_tpcds.abac_xmech.classic_fn ON (id);

GRANT USE SCHEMA ON SCHEMA abac_tpcds.abac_xmech TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.abac_xmech.both TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac_xmech.abac_fn    TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac_xmech.classic_fn TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- Expect (as the SP via the suite):
--   XT1: SELECT count(*) FROM abac_tpcds.abac_xmech.both
--        -> ERROR UC_ABAC_MULTIPLE_ROW_FILTERS (SQLSTATE 42KDJ) -- the expected / hypothesised
--           result: the per-table row-filter limit spans BOTH mechanisms, not just ABAC-vs-ABAC.
--        -> if instead a COUNT is returned, decode it per the table at the top of this file:
--             0  => ANDed together   |  10 => ABAC won  |  5 => classic won  |  20 => neither applied

-- ---- TEARDOWN ----
-- Order matters: drop the classic row filter FIRST, then the ABAC policy, then the schema.
-- Dropping the schema while a row filter (of either mechanism) is still attached to a table in
-- it can fail, so both filters must be detached before the CASCADE drop runs.
--   ALTER TABLE abac_tpcds.abac_xmech.both DROP ROW FILTER;
--   DROP POLICY IF EXISTS xmech_policy ON TABLE abac_tpcds.abac_xmech.both;
--   DROP SCHEMA IF EXISTS abac_tpcds.abac_xmech CASCADE;
