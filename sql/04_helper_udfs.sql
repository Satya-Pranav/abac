-- =====================================================================
-- 04_helper_udfs.sql   -> abac_tpcds.abac  (functions only, like customer ABAC schema)
-- =====================================================================

-- ---------------------------------------------------------------------
-- get_user_context() : NO OAUTH VARIANT.
-- Customer body was: from_json(current_oauth_custom_identity_claim(), '<struct>').
-- Here we resolve the same struct by current_user() from ABAC_UserContext.
-- Returns NULL if the principal has no context row -> row filter denies (secure default).
-- Swap this one function back to the OAuth body later; nothing downstream changes.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION abac_tpcds.abac.get_user_context()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
COMMENT 'No-OAuth: resolve ABAC context for the current Databricks principal via current_user()'
RETURN (
  SELECT named_struct(
           'tenant',      tenant,
           'user',        user_name,
           'org',         org,
           'mode',        mode,
           'root',        root,
           'permissions', permissions)
  FROM abac_tpcds.tpcds_1_delta.ABAC_UserContext
  WHERE user_name = current_user() AND isDeleted = false
  LIMIT 1
);

-- ---------------------------------------------------------------------
-- get_test_user_context() : deterministic context for ADMIN-side validation.
-- Independent of current_user(), so the owner can validate the logic directly.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION abac_tpcds.abac.get_test_user_context()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
COMMENT 'Deterministic ABAC context for admin-side validation'
RETURN named_struct(
  'tenant',      1,
  'user',        'u.analyst1@example.com',
  'org',         '100',
  'mode',        'ABAC',
  'root',        'Customer',
  'permissions', array('Item','StoreSale')
);

-- ---------------------------------------------------------------------
-- entity_type_to_object_type() : TPC-DS table name -> ABAC object type.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION abac_tpcds.abac.entity_type_to_object_type(entity_type STRING)
RETURNS STRING
COMMENT 'Convert a TPC-DS table/type string to an ABAC object type'
RETURN CASE lower(entity_type)
  WHEN 'customer'        THEN 'Customer'
  WHEN 'customer_address' THEN 'CustomerAddress'
  WHEN 'item'            THEN 'Item'
  WHEN 'store'           THEN 'Store'
  WHEN 'store_sales'     THEN 'StoreSale'
  WHEN 'store_returns'   THEN 'StoreReturn'
  WHEN 'catalog_sales'   THEN 'CatalogSale'
  WHEN 'catalog_returns' THEN 'CatalogReturn'
  WHEN 'web_sales'       THEN 'WebSale'
  WHEN 'web_returns'     THEN 'WebReturn'
  WHEN 'web_site'        THEN 'WebSite'
  WHEN 'warehouse'       THEN 'Warehouse'
  ELSE entity_type
END;

-- ---------------------------------------------------------------------
-- object_type_to_permission() : kept for FIDELITY with the customer ABAC schema.
-- NOTE: the customer row filter does NOT call this; permissions are matched by
-- object type directly (array_contains(ctx.permissions, object_type)).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION abac_tpcds.abac.object_type_to_permission(object_type STRING)
RETURNS STRING
COMMENT 'Fidelity helper; not used by the row filter'
RETURN CASE object_type
  WHEN 'Customer'        THEN 'customers'
  WHEN 'CustomerAddress' THEN 'customer-addresses'
  WHEN 'Item'            THEN 'items'
  WHEN 'Store'           THEN 'stores'
  WHEN 'StoreSale'       THEN 'sales'
  WHEN 'StoreReturn'     THEN 'returns'
  WHEN 'CatalogSale'     THEN 'sales'
  WHEN 'CatalogReturn'   THEN 'returns'
  WHEN 'WebSale'         THEN 'sales'
  WHEN 'WebReturn'       THEN 'returns'
  WHEN 'WebSite'         THEN 'web-sites'
  WHEN 'Warehouse'       THEN 'warehouses'
  ELSE lower(object_type)
END;
