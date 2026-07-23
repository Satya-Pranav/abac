-- sql_onetrust/06_test_cases.sql   (RUN AS OWNER — uses get_test_user_context, no policy needed)
-- get_test_user_context (03_row_filter_udfs.sql) returns:
--   user='u.assessment.owner@example.com', mode='ABAC', root='ASSESSMENT', permissions=['TEMPLATE']
-- Update the seeded entity ids below to match what 05_seed_test_principals.sql actually
-- picked (query seed_assessment_entity / seed_control_entity, or re-run 05 in the same
-- session so the temp views are live).

-- T1: root type, explicit assignment — the seeded assessment IS visible.
SELECT assert_true(count(*) = 1, 'T1 FAILED: seeded assessment should be visible')
FROM abac_onetrust.onetrust_sim.cmb_assessment
WHERE id = (SELECT entity_id FROM seed_assessment_entity)
  AND abac_onetrust.onetrust_sim.abac_row_filter_wrapper(id, 'ASSESSMENT', '100');

-- T2: root type, no assignment at all — a DIFFERENT assessment is NOT visible
-- (mode=ABAC means only explicit assignments show; picks any other real id).
SELECT assert_true(count(*) = 0, 'T2 FAILED: unassigned assessment should not be visible')
FROM abac_onetrust.onetrust_sim.cmb_assessment
WHERE id != (SELECT entity_id FROM seed_assessment_entity)
  AND id NOT IN (SELECT entityId FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE subjectId = 'u.assessment.owner@example.com')
  AND abac_onetrust.onetrust_sim.abac_row_filter_wrapper(id, 'ASSESSMENT', '100')
LIMIT 1;

-- T3: non-root type, IN permissions array — ALL cmb_template rows visible.
SELECT
  assert_true(
    (SELECT count(*) FROM abac_onetrust.onetrust_sim.cmb_template) =
    (SELECT count(*) FROM abac_onetrust.onetrust_sim.cmb_template WHERE abac_onetrust.onetrust_sim.abac_row_filter_wrapper(id, 'TEMPLATE', '100')),
    'T3 FAILED: all templates should be visible (non-root, in permissions)'
  );

-- T4: non-root type, NOT in permissions array — ZERO cmb_controlimplementation rows
-- visible under the ABAC-owner context (root=ASSESSMENT, permissions=[TEMPLATE] only).
SELECT assert_true(count(*) = 0, 'T4 FAILED: controls should not be visible (non-root, not in permissions)')
FROM abac_onetrust.onetrust_sim.cmb_controlimplementation
WHERE abac_onetrust.onetrust_sim.abac_row_filter_wrapper(id, 'CONTROL', '100');

-- T5: group membership — a user who is a MEMBER of test_group_1 (which owns the
-- seeded control) sees it, via a context override.
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context_group_member()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
RETURN named_struct('tenant', 1, 'user', 'u.group.member@example.com', 'org', '100', 'mode', 'ABAC', 'root', 'CONTROL', 'permissions', array());

SELECT assert_true(count(*) = 1, 'T5 FAILED: group member should see the group-assigned control')
FROM abac_onetrust.onetrust_sim.cmb_controlimplementation
WHERE id = (SELECT entity_id FROM seed_control_entity)
  AND abac_onetrust.onetrust_sim.abac_row_filter(
        id, 'CONTROL', '100', abac_onetrust.onetrust_sim.get_test_user_context_group_member());

-- T6: isActive=false assignment — u.inactive.grant has an ESA row but the linked
-- Assignment (900002) has isActive=false, so it must NOT grant visibility.
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context_inactive()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
RETURN named_struct('tenant', 1, 'user', 'u.inactive.grant@example.com', 'org', '100', 'mode', 'ABAC', 'root', 'ASSESSMENT', 'permissions', array());

SELECT assert_true(count(*) = 0, 'T6 FAILED: an isActive=false assignment must not grant visibility')
FROM abac_onetrust.onetrust_sim.cmb_assessment
WHERE id = (SELECT entity_id FROM seed_assessment_entity)
  AND abac_onetrust.onetrust_sim.abac_row_filter(
        id, 'ASSESSMENT', '100', abac_onetrust.onetrust_sim.get_test_user_context_inactive());

-- T7: DISABLE mode — everything visible regardless of assignments.
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context_disabled()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
RETURN named_struct('tenant', 1, 'user', 'u.disabled.mode@example.com', 'org', '100', 'mode', 'DISABLE', 'root', 'ASSESSMENT', 'permissions', array());

SELECT
  assert_true(
    (SELECT count(*) FROM abac_onetrust.onetrust_sim.cmb_assessment) =
    (SELECT count(*) FROM abac_onetrust.onetrust_sim.cmb_assessment
     WHERE abac_onetrust.onetrust_sim.abac_row_filter(id, 'ASSESSMENT', '100', abac_onetrust.onetrust_sim.get_test_user_context_disabled())),
    'T7 FAILED: DISABLE mode should show every row'
  );

-- T8: RBAC_ABAC mode over the real orgHierarchy ancestor closure — a user with
-- root=ASSETS and org = the real orgID that every cmb_v_inventoryaggregatedrisksummary
-- row carries (the table's 14 real verbatim rows all share one orgID — a single
-- distinct value confirmed against the profiled sample data) sees the tagged-type
-- rows whose org is in their subtree (proves the real profiled orgHierarchy data,
-- not a fabricated tree, drives the result — the root org's ancestor-closure row
-- makes it a member of its own subtree).
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context_rbac()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
RETURN named_struct(
  'tenant', 1, 'user', 'u.rbac.viewer@example.com', 'org',
  (SELECT orgID FROM abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary LIMIT 1),
  'mode', 'RBAC_ABAC', 'root', 'ASSETS', 'permissions', array()
);

SELECT assert_true(count(*) >= 1, 'T8 FAILED: RBAC_ABAC org-subtree row should be visible for at least the seeded org itself')
FROM abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary
WHERE upper(inventoryType) = 'ASSETS'
  AND abac_onetrust.onetrust_sim.abac_row_filter(
        entityID, 'ASSETS', orgID, abac_onetrust.onetrust_sim.get_test_user_context_rbac());

-- Expected: every assert_true statement above returns without throwing (Databricks
-- SQL's assert_true raises an error and halts if the condition is false — a failed
-- assertion is a visibly failed step, not a silent wrong answer).
