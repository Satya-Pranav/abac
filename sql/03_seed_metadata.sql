-- =====================================================================
-- 03_seed_metadata.sql   (re-runnable: clears prior seed first)
-- OAUTH PHASE: identity comes from the token's custom_claim, so ctx.user is a
-- DUMMY EMAIL the caller chooses. subjectID / memberID must equal that dummy email.
-- We seed 3 testers, each owning ONE entity of its root type (mirrors the live seed):
--     u.analyst1@example.com   -> Customer  entity 2012
--     u.vendor.mgr@example.com -> Item      entity 3006
--     u.developer@example.com  -> StoreSale entity 118144
-- These dummy emails are NOT real Databricks principals — they appear only as
-- claim.user / subjectID. The SERVICE PRINCIPAL 76d5804d-… is the real login the
-- policies bind TO (see 08/09).
-- =====================================================================

TRUNCATE TABLE abac_tpcds.tpcds_1_delta.ABAC_UserContext;
TRUNCATE TABLE abac_tpcds.tpcds_1_delta.ABAC_Assignment;
TRUNCATE TABLE abac_tpcds.tpcds_1_delta.ABAC_AssignmentPermission;
TRUNCATE TABLE abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment;
TRUNCATE TABLE abac_tpcds.tpcds_1_delta.UserGroupMembers;
TRUNCATE TABLE abac_tpcds.tpcds_1_delta.orgHierarchy;

-- (1) Assignments (grant records) — one per tester, all active.
INSERT INTO abac_tpcds.tpcds_1_delta.ABAC_Assignment VALUES
  ('assignment_customer_1', true, false),
  ('assignment_item_1',     true, false),
  ('assignment_sales_1',    true, false);

-- (2) Permission rows (used by masking only; the row filter never reads these).
INSERT INTO abac_tpcds.tpcds_1_delta.ABAC_AssignmentPermission VALUES
  ('assignment_customer_1', 'customers.basic.view', false),
  ('assignment_item_1',     'items.basic.view',     false),
  ('assignment_sales_1',    'sales.basic.view',     false);

-- (3) Explicit per-row assignments. entityID = the STRING value the policy passes:
--     Customer  -> CAST(c_customer_sk  AS STRING)
--     Item      -> CAST(i_item_sk      AS STRING)
--     StoreSale -> CAST(ss_customer_sk AS STRING)   (many sales share one customer sk)
--     subjectID = the tester's dummy email = the claim.user you send.
--     (2012 / 3006 / 118144 must exist in this dataset — they do in the live copy.)
INSERT INTO abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment VALUES
  ('2012',   'Customer',  'assignment_customer_1', 'USER_ID', 'u.analyst1@example.com',   false),
  ('3006',   'Item',      'assignment_item_1',     'USER_ID', 'u.vendor.mgr@example.com', false),
  ('118144', 'StoreSale', 'assignment_sales_1',    'USER_ID', 'u.developer@example.com',  false);

-- (4) Group membership (memberID = a tester's dummy email). DORMANT until an ESA row
--     uses subjectType='USER_GROUP', subjectID='test_group_1' (see JDBC_CASES.md group note).
INSERT INTO abac_tpcds.tpcds_1_delta.UserGroupMembers VALUES
  ('test_group_1', 'u.analyst1@example.com', false);

-- (5) orgHierarchy for RBAC_ABAC: org '100' is its own root; make 5 real customer
--     address keys DIRECT children of '100'. Under RBAC_ABAC + root=Customer + ctx.org='100',
--     a customer row is visible when its c_current_addr_sk is one of these.
INSERT INTO abac_tpcds.tpcds_1_delta.orgHierarchy VALUES ('100', '100', false);
INSERT INTO abac_tpcds.tpcds_1_delta.orgHierarchy
SELECT DISTINCT CAST(c_current_addr_sk AS STRING), '100', false
FROM abac_tpcds.tpcds_1_delta.customer
WHERE c_current_addr_sk IS NOT NULL
ORDER BY 1
LIMIT 5;

-- (6) ABAC_UserContext is used ONLY by the NO-OAUTH get_user_context() fallback, keyed
--     by current_user(). Under OAuth this table is unused (identity is the claim). Dummy
--     emails can't go here — they aren't real principals — so key the fallback row on the
--     SERVICE PRINCIPAL (its app id is current_user() for the SP session). DISABLE = a
--     harmless "pipeline returns data" default for a no-OAuth sanity check.
INSERT INTO abac_tpcds.tpcds_1_delta.ABAC_UserContext VALUES
  ('76d5804d-d302-4014-a1d3-d846f02c84ef', 1, '100', 'DISABLE', 'Customer', array('Item','StoreSale'), false);

-- Sanity: show the testers and the entity each may see.
SELECT * FROM abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment ORDER BY objectType;
SELECT * FROM abac_tpcds.tpcds_1_delta.ABAC_Assignment;
