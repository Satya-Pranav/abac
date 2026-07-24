-- =====================================================================
-- 01_catalog_schema.sql
-- Run as workspace/catalog admin.
-- Creates the catalog and schemas the Phase 1 OneTrust synthetic dataset lands in.
-- Names must match onetrust_synth/config.py exactly: CATALOG = "abac_onetrust",
-- MAIN_SCHEMA = "onetrust_sim", MONITORING_SCHEMA = "monitoring".
-- =====================================================================

CREATE CATALOG IF NOT EXISTS abac_onetrust;
CREATE SCHEMA IF NOT EXISTS abac_onetrust.onetrust_sim;
CREATE SCHEMA IF NOT EXISTS abac_onetrust.monitoring;

SHOW SCHEMAS IN abac_onetrust;
-- Expected: the result set includes `onetrust_sim` and `monitoring`.
