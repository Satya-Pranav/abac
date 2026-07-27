-- =====================================================================
-- 09_onboard_new_tables.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported from sql/13_onboard_new_tables.sql (TPC-DS Parts A-D; Part E's org rows are already
-- covered by Task 1's DEL_ORG/LIVE_ORG fixture), for the OneTrust suite (OT-N1..OT-N4).
-- 4 throwaway tables in an isolated schema, wired to the REAL deployed
-- abac_row_filter_wrapper_oauth (see sql_onetrust/07_oauth_wiring.sql) -- the point of this group
-- is proving a brand-new table onboards correctly onto the EXISTING shared filter, so the
-- metadata rows are real, namespaced 'phase1-meta-seed' (not isolated like the tables).
--
-- Conditions exercised (all honored by the deployed abac_row_filter):
--   meta_promo -> esa.isDeleted = true             -> branch 3b EXISTS excluded  -> 0  (negative)
--   meta_store -> esa.subjectType = 'USER_GROUP'   -> group path via ugm          -> 1  (positive)
--   meta_cc    -> ABAC_Assignment.isActive = false -> JOIN a.isActive fails       -> 0  (negative)
--   meta_ship  -> ABAC_Assignment.isDeleted = true -> AND a.isDeleted=false fails -> 0  (negative)
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_meta;

CREATE OR REPLACE TABLE abac_onetrust.abac_meta.meta_promo (id BIGINT);
INSERT INTO abac_onetrust.abac_meta.meta_promo SELECT id FROM range(1, 21);
CREATE OR REPLACE TABLE abac_onetrust.abac_meta.meta_store (id BIGINT);
INSERT INTO abac_onetrust.abac_meta.meta_store SELECT id FROM range(1, 21);
CREATE OR REPLACE TABLE abac_onetrust.abac_meta.meta_cc (id BIGINT);
INSERT INTO abac_onetrust.abac_meta.meta_cc SELECT id FROM range(1, 21);
CREATE OR REPLACE TABLE abac_onetrust.abac_meta.meta_ship (id BIGINT);
INSERT INTO abac_onetrust.abac_meta.meta_ship SELECT id FROM range(1, 21);

ALTER TABLE abac_onetrust.abac_meta.meta_promo ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.abac_meta.meta_store ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.abac_meta.meta_cc    ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.abac_meta.meta_ship  ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

-- object type literals: any string not 'PROCESSING ACTIVITIES' passes through
-- entity_type_to_object_type() unchanged (see sql_onetrust/03_row_filter_udfs.sql).
CREATE OR REPLACE POLICY meta_promo_policy
ON TABLE abac_onetrust.abac_meta.meta_promo
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper_oauth
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'META_PROMO', '100');

CREATE OR REPLACE POLICY meta_store_policy
ON TABLE abac_onetrust.abac_meta.meta_store
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper_oauth
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'META_STORE', '100');

CREATE OR REPLACE POLICY meta_cc_policy
ON TABLE abac_onetrust.abac_meta.meta_cc
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper_oauth
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'META_CC', '100');

CREATE OR REPLACE POLICY meta_ship_policy
ON TABLE abac_onetrust.abac_meta.meta_ship
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper_oauth
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'META_SHIP', '100');

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_meta         TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_meta.meta_promo    TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_meta.meta_store    TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_meta.meta_cc       TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_meta.meta_ship     TO `<ONETRUST_SP>`;

-- PART B — ABAC_Assignment rows (the on/off + soft-delete switches). Real table, namespaced.
DELETE FROM abac_onetrust.onetrust_sim.ABAC_Assignment WHERE staticIdentifier = 'phase1-meta-seed';
DELETE FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-meta-seed';
DELETE FROM abac_onetrust.onetrust_sim.UserGroupMembers WHERE tenantHash = 'phase1-meta-seed';

INSERT INTO abac_onetrust.onetrust_sim.ABAC_Assignment
  (id, guid, staticIdentifier, name, objectType, sourceType, isActive, createdBy, createDT, updatedBy, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900101, uuid(), 'phase1-meta-seed', 'Owner', 'META_PROMO', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', false
UNION ALL
SELECT 900102, uuid(), 'phase1-meta-seed', 'Owner', 'META_STORE', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', false
UNION ALL
SELECT 900103, uuid(), 'phase1-meta-seed', 'Owner', 'META_CC', 'SYSTEM', false, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', false
UNION ALL
SELECT 900104, uuid(), 'phase1-meta-seed', 'Owner', 'META_SHIP', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', true;

-- PART C — ABAC_EntitySubjectAssignment rows.
-- meta_promo: esa.isDeleted = TRUE (negative)
INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
VALUES (900101, NULL, '1', NULL, 'u.meta.tester@example.com', 'USER_ID', 'META_PROMO', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', true);
-- meta_store: subjectType = USER_GROUP -> group 'meta_group_1' (positive; meta.tester is a member, Part D)
INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
VALUES (900102, NULL, '1', NULL, 'meta_group_1', 'USER_GROUP', 'META_STORE', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', false);
-- meta_cc: normal esa, but its assignment is inactive (negative)
INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
VALUES (900103, NULL, '1', NULL, 'u.meta.tester@example.com', 'USER_ID', 'META_CC', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', false);
-- meta_ship: normal esa, but its assignment is soft-deleted (negative)
INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
VALUES (900104, NULL, '1', NULL, 'u.meta.tester@example.com', 'USER_ID', 'META_SHIP', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', false);

-- PART D — UserGroupMembers (meta.tester is a member of meta_group_1)
INSERT INTO abac_onetrust.onetrust_sim.UserGroupMembers (memberId, groupId, eventTime, recModifiedTime, isDeleted, tenantHash)
VALUES ('u.meta.tester@example.com', 'meta_group_1', current_timestamp(), current_timestamp(), false, 'phase1-meta-seed');

-- Expect (as the SP via the suite, claim user=u.meta.tester@example.com, mode=ABAC, root=<table's type>):
--   OT-N1 (meta_promo, root=META_PROMO): count(*) WHERE id=1 -> 0 (esa soft-deleted)
--   OT-N2 (meta_store, root=META_STORE): count(*) WHERE id=1 -> 1 (group grant)
--   OT-N3 (meta_cc, root=META_CC):       count(*) WHERE id=1 -> 0 (assignment inactive)
--   OT-N4 (meta_ship, root=META_SHIP):   count(*) WHERE id=1 -> 0 (assignment soft-deleted)

-- ---- TEARDOWN ----
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_meta CASCADE;
--   DELETE FROM abac_onetrust.onetrust_sim.ABAC_Assignment WHERE staticIdentifier = 'phase1-meta-seed';
--   DELETE FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-meta-seed';
--   DELETE FROM abac_onetrust.onetrust_sim.UserGroupMembers WHERE tenantHash = 'phase1-meta-seed';
