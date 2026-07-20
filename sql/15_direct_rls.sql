-- =====================================================================
-- 15_direct_rls.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Two contrasting ways to attach a row filter, for the suite's DR1 + DR2 cases:
--   DR1 = CLASSIC RLS on `reason`          -> ALTER TABLE ... SET ROW FILTER; NO tags, NO policy.
--   DR2 = ABAC tag+policy on `income_band` -> has_tag() MATCH COLUMNS policy + wrapper + inner
--         row-filter UDF that the SUITE hot-swaps mid-run: assert -> CREATE OR REPLACE the UDF ->
--         wait 10s -> re-assert -> revert.
--
-- Both `reason` and `income_band` are tiny fixed dimension tables with clean 1..N surrogate keys
-- (reason: r_reason_sk; income_band: ib_income_band_sk = 1..20), and neither is governed by anything
-- else. The DR2 inner UDF (dr2_row_filter) is OWNED BY THE SP so the suite can CREATE OR REPLACE it
-- during the run; the POLICY binds the STABLE wrapper, so the swap never touches an in-use binding.
--
-- Assumes the SP already has EXECUTE on abac.get_user_context / abac.entity_type_to_object_type
-- (granted for the deployed flow in sql/09).
--
-- SP the JDBC suite authenticates as: 76d5804d-d302-4014-a1d3-d846f02c84ef
-- =====================================================================

-- =========================================================
-- DR1 — CLASSIC Row-Level Security on `reason` (NO governance tags, NO CREATE POLICY)
-- =========================================================
CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.rls_reason(k BIGINT)
  RETURNS BOOLEAN RETURN k >= 20;                        -- keep only r_reason_sk >= 20
GRANT EXECUTE ON FUNCTION abac_tpcds.tpcds_1_delta.rls_reason TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT   ON TABLE    abac_tpcds.tpcds_1_delta.reason     TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
-- classic UC row filter: bound DIRECTLY to the column — no has_tag, no CREATE POLICY, no wrapper.
ALTER TABLE abac_tpcds.tpcds_1_delta.reason
  SET ROW FILTER abac_tpcds.tpcds_1_delta.rls_reason ON (r_reason_sk);

-- =========================================================
-- DR2 — ABAC tag + policy on `income_band` (the has_tag() flow; hot-swappable inner UDF)
-- =========================================================
-- inner row-filter UDF (SWAPPABLE, owned by the SP). Original cutoff: ib_income_band_sk <= 10.
-- Same 4-arg shape as the deployed abac_row_filter; here the body just thresholds entity_id and
-- ignores ctx, so assertions are deterministic (income_band = 20 fixed rows -> <=10 shows 10).
CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.dr2_row_filter(
  entity_id STRING, object_type STRING, org_id STRING,
  ctx STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>)
RETURNS BOOLEAN
RETURN try_cast(entity_id AS BIGINT) <= 10;              -- << the suite CREATE OR REPLACEs this cutoff
ALTER FUNCTION abac_tpcds.tpcds_1_delta.dr2_row_filter OWNER TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- stable wrapper the POLICY binds (same shape as the deployed abac_row_filter_wrapper)
CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.dr2_wrapper(
  entity_id STRING, object_type STRING, org_id STRING)
RETURNS BOOLEAN
RETURN abac_tpcds.tpcds_1_delta.dr2_row_filter(
  entity_id,
  abac_tpcds.abac.entity_type_to_object_type(object_type),
  org_id,
  abac_tpcds.abac.get_user_context());

GRANT EXECUTE ON FUNCTION abac_tpcds.tpcds_1_delta.dr2_wrapper    TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT EXECUTE ON FUNCTION abac_tpcds.tpcds_1_delta.dr2_row_filter TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT   ON TABLE    abac_tpcds.tpcds_1_delta.income_band   TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- tag the id column (GOVERNANCE TAG) and bind via the has_tag() MATCH COLUMNS policy form
ALTER TABLE abac_tpcds.tpcds_1_delta.income_band
  ALTER COLUMN ib_income_band_sk SET TAGS ('abac_column_id' = 'true');
CREATE OR REPLACE POLICY income_band_dr2_policy
ON TABLE abac_tpcds.tpcds_1_delta.income_band
ROW FILTER abac_tpcds.tpcds_1_delta.dr2_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'IncomeBand', '100');

-- If the SP cannot CREATE OR REPLACE dr2_row_filter even as its owner, also run (as owner):
--   GRANT CREATE FUNCTION ON SCHEMA abac_tpcds.tpcds_1_delta TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- Expect (as the SP via the suite):
--   DR1 : SELECT count(*) FROM reason WHERE r_reason_sk < 20  ->  0   (classic RLS, no tags)
--   DR2a: SELECT count(*) FROM income_band                    ->  10  (ABAC policy, cutoff <= 10)
--   DR2b: after CREATE OR REPLACE dr2_row_filter (<= 5) + 10s ->  5
--   DR2c: after revert (<= 10)                                ->  10

-- ---- TEARDOWN ----
--   ALTER TABLE abac_tpcds.tpcds_1_delta.reason DROP ROW FILTER;
--   DROP FUNCTION IF EXISTS abac_tpcds.tpcds_1_delta.rls_reason;
--   DROP POLICY IF EXISTS income_band_dr2_policy ON TABLE abac_tpcds.tpcds_1_delta.income_band;
--   ALTER TABLE abac_tpcds.tpcds_1_delta.income_band ALTER COLUMN ib_income_band_sk UNSET TAGS ('abac_column_id');
--   DROP FUNCTION IF EXISTS abac_tpcds.tpcds_1_delta.dr2_wrapper;
--   DROP FUNCTION IF EXISTS abac_tpcds.tpcds_1_delta.dr2_row_filter;
