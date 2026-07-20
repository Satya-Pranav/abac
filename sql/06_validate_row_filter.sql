-- =====================================================================
-- 06_validate_row_filter.sql   (RUN AS THE OWNER — no policies needed yet)
-- Exercises abac_row_filter via the TEST wrapper (get_test_user_context:
-- mode=ABAC, root=Customer, permissions=[Item,StoreSale]).
-- This is the checkpoint before attaching policies.
-- =====================================================================

-- customer (root type): expect ONLY the 5 explicitly-assigned customers.
SELECT count(*) AS visible_customers
FROM abac_tpcds.tpcds_1_delta.customer
WHERE abac_tpcds.tpcds_1_delta.abac_row_filter_test_wrapper(
        CAST(c_customer_sk AS STRING), 'customer', CAST(c_current_addr_sk AS STRING));

-- Show which customers (should match the 5 seeded in 03).
SELECT c_customer_sk
FROM abac_tpcds.tpcds_1_delta.customer
WHERE abac_tpcds.tpcds_1_delta.abac_row_filter_test_wrapper(
        CAST(c_customer_sk AS STRING), 'customer', CAST(c_current_addr_sk AS STRING))
ORDER BY c_customer_sk;

-- item (non-root, IN permissions): expect ALL items visible.
SELECT
  (SELECT count(*) FROM abac_tpcds.tpcds_1_delta.item) AS total_items,
  count(*) AS visible_items
FROM abac_tpcds.tpcds_1_delta.item
WHERE abac_tpcds.tpcds_1_delta.abac_row_filter_test_wrapper(
        CAST(i_item_sk AS STRING), 'item', '100');

-- store_sales (non-root, IN permissions): expect ALL rows visible.
SELECT
  (SELECT count(*) FROM abac_tpcds.tpcds_1_delta.store_sales) AS total_store_sales,
  count(*) AS visible_store_sales
FROM abac_tpcds.tpcds_1_delta.store_sales
WHERE abac_tpcds.tpcds_1_delta.abac_row_filter_test_wrapper(
        CAST(ss_customer_sk AS STRING), 'store_sales', CAST(ss_store_sk AS STRING));

-- store (non-root, NOT in permissions): expect ZERO rows.
SELECT count(*) AS visible_stores
FROM abac_tpcds.tpcds_1_delta.store
WHERE abac_tpcds.tpcds_1_delta.abac_row_filter_test_wrapper(
        CAST(s_store_sk AS STRING), 'store', CAST(s_store_sk AS STRING));

-- warehouse (non-root, NOT in permissions): expect ZERO rows.
SELECT count(*) AS visible_warehouses
FROM abac_tpcds.tpcds_1_delta.warehouse
WHERE abac_tpcds.tpcds_1_delta.abac_row_filter_test_wrapper(
        CAST(w_warehouse_sk AS STRING), 'warehouse', CAST(w_warehouse_sk AS STRING));
