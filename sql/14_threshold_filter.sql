-- =====================================================================
-- 14_threshold_filter.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- DEMO: a RANGE / THRESHOLD row filter on the real `inventory` table — "show every row whose
-- inv_quantity_on_hand is >= the assigned value", instead of the deployed filter's EXACT match.
--
-- Applied to `inventory` (verified unused by any other case/policy/tag). Thresholds on
-- inv_quantity_on_hand: assign the analyst a value of 500 -> they see only inventory rows with
-- quantity_on_hand >= 500.
--
-- This is a SEPARATE function; it does NOT touch the deployed abac_row_filter, so the exact-match
-- A/B/R/N cases are unaffected.
--
-- The ONLY substantive change vs the deployed 3b EXISTS is the match predicate:
--     deployed:  AND esa.entityID = entity_id
--     threshold: AND try_cast(entity_id AS BIGINT) >= try_cast(esa.entityID AS BIGINT)
--   * try_cast(... AS BIGINT): entityID columns are STRING, and a raw string '>=' is LEXICOGRAPHIC
--     ('10' >= '5' is FALSE), so you MUST cast to a number. try_cast fails safe (NULL -> row hidden).
--   * '>=' = the assigned value AND above. Use '>' for strictly above, '<='/'<' for below.
--   (Branches 2 (permissions) and 3a (RBAC_ABAC) are omitted here to keep the demo focused on 3b.)
--
-- SP the JDBC suite authenticates as: 76d5804d-d302-4014-a1d3-d846f02c84ef
-- =====================================================================

-- ---- 1. Threshold filter function (deployed shape, only 3b's predicate changed) ----
CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.abac_row_filter_threshold(
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
      FROM abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment esa
      JOIN abac_tpcds.tpcds_1_delta.ABAC_Assignment a
        ON esa.assignmentID = a.id AND a.isActive AND a.isDeleted = false
      LEFT JOIN abac_tpcds.tpcds_1_delta.UserGroupMembers ugm
        ON esa.subjectType = 'USER_GROUP'
       AND esa.subjectID   = ugm.groupID
       AND ugm.memberID    = ctx.user
       AND ugm.isDeleted   = false
      WHERE esa.isDeleted = false
        AND esa.objectType = object_type
        AND try_cast(entity_id AS BIGINT) >= try_cast(esa.entityID AS BIGINT)   -- <<< threshold (was '=')
        AND ( ugm.memberID IS NOT NULL
              OR (esa.subjectType = 'USER_ID' AND esa.subjectID = ctx.user) )
    )
  )
);

-- wrapper (injects the live claim + maps the object type) — same pattern as the deployed wrapper
CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.abac_row_filter_threshold_wrapper(
  entity_id STRING, object_type STRING, org_id STRING
)
RETURNS BOOLEAN
RETURN abac_tpcds.tpcds_1_delta.abac_row_filter_threshold(
  entity_id,
  abac_tpcds.abac.entity_type_to_object_type(object_type),
  org_id,
  abac_tpcds.abac.get_user_context()
);

-- ---- 2. Govern `inventory`: grant + tag the threshold column ----
GRANT SELECT ON TABLE abac_tpcds.tpcds_1_delta.inventory
  TO `76d5804d-d302-4014-a1d3-d846f02c84ef`;
-- inv_quantity_on_hand is the dimension we threshold on (the "id" the filter compares).
ALTER TABLE abac_tpcds.tpcds_1_delta.inventory
  ALTER COLUMN inv_quantity_on_hand SET TAGS ('abac_column_id' = 'true');

-- ---- 3. Policy binds the THRESHOLD wrapper (object type literal 'Inventory', org literal '100') ----
CREATE OR REPLACE POLICY inventory_threshold_policy
ON TABLE abac_tpcds.tpcds_1_delta.inventory
ROW FILTER abac_tpcds.tpcds_1_delta.abac_row_filter_threshold_wrapper
TO `76d5804d-d302-4014-a1d3-d846f02c84ef`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'Inventory', '100');

-- ---- 4. Assignment: threshold = 500 for the analyst on object type 'Inventory' ----
INSERT INTO abac_tpcds.tpcds_1_delta.ABAC_Assignment VALUES ('assignment_inventory_1', true, false);
INSERT INTO abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment VALUES
  ('500', 'Inventory', 'assignment_inventory_1', 'USER_ID', 'u.analyst1@example.com', false);

-- Expect (as the analyst, root=Inventory): only inventory rows with inv_quantity_on_hand >= 500 are
-- visible. So count(*) > 0, and count(*) WHERE inv_quantity_on_hand < 500 is exactly 0 (nothing below
-- the threshold leaks). With '>' it would be strictly-greater; with '<=' it would flip to "and below".

-- ---- TEARDOWN ----
--   DROP POLICY IF EXISTS inventory_threshold_policy ON TABLE abac_tpcds.tpcds_1_delta.inventory;
--   ALTER TABLE abac_tpcds.tpcds_1_delta.inventory ALTER COLUMN inv_quantity_on_hand UNSET TAGS ('abac_column_id');
--   DROP FUNCTION IF EXISTS abac_tpcds.tpcds_1_delta.abac_row_filter_threshold_wrapper;
--   DROP FUNCTION IF EXISTS abac_tpcds.tpcds_1_delta.abac_row_filter_threshold;
--   DELETE FROM abac_tpcds.tpcds_1_delta.ABAC_Assignment WHERE id = 'assignment_inventory_1';
--   DELETE FROM abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment WHERE assignmentID = 'assignment_inventory_1';
