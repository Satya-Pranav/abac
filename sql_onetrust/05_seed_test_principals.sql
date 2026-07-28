-- sql_onetrust/05_seed_test_principals.sql   (re-runnable: deletes prior seed rows first, by name prefix)
-- Picks one real entity per governed table (from the already-generated bulk data)
-- and creates hand-authored assignment/subject rows so test cases can assert exact,
-- known outcomes — the bulk synthetic rows from Task 16 provide background noise/scale,
-- these seeded rows are the ground truth the test cases check.
--
-- Every statement below is fully self-contained (no CREATE TEMPORARY VIEW, no cross-statement
-- state) -- each one can be submitted independently, e.g. one at a time via the SQL Statement
-- Execution API (POST /api/2.0/sql/statements), which opens a fresh session per call and cannot
-- see a TEMPORARY VIEW created by an earlier call. Entity lookups are inlined as derived-table
-- subqueries `FROM (SELECT ... ORDER BY ... LIMIT 1)` instead -- deterministic (ORDER BY breaks
-- ties) and evaluated fresh within the same statement, so no separate view is needed.

DELETE FROM abac_onetrust.onetrust_sim.ABAC_Assignment WHERE staticIdentifier = 'phase1-test-seed';
DELETE FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-test-seed';
DELETE FROM abac_onetrust.onetrust_sim.UserGroupMembers WHERE tenantHash = 'phase1-test-seed';

-- assignment 900001: explicit grant on the seeded assessment to u.assessment.owner
-- SELECT ... UNION ALL, not a multi-row VALUES (...), (...): Databricks SQL cannot
-- evaluate a non-deterministic expression like uuid() inside a VALUES inline table.
INSERT INTO abac_onetrust.onetrust_sim.ABAC_Assignment
  (id, guid, staticIdentifier, name, objectType, sourceType, isActive, createdBy, createDT, updatedBy, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900001, uuid(), 'phase1-test-seed', 'Owner', 'ASSESSMENT', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
UNION ALL
-- 900002: an INACTIVE grant (test case 8 — must NOT grant visibility)
SELECT 900002, uuid(), 'phase1-test-seed', 'Owner', 'ASSESSMENT', 'SYSTEM', false, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
UNION ALL
-- 900003: group grant on the seeded control to test_group_1
SELECT 900003, uuid(), 'phase1-test-seed', 'Owner', 'CONTROL', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false;

-- one real cmb_assessment id and one real cmb_controlimplementation id, inlined below. ORDER BY id
-- makes the pick deterministic: a plain LIMIT 1 with no ordering gives SQL no guarantee the same
-- underlying row is returned across separate evaluations, but ORDER BY id LIMIT 1 always does.
INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900001, NULL, entity_id, NULL, 'u.assessment.owner@example.com', 'USER_ID', 'ASSESSMENT', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
FROM (SELECT id AS entity_id FROM abac_onetrust.onetrust_sim.cmb_assessment ORDER BY id LIMIT 1) ent
UNION ALL
SELECT 900002, NULL, entity_id, NULL, 'u.inactive.grant@example.com', 'USER_ID', 'ASSESSMENT', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
FROM (SELECT id AS entity_id FROM abac_onetrust.onetrust_sim.cmb_assessment ORDER BY id LIMIT 1) ent
UNION ALL
SELECT 900003, NULL, entity_id, NULL, 'test_group_1', 'USER_GROUP', 'CONTROL', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
FROM (SELECT id AS entity_id FROM abac_onetrust.onetrust_sim.cmb_controlimplementation ORDER BY id LIMIT 1) ent;

INSERT INTO abac_onetrust.onetrust_sim.UserGroupMembers (memberId, groupId, eventTime, recModifiedTime, isDeleted, tenantHash)
VALUES ('u.group.member@example.com', 'test_group_1', current_timestamp(), current_timestamp(), false, 'phase1-test-seed');

-- assignment 900004: explicit grant on the seeded template to u.template.owner (3rd real
-- explicit-assignment identity, direct USER_ID -- not via a group, unlike test_group_1/CONTROL --
-- so the ABAC-group cases have a same-mechanism-different-table pair independent of the group path).
INSERT INTO abac_onetrust.onetrust_sim.ABAC_Assignment
  (id, guid, staticIdentifier, name, objectType, sourceType, isActive, createdBy, createDT, updatedBy, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900004, uuid(), 'phase1-test-seed', 'Owner', 'TEMPLATE', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false;

-- one real cmb_template id, inlined the same deterministic way as above (ORDER BY id LIMIT 1).
INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900004, NULL, entity_id, NULL, 'u.template.owner@example.com', 'USER_ID', 'TEMPLATE', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
FROM (SELECT id AS entity_id FROM abac_onetrust.onetrust_sim.cmb_template ORDER BY id LIMIT 1) ent;

-- assignment 900005: explicit grant on the seeded ASSETS entity to u.assets.owner (5th real
-- explicit-assignment identity -- used by the RBAC group to prove 3b works independent of 3a).
INSERT INTO abac_onetrust.onetrust_sim.ABAC_Assignment
  (id, guid, staticIdentifier, name, objectType, sourceType, isActive, createdBy, createDT, updatedBy, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900005, uuid(), 'phase1-test-seed', 'Owner', 'ASSETS', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false;

-- one real cmb_v_inventoryaggregatedrisksummary entity whose inventoryType is 'Assets' (maps to
-- object type 'ASSETS' via entity_type_to_object_type -- see config.INVENTORY_TYPE_TO_OBJECT_TYPE),
-- inlined the same deterministic way as above (ORDER BY entityID LIMIT 1).
INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900005, NULL, entity_id, NULL, 'u.assets.owner@example.com', 'USER_ID', 'ASSETS', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
FROM (SELECT entityID AS entity_id FROM abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary
      WHERE upper(inventoryType) = 'ASSETS' ORDER BY entityID LIMIT 1) ent;

-- Expected: no error; SELECT count(*) FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
-- WHERE tenantHash = 'phase1-test-seed' returns 5.
