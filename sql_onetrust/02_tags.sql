-- =====================================================================
-- 02_tags.sql   (RUN AFTER Task 9/16's Python scripts have written the tables)
-- =====================================================================
-- Governed tag KEYS (abac_column_id, abac_column_org, abac_column_type) must already
-- exist -- created once via Settings > Catalog > Governed tags, same as the TPC-DS POC
-- (see sql/07_tags.sql for precedent). Phase 1 tags only the 4 tables getting a policy
-- in Task 18 (see design doc section 4 / this plan's Global Constraints for why the
-- other 7 main tables are out of scope for Phase 1 policy wiring).
--
-- Column names below are verified against the real profiled columns in
-- onetrust/onetrust_sample_data/onetrust_table_profile_results.csv and against
-- onetrust_synth/config.py's ENTITY_SOURCE_TABLES (id column per source table).
-- =====================================================================

-- cmb_assessment: single type 'ASSESSMENT' (no_type policy shape) -- id only
ALTER TABLE abac_onetrust.onetrust_sim.cmb_assessment ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

-- cmb_controlimplementation: single type 'CONTROL' (no_type policy shape) -- id only
ALTER TABLE abac_onetrust.onetrust_sim.cmb_controlimplementation ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

-- cmb_template: single type 'TEMPLATE' (no_type policy shape) -- id only
ALTER TABLE abac_onetrust.onetrust_sim.cmb_template ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

-- cmb_v_inventoryaggregatedrisksummary: per-row type via inventoryType (default/tagged-type
-- policy shape) -- id, type, AND org (orgID exists on this table, unlike the other three)
ALTER TABLE abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary ALTER COLUMN entityID SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary ALTER COLUMN inventoryType SET TAGS ('abac_column_type' = 'true');
ALTER TABLE abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary ALTER COLUMN orgID SET TAGS ('abac_column_org' = 'true');

-- Expected: no error (governed tag keys must be pre-created via the UI first, per the
-- comment above -- if SET TAGS errors with an unknown tag key, create the 3 keys via
-- Settings > Catalog > Governed tags before re-running).
