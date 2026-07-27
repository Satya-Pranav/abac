-- sql_onetrust/05_seed_test_principals.sql   (re-runnable: deletes prior seed rows first, by name prefix)
-- Picks one real entity per governed table (from the already-generated bulk data)
-- and creates hand-authored assignment/subject rows so test cases can assert exact,
-- known outcomes — the bulk synthetic rows from Task 16 provide background noise/scale,
-- these seeded rows are the ground truth the test cases check.

DELETE FROM abac_onetrust.onetrust_sim.ABAC_Assignment WHERE staticIdentifier = 'phase1-test-seed';
DELETE FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-test-seed';
DELETE FROM abac_onetrust.onetrust_sim.UserGroupMembers WHERE tenantHash = 'phase1-test-seed';

-- one real cmb_assessment id and one real cmb_controlimplementation id, picked
-- from the already-generated data. ORDER BY id makes the pick deterministic:
-- these views are re-evaluated fresh both here and in 06_test_cases.sql (a
-- separate script execution), and a plain LIMIT 1 with no ordering gives SQL
-- no guarantee the same underlying row is returned both times.
CREATE OR REPLACE TEMPORARY VIEW seed_assessment_entity AS
  SELECT id AS entity_id FROM abac_onetrust.onetrust_sim.cmb_assessment ORDER BY id LIMIT 1;
CREATE OR REPLACE TEMPORARY VIEW seed_control_entity AS
  SELECT id AS entity_id FROM abac_onetrust.onetrust_sim.cmb_controlimplementation ORDER BY id LIMIT 1;

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

INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900001, NULL, entity_id, NULL, 'u.assessment.owner@example.com', 'USER_ID', 'ASSESSMENT', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
FROM seed_assessment_entity
UNION ALL
SELECT 900002, NULL, entity_id, NULL, 'u.inactive.grant@example.com', 'USER_ID', 'ASSESSMENT', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
FROM seed_assessment_entity
UNION ALL
SELECT 900003, NULL, entity_id, NULL, 'test_group_1', 'USER_GROUP', 'CONTROL', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
FROM seed_control_entity;

INSERT INTO abac_onetrust.onetrust_sim.UserGroupMembers (memberId, groupId, eventTime, recModifiedTime, isDeleted, tenantHash)
VALUES ('u.group.member@example.com', 'test_group_1', current_timestamp(), current_timestamp(), false, 'phase1-test-seed');

-- one real cmb_template id, picked the same deterministic way as seed_assessment_entity/
-- seed_control_entity above.
CREATE OR REPLACE TEMPORARY VIEW seed_template_entity AS
  SELECT id AS entity_id FROM abac_onetrust.onetrust_sim.cmb_template ORDER BY id LIMIT 1;

-- assignment 900004: explicit grant on the seeded template to u.template.owner (3rd real
-- explicit-assignment identity, direct USER_ID -- not via a group, unlike test_group_1/CONTROL --
-- so the ABAC-group cases have a same-mechanism-different-table pair independent of the group path).
INSERT INTO abac_onetrust.onetrust_sim.ABAC_Assignment
  (id, guid, staticIdentifier, name, objectType, sourceType, isActive, createdBy, createDT, updatedBy, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900004, uuid(), 'phase1-test-seed', 'Owner', 'TEMPLATE', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false;

INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900004, NULL, entity_id, NULL, 'u.template.owner@example.com', 'USER_ID', 'TEMPLATE', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
FROM seed_template_entity;

-- Expected: no error; SELECT count(*) FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
-- WHERE tenantHash = 'phase1-test-seed' returns 4.
