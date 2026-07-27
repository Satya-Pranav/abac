-- =====================================================================
-- 11_direct_rls_and_dr2.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported from sql/15_direct_rls.sql (TPC-DS), for the OneTrust suite (OT-DR1 + the
-- OnetrustDr2HotSwap scenario, Task 18). Two contrasting ways to attach a row filter, on
-- isolated tables (abac_onetrust.abac_rls):
--   DR1 = CLASSIC RLS on rls_demo          -> ALTER TABLE ... SET ROW FILTER; NO tags, NO policy.
--   DR2 = ABAC tag+policy on dr2_demo      -> has_tag() MATCH COLUMNS policy + wrapper + inner
--         row-filter UDF that OnetrustDr2HotSwap hot-swaps mid-run: assert -> CREATE OR REPLACE
--         the UDF -> poll until reflected -> re-assert -> revert.
--
-- Both are tiny fixed dimension tables with clean 1..20 surrogate keys, neither governed by
-- anything else. dr2_row_filter is OWNED BY THE SP so the suite can CREATE OR REPLACE it during
-- the run; the POLICY binds the STABLE wrapper, so the swap never touches an in-use binding.
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_rls;

-- =========================================================
-- DR1 -- CLASSIC Row-Level Security on rls_demo (NO governance tags, NO CREATE POLICY)
-- =========================================================
CREATE OR REPLACE TABLE abac_onetrust.abac_rls.rls_demo (id BIGINT);
INSERT INTO abac_onetrust.abac_rls.rls_demo SELECT id FROM range(1, 21);

CREATE OR REPLACE FUNCTION abac_onetrust.abac_rls.rls_demo_filter(k BIGINT)
  RETURNS BOOLEAN RETURN k >= 10;                        -- keep only id >= 10 (11 of 20 rows)
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_rls.rls_demo_filter TO `<ONETRUST_SP>`;
GRANT SELECT   ON TABLE    abac_onetrust.abac_rls.rls_demo       TO `<ONETRUST_SP>`;
GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_rls                TO `<ONETRUST_SP>`;
-- classic UC row filter: bound DIRECTLY to the column -- no has_tag, no CREATE POLICY, no wrapper.
ALTER TABLE abac_onetrust.abac_rls.rls_demo
  SET ROW FILTER abac_onetrust.abac_rls.rls_demo_filter ON (id);

-- =========================================================
-- DR2 -- ABAC tag + policy on dr2_demo (the has_tag() flow; hot-swappable inner UDF)
-- =========================================================
CREATE OR REPLACE TABLE abac_onetrust.abac_rls.dr2_demo (id BIGINT);
INSERT INTO abac_onetrust.abac_rls.dr2_demo SELECT id FROM range(1, 21);

-- inner row-filter UDF (SWAPPABLE, owned by the SP). Original cutoff: id <= 10.
CREATE OR REPLACE FUNCTION abac_onetrust.abac_rls.dr2_row_filter(
  entity_id STRING, object_type STRING, org_id STRING,
  ctx STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>)
RETURNS BOOLEAN
RETURN try_cast(entity_id AS BIGINT) <= 10;              -- << OnetrustDr2HotSwap CREATE OR REPLACEs this cutoff
ALTER FUNCTION abac_onetrust.abac_rls.dr2_row_filter OWNER TO `<ONETRUST_SP>`;

-- stable wrapper the POLICY binds (same shape as the deployed abac_row_filter_wrapper_oauth)
CREATE OR REPLACE FUNCTION abac_onetrust.abac_rls.dr2_wrapper(
  entity_id STRING, object_type STRING, org_id STRING)
RETURNS BOOLEAN
RETURN abac_onetrust.abac_rls.dr2_row_filter(
  entity_id,
  abac_onetrust.onetrust_sim.entity_type_to_object_type(object_type),
  org_id,
  abac_onetrust.onetrust_sim.get_user_context());

GRANT EXECUTE ON FUNCTION abac_onetrust.abac_rls.dr2_wrapper     TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_rls.dr2_row_filter  TO `<ONETRUST_SP>`;
GRANT SELECT   ON TABLE    abac_onetrust.abac_rls.dr2_demo       TO `<ONETRUST_SP>`;

ALTER TABLE abac_onetrust.abac_rls.dr2_demo ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
CREATE OR REPLACE POLICY dr2_demo_policy
ON TABLE abac_onetrust.abac_rls.dr2_demo
ROW FILTER abac_onetrust.abac_rls.dr2_wrapper
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'DR2_DEMO', '100');

-- If the SP cannot CREATE OR REPLACE dr2_row_filter even as its owner, also run (as owner):
--   GRANT CREATE FUNCTION ON SCHEMA abac_onetrust.abac_rls TO `<ONETRUST_SP>`;

-- Expect (as the SP via the suite):
--   OT-DR1 : SELECT count(*) FROM rls_demo WHERE id < 10  ->  0   (classic RLS, no tags)
--   DR2a (OnetrustDr2HotSwap start) : SELECT count(*) FROM dr2_demo             -> 10 (cutoff <= 10)
--   DR2b (after CREATE OR REPLACE dr2_row_filter <= 5 + poll) : SELECT count(*) -> 5
--   DR2c (after revert to <= 10)                               : SELECT count(*) -> 10

-- ---- TEARDOWN ----
--   ALTER TABLE abac_onetrust.abac_rls.rls_demo DROP ROW FILTER;
--   DROP POLICY IF EXISTS dr2_demo_policy ON TABLE abac_onetrust.abac_rls.dr2_demo;
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_rls CASCADE;
