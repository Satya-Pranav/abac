-- =====================================================================
-- 01_schemas.sql
-- Catalog `abac_tpcds` and schema `tpcds_1_delta` already exist (tables loaded).
-- Create only the shared helper schema. Mirrors the customer `ABAC` schema:
-- FUNCTIONS ONLY — no tables live here.
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_tpcds.abac
  COMMENT 'Shared ABAC helper functions (get_user_context, entity_type_to_object_type, object_type_to_permission). No tables.';
