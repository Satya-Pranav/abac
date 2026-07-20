-- =====================================================================
-- 10_live_validation.sql
-- OAUTH PHASE: filtering is driven by the token's custom_claim, not by "who you log
-- in as". Validate through the SERVICE PRINCIPAL with claim.user = a dummy email —
-- via the JDBC client or curl SQL Statements API (full catalog: JDBC_CASES.md).
-- Owners/metastore admins BYPASS row filters, so running these AS yourself in the UI
-- shows everything; to see filtering in the UI, call the filter directly (sql/11).
--
-- The count queries are identity-agnostic — run them in whatever claim you are testing
-- and compare to the expected result for that claim.
-- =====================================================================

SELECT current_user();  -- returns the SERVICE PRINCIPAL app id when run via the SP session

-- Expected for claim {"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer"}
-- against the deployed 2-branch filter:
SELECT count(*) AS customer_rows    FROM abac_tpcds.tpcds_1_delta.customer;     -- 1  (entity 2012)
SELECT count(*) AS item_rows        FROM abac_tpcds.tpcds_1_delta.item;         -- 0  (non-root; 2-branch has no permissions branch)
SELECT count(*) AS store_sales_rows FROM abac_tpcds.tpcds_1_delta.store_sales;  -- 0  (non-root)
SELECT count(*) AS store_rows       FROM abac_tpcds.tpcds_1_delta.store;        -- 0
SELECT count(*) AS warehouse_rows   FROM abac_tpcds.tpcds_1_delta.warehouse;    -- 0

-- A join still works, with each side filtered by its own policy:
SELECT c.c_customer_sk, ss.ss_item_sk
FROM abac_tpcds.tpcds_1_delta.customer c
JOIN abac_tpcds.tpcds_1_delta.store_sales ss ON ss.ss_customer_sk = c.c_customer_sk
LIMIT 20;

-- =====================================================================
-- Scenario switching under OAuth: change the CLAIM, not a table. Re-run the counts
-- with a new claim (JDBC_CASES.md groups A / B-perm / B-rbac):
--   DISABLE (all rows):        {"...","mode":"DISABLE",...}
--   Item tester:               {"user":"u.vendor.mgr@example.com","root":"Item",...}      -> item -> 1 (entity 3006)
--   StoreSale tester:          {"user":"u.developer@example.com","root":"StoreSale",...}  -> store_sales -> ss_customer_sk=118144
--   Coarse related tables:     add "permissions":["Item","StoreSale"]                     -> needs the 3-branch filter
--   RBAC_ABAC (org subtree):   {"...","mode":"RBAC_ABAC","org":"100",...}                 -> needs the 3-branch filter
--
-- NO-OAUTH fallback only (get_user_context reads ABAC_UserContext): switch by UPDATE
-- instead of by claim, keyed on the SP app id:
--   UPDATE abac_tpcds.tpcds_1_delta.ABAC_UserContext
--     SET mode='DISABLE' WHERE user_name='76d5804d-d302-4014-a1d3-d846f02c84ef';
-- =====================================================================
