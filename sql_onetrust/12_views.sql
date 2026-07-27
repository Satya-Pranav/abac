-- =====================================================================
-- 12_views.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported from sql/16_views.sql (TPC-DS), for the OneTrust suite (OT-V1..OT-V3).
-- Does a row filter propagate through a VIEW, or is a view a bypass?
--   V1/V3 = a view over rls_demo, which already carries CLASSIC RLS (id >= 10) from
--           sql_onetrust/11_direct_rls_and_dr2.sql. No new policy needed here -- only a new view.
--   V2    = a view over dr2_demo, which already carries the ABAC has_tag() policy (cutoff id <= 10,
--           of 20 fixed rows) from sql_onetrust/11_direct_rls_and_dr2.sql.
--
-- PREREQUISITE: sql_onetrust/11_direct_rls_and_dr2.sql must already be applied.
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

-- V1/V3: a view over a GOVERNED base table (classic RLS on rls_demo).
CREATE OR REPLACE VIEW abac_onetrust.abac_rls.v_rls_demo_governed AS
SELECT id FROM abac_onetrust.abac_rls.rls_demo;

-- V2: a view over dr2_demo, governed by the ABAC has_tag() policy (cutoff id <= 10).
CREATE OR REPLACE VIEW abac_onetrust.abac_rls.v_dr2_demo_governed AS
SELECT id FROM abac_onetrust.abac_rls.dr2_demo;

GRANT SELECT ON VIEW abac_onetrust.abac_rls.v_rls_demo_governed  TO `<ONETRUST_SP>`;
GRANT SELECT ON VIEW abac_onetrust.abac_rls.v_dr2_demo_governed  TO `<ONETRUST_SP>`;

-- Expect (as the SP via the suite):
--   OT-V1: SELECT count(*) FROM v_rls_demo_governed WHERE id < 10  ->  0   (classic RLS keeps only
--          id >= 10; the view must not bypass it)
--   OT-V2: SELECT count(*) FROM v_dr2_demo_governed                ->  10  (ABAC policy keeps only
--          id <= 10, of 20 fixed rows; the view must not bypass it)
--   OT-V3: SELECT min(id) FROM v_rls_demo_governed                 ->  >= 10 (an aggregate through
--          the view must not reveal the existence of filtered-out rows)

-- ---- TEARDOWN ----
--   DROP VIEW IF EXISTS abac_onetrust.abac_rls.v_rls_demo_governed;
--   DROP VIEW IF EXISTS abac_onetrust.abac_rls.v_dr2_demo_governed;
