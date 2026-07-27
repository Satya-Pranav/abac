-- =====================================================================
-- 10_threshold_filter.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported from sql/14_threshold_filter.sql (TPC-DS), for the OneTrust suite (OT-TH1..OT-TH3).
-- A RANGE / THRESHOLD row filter -- "show every row whose quantity is >= the assigned value",
-- instead of the deployed filter's EXACT match. Isolated table (abac_onetrust.abac_thresh),
-- since there's no real OneTrust table already in this suite's scope playing the role TPC-DS's
-- `inventory` fact table does -- but reads from the REAL shared ABAC_EntitySubjectAssignment/
-- ABAC_Assignment tables (namespaced 'phase1-thresh-seed'), same pattern as
-- sql_onetrust/09_onboard_new_tables.sql.
--
-- This is a SEPARATE function; it does NOT touch abac_row_filter_wrapper_oauth, so every other
-- OneTrust case (Tier A, META) is unaffected.
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_thresh;

CREATE OR REPLACE TABLE abac_onetrust.abac_thresh.thresh_inventory (id BIGINT, quantity BIGINT);
INSERT INTO abac_onetrust.abac_thresh.thresh_inventory SELECT id, id * 25 FROM range(1, 21);
-- quantities: 25, 50, 75, ..., 500 (20 rows)

-- ---- 1. Threshold filter function (same shape as abac_row_filter, only 3b's predicate changed) ----
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.abac_row_filter_threshold(
  entity_id   STRING,
  object_type STRING,
  org_id      STRING,
  ctx STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
)
RETURNS BOOLEAN
RETURN (
  ctx.mode = 'DISABLE'
  OR (
    ctx.root = object_type
    AND EXISTS (
      SELECT 1
      FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment esa
      JOIN abac_onetrust.onetrust_sim.ABAC_Assignment a
        ON esa.assignmentId = a.id AND a.isActive AND a.isDeleted = false
      LEFT JOIN abac_onetrust.onetrust_sim.UserGroupMembers ugm
        ON esa.subjectType = 'USER_GROUP'
       AND esa.subjectId   = ugm.groupId
       AND ugm.memberId    = ctx.user
       AND ugm.isDeleted   = false
      WHERE esa.isDeleted = false
        AND esa.objectType = object_type
        AND try_cast(entity_id AS BIGINT) >= try_cast(esa.entityId AS BIGINT)   -- <<< threshold (was '=')
        AND ( ugm.memberId IS NOT NULL
              OR (esa.subjectType = 'USER_ID' AND esa.subjectId = ctx.user) )
    )
  )
);

CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.abac_row_filter_threshold_wrapper(
  entity_id STRING, object_type STRING, org_id STRING
)
RETURNS BOOLEAN
RETURN abac_onetrust.onetrust_sim.abac_row_filter_threshold(
  entity_id,
  abac_onetrust.onetrust_sim.entity_type_to_object_type(object_type),
  org_id,
  abac_onetrust.onetrust_sim.get_user_context());

ALTER TABLE abac_onetrust.abac_thresh.thresh_inventory ALTER COLUMN quantity SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE POLICY thresh_inventory_policy
ON TABLE abac_onetrust.abac_thresh.thresh_inventory
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_threshold_wrapper
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'THRESH_INVENTORY', '100');

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_thresh                  TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_thresh.thresh_inventory       TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.onetrust_sim.abac_row_filter_threshold_wrapper TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.onetrust_sim.abac_row_filter_threshold         TO `<ONETRUST_SP>`;

-- assignment: threshold = 250 for u.thresh.tester on object type 'THRESH_INVENTORY'
DELETE FROM abac_onetrust.onetrust_sim.ABAC_Assignment WHERE staticIdentifier = 'phase1-thresh-seed';
DELETE FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-thresh-seed';

INSERT INTO abac_onetrust.onetrust_sim.ABAC_Assignment
  (id, guid, staticIdentifier, name, objectType, sourceType, isActive, createdBy, createDT, updatedBy, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900201, uuid(), 'phase1-thresh-seed', 'Owner', 'THRESH_INVENTORY', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-thresh-seed', false;

INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
VALUES (900201, NULL, '250', NULL, 'u.thresh.tester@example.com', 'USER_ID', 'THRESH_INVENTORY', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-thresh-seed', false);

-- Expect (as u.thresh.tester, root=THRESH_INVENTORY): quantities 250..500 visible (11 of 20 rows;
-- quantity=25*id, so id=10..20). count(*) WHERE quantity < 250 -> 0. min(quantity) -> 250.

-- ---- TEARDOWN ----
--   DROP POLICY IF EXISTS thresh_inventory_policy ON TABLE abac_onetrust.abac_thresh.thresh_inventory;
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_thresh CASCADE;
--   DROP FUNCTION IF EXISTS abac_onetrust.onetrust_sim.abac_row_filter_threshold_wrapper;
--   DROP FUNCTION IF EXISTS abac_onetrust.onetrust_sim.abac_row_filter_threshold;
--   DELETE FROM abac_onetrust.onetrust_sim.ABAC_Assignment WHERE staticIdentifier = 'phase1-thresh-seed';
--   DELETE FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-thresh-seed';
