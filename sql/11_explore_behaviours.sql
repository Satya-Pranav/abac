-- =====================================================================
-- 11_explore_behaviours.sql   (RUN AS THE OWNER, top-to-bottom, in the SQL editor)
--
-- PURPOSE: a "sample program" to explore how the Databricks row filter behaves
-- as you vary the FOUR axes you care about:
--     (A) ctx JSON values        -> Part A  (mode / root / user / permissions grid)
--     (B) claims values          -> Part A is the claim body; Part E runs it via OAuth
--     (C) row-filter logic       -> Part 0 prints which filter is really deployed
--     (D) metadata table values  -> Part D  (flip isActive/isDeleted, group grants)
-- Plus the RBAC_ABAC question ("rbac_abac???")  -> Part C.
--
-- HOW IT WORKS: instead of minting an OAuth token per scenario, we call
-- abac_row_filter(entity, object_type, org_id, ctx) DIRECTLY with a literal
-- named_struct ctx. That is exactly the struct get_user_context() would return,
-- so the logic is identical — but we can sweep dozens of ctx values in seconds,
-- as the owner, with no token, no reseed. Part E then confirms one row of the
-- grid through the real OAuth/JDBC path so you know the shortcut matches reality.
--
-- SAFE TO RUN: Parts 1-4 only INSERT a namespaced synthetic fixture (ids prefixed
-- 'EXP_' / 'u.explore') and DELETE exactly those rows at the end (Part Z). It does
-- not touch your existing seed. Run Part Z even if you stop early, to clean up.
-- =====================================================================


-- =====================================================================
-- PART 0 — WHICH ROW FILTER IS ACTUALLY DEPLOYED? (axis C)
-- The repo's 05_dataset_udfs.sql is 3-branch; first_jdbc_script.md is 2-branch.
-- Read the body below and check for the MIDDLE branch:
--     OR ( ctx.root <> object_type AND array_contains(ctx.permissions, object_type) )
--   present  -> 3-branch: non-root tables are visible when object_type ∈ permissions.
--   absent   -> 2-branch: permissions is INERT; only the root table can ever show rows.
-- Part A scenario A4 also tells you this automatically (TRUE => 3-branch).
-- =====================================================================
SHOW CREATE FUNCTION abac_tpcds.tpcds_1_delta.abac_row_filter;
-- Also confirm which identity source get_user_context() uses (no-OAuth table lookup
-- vs OAuth current_oauth_custom_identity_claim()):
SHOW CREATE FUNCTION abac_tpcds.abac.get_user_context;


-- =====================================================================
-- PART 1 — SYNTHETIC FIXTURE (deterministic, namespaced, removed in Part Z)
-- Everything below is logic-only: no dependency on the TPC-DS row values, so the
-- truth tables are reproducible no matter what 03_seed_metadata.sql loaded.
-- =====================================================================
INSERT INTO abac_tpcds.tpcds_1_delta.ABAC_Assignment VALUES
  ('EXP_A1', true,  false),   -- active, live
  ('EXP_A2', false, false),   -- INACTIVE (for the isActive experiment, Part D)
  ('EXP_A3', true,  true);    -- soft-DELETED (for the isDeleted experiment, Part D)

-- Entity E1: assigned to a USER.  Entity E2: assigned to a GROUP.
-- E_INACTIVE / E_DELETED: assigned via the inactive / deleted assignments.
INSERT INTO abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment VALUES
  ('E1',        'Customer', 'EXP_A1', 'USER_ID',    'u.explore@example.com', false),
  ('E2',        'Customer', 'EXP_A1', 'USER_GROUP', 'g.explore',             false),
  ('E_INACTIVE','Customer', 'EXP_A2', 'USER_ID',    'u.explore@example.com', false),
  ('E_DELETED', 'Customer', 'EXP_A3', 'USER_ID',    'u.explore@example.com', false),
  ('E_ESADEL',  'Customer', 'EXP_A1', 'USER_ID',    'u.explore@example.com', true);  -- ESA row itself soft-deleted

INSERT INTO abac_tpcds.tpcds_1_delta.UserGroupMembers VALUES
  ('g.explore', 'u.explore@example.com', false);

-- A 3-LEVEL org tree to probe RBAC_ABAC (Part C): ROOT -> CHILD -> GRAND.
INSERT INTO abac_tpcds.tpcds_1_delta.orgHierarchy VALUES
  ('EXP_ROOT',  'EXP_ROOT',  false),   -- self-loop (root is its own parent)
  ('EXP_CHILD', 'EXP_ROOT',  false),   -- direct child of ROOT
  ('EXP_GRAND', 'EXP_CHILD', false);   -- grandchild (child of CHILD, NOT of ROOT)


-- =====================================================================
-- PART A — ctx / CLAIM GRID  (axes A + B)
-- One result set = the full truth table. `expected` is what the *3-branch* filter
-- should return; compare `actual` to it. If A4 (the permissions branch) comes back
-- FALSE, your deployed filter is the 2-branch version (see Part 0).
--
-- The ctx literal here is byte-for-byte the JSON claim you would send via OAuth,
-- just written as named_struct. entity_id / object_type / org_id are the 3 args the
-- policy's USING COLUMNS clause feeds in (id, table-name-mapped-to-object-type, org).
-- =====================================================================
WITH probe AS (
  SELECT scenario, expected,
         abac_tpcds.tpcds_1_delta.abac_row_filter(entity_id, object_type, org_id, ctx) AS actual
  FROM VALUES
    -- (label, expected, entity_id, object_type, org_id, ctx)
    ('A0  DISABLE shows everything',                    true,
       'anything','Customer','ignored',
       named_struct('tenant',1,'user','nobody','org','X','mode','DISABLE','root','Customer','permissions',array())),

    ('A1  ABAC root match + USER assigned',             true,
       'E1','Customer','ignored',
       named_struct('tenant',1,'user','u.explore@example.com','org','EXP_ROOT','mode','ABAC','root','Customer','permissions',array())),

    ('A2  ABAC right entity, WRONG user',               false,
       'E1','Customer','ignored',
       named_struct('tenant',1,'user','someone.else@example.com','org','EXP_ROOT','mode','ABAC','root','Customer','permissions',array())),

    ('A3  ABAC right user, root<>object (Item)',        false,
       'E1','Item','ignored',
       named_struct('tenant',1,'user','u.explore@example.com','org','EXP_ROOT','mode','ABAC','root','Customer','permissions',array())),

    ('A4  non-root Item, Item IN permissions',          true,   -- 3-branch only; FALSE => 2-branch deployed
       'E1','Item','ignored',
       named_struct('tenant',1,'user','u.explore@example.com','org','EXP_ROOT','mode','ABAC','root','Customer','permissions',array('Item','StoreSale'))),

    ('A5  non-root Store, Store NOT in permissions',    false,
       'E1','Store','ignored',
       named_struct('tenant',1,'user','u.explore@example.com','org','EXP_ROOT','mode','ABAC','root','Customer','permissions',array('Item','StoreSale'))),

    ('A6  ABAC GROUP-assigned entity, user in group',   true,
       'E2','Customer','ignored',
       named_struct('tenant',1,'user','u.explore@example.com','org','EXP_ROOT','mode','ABAC','root','Customer','permissions',array())),

    ('A7  ABAC GROUP entity, user NOT in group',        false,
       'E2','Customer','ignored',
       named_struct('tenant',1,'user','outsider@example.com','org','EXP_ROOT','mode','ABAC','root','Customer','permissions',array())),

    ('A8  ABAC unassigned entity id',                   false,
       'E_NONE','Customer','ignored',
       named_struct('tenant',1,'user','u.explore@example.com','org','EXP_ROOT','mode','ABAC','root','Customer','permissions',array())),

    ('A9  empty user string',                           false,
       'E1','Customer','ignored',
       named_struct('tenant',1,'user','','org','EXP_ROOT','mode','ABAC','root','Customer','permissions',array()))
  AS t(scenario, expected, entity_id, object_type, org_id, ctx)
)
SELECT scenario, expected, actual,
       CASE WHEN expected = actual THEN 'ok' ELSE '*** MISMATCH (see Part 0) ***' END AS verdict
FROM probe ORDER BY scenario;


-- =====================================================================
-- PART C — RBAC_ABAC, explained by observation  ("rbac_abac???")
-- The root branch, in RBAC_ABAC mode, replaces "explicit assignment" with:
--     org_id IN (SELECT orgID FROM orgHierarchy WHERE parentOrgID = ctx.org AND isDeleted=false)
-- i.e. "show a root row when its org column is a DIRECT CHILD of my org (or my org
-- itself, via the self-loop)". NOTE: it is parentOrgID = ctx.org — a SINGLE LEVEL,
-- not a recursive subtree. The grandchild below proves it (expected FALSE).
-- =====================================================================
WITH rbac AS (
  SELECT org_id, expected,
         abac_tpcds.tpcds_1_delta.abac_row_filter('any','Customer', org_id,
           named_struct('tenant',1,'user','u.explore@example.com','org','EXP_ROOT',
                        'mode','RBAC_ABAC','root','Customer','permissions',array())) AS visible
  FROM VALUES
    ('EXP_ROOT',  true ),   -- my own org (self-loop row makes it a child of itself)
    ('EXP_CHILD', true ),   -- direct child of EXP_ROOT  -> visible
    ('EXP_GRAND', false),   -- grandchild (parent = EXP_CHILD) -> NOT visible: single-level only
    ('EXP_OTHER', false)    -- not in the tree at all
  AS t(org_id, expected)
)
SELECT org_id, expected, visible,
       CASE WHEN expected = visible THEN 'ok' ELSE '*** unexpected ***' END AS verdict
FROM rbac ORDER BY org_id;

-- RBAC_ABAC only relaxes the ROOT type. A non-root table still needs the permissions
-- branch — org membership does nothing for it. (expected FALSE unless Item ∈ permissions)
SELECT abac_tpcds.tpcds_1_delta.abac_row_filter('any','Item','EXP_CHILD',
         named_struct('tenant',1,'user','u.explore@example.com','org','EXP_ROOT',
                      'mode','RBAC_ABAC','root','Customer','permissions',array())) AS item_under_rbac_no_perm;

-- To make it a TRUE subtree (grandchildren included), the branch would need a
-- recursive walk instead of a single equality. Reference form (does NOT change the
-- deployed function — run it to see the full descendant set for EXP_ROOT):
WITH RECURSIVE subtree AS (
  SELECT orgID, parentOrgID FROM abac_tpcds.tpcds_1_delta.orgHierarchy
    WHERE parentOrgID = 'EXP_ROOT' AND isDeleted = false
  UNION ALL
  SELECT o.orgID, o.parentOrgID FROM abac_tpcds.tpcds_1_delta.orgHierarchy o
    JOIN subtree s ON o.parentOrgID = s.orgID AND o.isDeleted = false
)
SELECT 'recursive subtree of EXP_ROOT' AS note, collect_set(orgID) AS members FROM subtree;


-- =====================================================================
-- PART D — METADATA TABLE VALUES  (axis D)
-- The row filter reads isActive / isDeleted flags. These probes reuse the fixture
-- from Part 1 (EXP_A2 inactive, EXP_A3 deleted, E_ESADEL soft-deleted ESA row) to
-- show each flag flipping a row off. All expected FALSE.
-- =====================================================================
WITH flags AS (
  SELECT scenario, entity_id,
         abac_tpcds.tpcds_1_delta.abac_row_filter(entity_id,'Customer','x',
           named_struct('tenant',1,'user','u.explore@example.com','org','EXP_ROOT',
                        'mode','ABAC','root','Customer','permissions',array())) AS visible
  FROM VALUES
    ('D1  assignment isActive=false', 'E_INACTIVE'),  -- ABAC_Assignment.isActive=false  -> hidden
    ('D2  assignment isDeleted=true', 'E_DELETED'),   -- ABAC_Assignment.isDeleted=true  -> hidden
    ('D3  ESA row isDeleted=true',    'E_ESADEL'),    -- ESA.isDeleted=true               -> hidden
    ('D4  control: still visible',    'E1')           -- unchanged -> TRUE (sanity)
  AS t(scenario, entity_id)
)
SELECT scenario, visible,
       CASE WHEN scenario LIKE 'D4%' THEN visible ELSE NOT visible END AS behaves_as_expected
FROM flags ORDER BY scenario;

-- Which metadata tables the filter ACTUALLY reads (so you know what to mutate):
--   ABAC_EntitySubjectAssignment + ABAC_Assignment : always
--   UserGroupMembers                                : only USER_GROUP grants
--   orgHierarchy                                    : only RBAC_ABAC mode
--   ABAC_AssignmentPermission                       : NEVER by the row filter (masking only)


-- =====================================================================
-- PART E — CONFIRM ONE ROW VIA THE REAL OAUTH / JDBC PATH  (axis B, real)
-- Parts A-D prove the LOGIC as the owner. To prove the CLAIM plumbing, run the same
-- ctx as an OAuth claim as the service principal (owners bypass policies, so this
-- must NOT be run as you). This needs the fixture entity E1 to exist on a REAL table
-- row — it doesn't — so use your real seed's assigned entity/user instead, e.g.:
--
--   java -jar JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar \
--     '{"tenant":1,"user":"<a real subjectID from ABAC_EntitySubjectAssignment>",
--       "org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
--     "SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer"
--
-- The count must match what Part A / 06_validate_row_filter.sql predict for that
-- same ctx. If they differ, the OAuth claim isn't reaching the filter (see the
-- dev-environment checklist in EXPLORE_BEHAVIOURS.md).
-- See first_jdbc_script.md (curl) and OAUTH_JDBC_FLOW.md §6 for the full commands.


-- =====================================================================
-- PART Z — TEARDOWN (run this even if you stopped early)
-- Removes ONLY the synthetic fixture. Leaves your real seed untouched.
-- =====================================================================
DELETE FROM abac_tpcds.tpcds_1_delta.ABAC_Assignment
  WHERE id IN ('EXP_A1','EXP_A2','EXP_A3');
DELETE FROM abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment
  WHERE assignmentID IN ('EXP_A1','EXP_A2','EXP_A3');
DELETE FROM abac_tpcds.tpcds_1_delta.UserGroupMembers
  WHERE groupID = 'g.explore';
DELETE FROM abac_tpcds.tpcds_1_delta.orgHierarchy
  WHERE orgID IN ('EXP_ROOT','EXP_CHILD','EXP_GRAND');
