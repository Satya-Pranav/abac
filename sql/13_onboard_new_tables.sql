-- =====================================================================
-- 13_onboard_new_tables.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Onboards 4 NEW governed tables (promotion, store, call_center, ship_mode) under the
-- SAME deployed row filter (abac_row_filter_wrapper), then seeds ABAC metadata rows that
-- exercise one condition each. Also seeds a soft-deleted orgHierarchy child (+ a live control).
--
-- Conditions exercised (all honored by the deployed 3-branch filter):
--   promotion    -> esa.isDeleted = true            -> branch 3b EXISTS excluded  -> 0  (negative)
--   store        -> esa.subjectType = 'USER_GROUP'  -> group path via ugm         -> 1  (positive)
--   call_center  -> ABAC_Assignment.isActive = false-> JOIN a.isActive fails       -> 0  (negative)
--   ship_mode    -> ABAC_Assignment.isDeleted = true-> AND a.isDeleted=false fails  -> 0  (negative)
--   orgHierarchy -> a child with isDeleted=true      -> excluded from branch 3a set -> 0  (negative)
--                   (+ same address as a LIVE child of LIVE_ORG -> 3a includes it   -> >0 control)
--
-- object type: we pass the PascalCase literal (e.g. 'Promotion') as the policy's type arg. It flows
-- through entity_type_to_object_type()'s ELSE branch unchanged, so NO edit to sql/04 is needed and
-- esa.objectType just has to equal it. ('store' is also explicitly mapped -> 'Store' — same result.)
--
-- SP the JDBC suite authenticates as (policies target it; owners bypass row filters):
--   76d5804d-d302-4014-a1d3-d846f02c84ef
-- If DBR nudges the MATCH COLUMNS grammar, accept it — intent: id (+org) matched by tag.
-- =====================================================================

-- =========================================================
-- PART A — grant + tag + row-filter policy per new table
-- =========================================================
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.promotion   TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.store        TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.call_center  TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.ship_mode    TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;

-- id tags (org is a literal '100' for the id-only tables, like item; store has id==org)
ALTER TABLE abac_tpcds.tpcds_1_delta.promotion   ALTER COLUMN p_promo_sk        SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_tpcds.tpcds_1_delta.call_center ALTER COLUMN cc_call_center_sk SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_tpcds.tpcds_1_delta.ship_mode   ALTER COLUMN sm_ship_mode_sk   SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_tpcds.tpcds_1_delta.store       ALTER COLUMN s_store_sk        SET TAGS ('abac_column_id' = 'true', 'abac_column_org' = 'true');

-- promotion (id only, literal org '100' — mirrors the item policy)
CREATE OR REPLACE POLICY tpcds_1_delta_promotion_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.promotion
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'Promotion', '100');

CREATE OR REPLACE POLICY tpcds_1_delta_call_center_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.call_center
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'CallCenter', '100');

CREATE OR REPLACE POLICY tpcds_1_delta_ship_mode_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.ship_mode
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'ShipMode', '100');

-- store (id == org on s_store_sk)
CREATE OR REPLACE POLICY tpcds_1_delta_store_abac_policy
ON TABLE abac_tpcds.tpcds_1_delta.store
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id, has_tag('abac_column_org') AS org
USING COLUMNS (id, 'Store', org);

-- =========================================================
-- PART B — ABAC_Assignment rows (the on/off + soft-delete switches)
-- =========================================================
INSERT INTO abac_tpcds.tpcds_1_delta.ABAC_Assignment VALUES
  ('assignment_promo_1', true,  false),   -- promotion: assignment normal (the HIDE comes from esa.isDeleted)
  ('assignment_store_1', true,  false),   -- store:     assignment normal (group grants it)
  ('assignment_cc_1',    false, false),   -- call_center: isActive = FALSE  -> hides
  ('assignment_ship_1',  true,  true);    -- ship_mode:   isDeleted = TRUE  -> hides

-- =========================================================
-- PART C — ABAC_EntitySubjectAssignment rows (entityID pulled from real ids)
--   entityID is the STRING form of each table's id-tagged column value.
-- =========================================================
-- promotion: esa.isDeleted = TRUE (negative)
INSERT INTO abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment
  SELECT CAST(min(p_promo_sk) AS STRING), 'Promotion', 'assignment_promo_1', 'USER_ID', 'u.analyst1@example.com', true
  FROM abac_tpcds.tpcds_1_delta.promotion;

-- store: subjectType = USER_GROUP -> group 'test_group_1' (positive; analyst1 is a member, see Part D)
INSERT INTO abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment
  SELECT CAST(min(s_store_sk) AS STRING), 'Store', 'assignment_store_1', 'USER_GROUP', 'test_group_1', false
  FROM abac_tpcds.tpcds_1_delta.store;

-- call_center: normal esa, but its assignment is inactive (negative)
INSERT INTO abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment
  SELECT CAST(min(cc_call_center_sk) AS STRING), 'CallCenter', 'assignment_cc_1', 'USER_ID', 'u.analyst1@example.com', false
  FROM abac_tpcds.tpcds_1_delta.call_center;

-- ship_mode: normal esa, but its assignment is soft-deleted (negative)
INSERT INTO abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment
  SELECT CAST(min(sm_ship_mode_sk) AS STRING), 'ShipMode', 'assignment_ship_1', 'USER_ID', 'u.analyst1@example.com', false
  FROM abac_tpcds.tpcds_1_delta.ship_mode;

-- =========================================================
-- PART D — UserGroupMembers (your row: analyst1 is a member of test_group_1)
-- =========================================================
INSERT INTO abac_tpcds.tpcds_1_delta.UserGroupMembers VALUES
  ('test_group_1', 'u.analyst1@example.com', false);

-- =========================================================
-- PART E — orgHierarchy: same real customer address as a DELETED child (DEL_ORG) and a LIVE child (LIVE_ORG)
--   RBAC_ABAC org=DEL_ORG  -> child isDeleted=true  -> excluded -> 0   (the soft-delete test)
--   RBAC_ABAC org=LIVE_ORG -> child isDeleted=false -> included -> >0  (control: same address, live)
-- =========================================================
INSERT INTO abac_tpcds.tpcds_1_delta.orgHierarchy
  SELECT CAST(min(c_current_addr_sk) AS STRING), 'DEL_ORG', true
  FROM abac_tpcds.tpcds_1_delta.customer WHERE c_current_addr_sk IS NOT NULL;
INSERT INTO abac_tpcds.tpcds_1_delta.orgHierarchy
  SELECT CAST(min(c_current_addr_sk) AS STRING), 'LIVE_ORG', false
  FROM abac_tpcds.tpcds_1_delta.customer WHERE c_current_addr_sk IS NOT NULL;

-- =========================================================
-- TEARDOWN (run to fully remove this experiment)
-- =========================================================
--   DROP POLICY IF EXISTS tpcds_1_delta_promotion_abac_policy   ON TABLE abac_tpcds.tpcds_1_delta.promotion;
--   DROP POLICY IF EXISTS tpcds_1_delta_call_center_abac_policy ON TABLE abac_tpcds.tpcds_1_delta.call_center;
--   DROP POLICY IF EXISTS tpcds_1_delta_ship_mode_abac_policy   ON TABLE abac_tpcds.tpcds_1_delta.ship_mode;
--   DROP POLICY IF EXISTS tpcds_1_delta_store_abac_policy       ON TABLE abac_tpcds.tpcds_1_delta.store;
--   DELETE FROM abac_tpcds.tpcds_1_delta.ABAC_Assignment WHERE id IN ('assignment_promo_1','assignment_store_1','assignment_cc_1','assignment_ship_1');
--   DELETE FROM abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment WHERE assignmentID IN ('assignment_promo_1','assignment_store_1','assignment_cc_1','assignment_ship_1');
--   DELETE FROM abac_tpcds.tpcds_1_delta.UserGroupMembers WHERE groupID = 'test_group_1';
--   DELETE FROM abac_tpcds.tpcds_1_delta.orgHierarchy WHERE parentOrgID IN ('DEL_ORG','LIVE_ORG');
