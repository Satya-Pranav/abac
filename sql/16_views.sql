-- =====================================================================
-- 16_views.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Does a row filter propagate through a VIEW, or is a view a bypass?
--   V1/V3 = a view over `reason`, which already carries CLASSIC RLS (rls_reason: r_reason_sk >= 20)
--           from sql/15. No new policy needed here — only a new view.
--   V2    = a view over `income_band`, which already carries the ABAC has_tag() policy
--           (income_band_dr2_policy / dr2_row_filter, cutoff ib_income_band_sk <= 10, of 20 fixed
--           rows) from sql/15.
--
-- NOTE ON NAMING: an earlier draft of this script called the V2 view "v_ungoverned". That name is
-- wrong: income_band IS governed (it carries the DR2 ABAC has_tag() policy from sql/15) — that
-- governance propagating through the view is exactly what V2 asserts (10 of 20 rows). The view
-- below is named v_income_band_governed to say what it actually is.
--
-- Prerequisites: sql/15_direct_rls.sql must already be applied —
--   `reason` needs its classic ROW FILTER (rls_reason, r_reason_sk >= 20) and `income_band` needs
--   its ABAC policy (income_band_dr2_policy, cutoff <= 10) already bound. Without sql/15, V1-V3
--   would just measure the two UNFILTERED base tables through a pass-through view.
--
-- SP the JDBC suite authenticates as: 76d5804d-d302-4014-a1d3-d846f02c84ef
-- =====================================================================

-- V1/V3: a view over a GOVERNED base table (classic RLS on `reason`, from sql/15).
CREATE OR REPLACE VIEW abac_tpcds.tpcds_1_delta.v_reason_governed AS
SELECT r_reason_sk, r_reason_desc FROM abac_tpcds.tpcds_1_delta.reason;

-- V2: a view over `income_band`, governed by the ABAC has_tag() policy from sql/15
-- (income_band_dr2_policy, cutoff ib_income_band_sk <= 10).
CREATE OR REPLACE VIEW abac_tpcds.tpcds_1_delta.v_income_band_governed AS
SELECT ib_income_band_sk FROM abac_tpcds.tpcds_1_delta.income_band;

GRANT SELECT ON VIEW abac_tpcds.tpcds_1_delta.v_reason_governed       TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON VIEW abac_tpcds.tpcds_1_delta.v_income_band_governed  TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- Expect (as the SP via the suite):
--   V1: SELECT count(*) FROM v_reason_governed WHERE r_reason_sk < 20  ->  0   (classic RLS
--       keeps only r_reason_sk >= 20; the view must not bypass it)
--   V2: SELECT count(*) FROM v_income_band_governed                    ->  10  (ABAC policy keeps
--       only ib_income_band_sk <= 10, of 20 fixed rows; the view must not bypass it)
--   V3: SELECT min(r_reason_sk) FROM v_reason_governed                 ->  >= 20 (an aggregate
--       through the view must not reveal the existence of filtered-out rows)

-- ---- TEARDOWN ----
--   DROP VIEW IF EXISTS abac_tpcds.tpcds_1_delta.v_reason_governed;
--   DROP VIEW IF EXISTS abac_tpcds.tpcds_1_delta.v_income_band_governed;
