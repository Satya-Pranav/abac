-- =====================================================================
-- 05_dataset_udfs.sql   -> abac_tpcds.tpcds_1_delta
-- The row filter + wrappers. Body is the customer's create_row_filter.sql VERBATIM
-- (only names adapted). The permission check is array_contains(ctx.permissions,
-- object_type) — object types, NOT '*.view' strings (see README §8).
-- =====================================================================

-- ---------------------------------------------------------------------
-- abac_row_filter(entity_id, object_type, org_id, ctx) -> BOOLEAN
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.abac_row_filter(
  entity_id   STRING,
  object_type STRING,
  org_id      STRING,
  ctx STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
)
RETURNS BOOLEAN
RETURN (
  ctx.mode = 'DISABLE'
  -- Not the root type: allow if the user may view this related object type
  OR (
    ctx.root <> object_type
    AND array_contains(ctx.permissions, object_type)
  )
  -- The root type: real ABAC / RBAC_ABAC checks
  OR (
    ctx.root = object_type
    AND (
      -- RBAC_ABAC: show everything in the user's org subtree
      (
        ctx.mode = 'RBAC_ABAC'
        AND org_id IN (
          SELECT orgID FROM abac_tpcds.tpcds_1_delta.orgHierarchy
          WHERE parentOrgID = ctx.org AND isDeleted = false
        )
      )
      -- ABAC: explicit assignment to the user, or to a group they belong to
      OR EXISTS (
        SELECT 1
        FROM abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment esa
        JOIN abac_tpcds.tpcds_1_delta.ABAC_Assignment a
          ON esa.assignmentID = a.id
          AND a.isActive
          AND a.isDeleted = false
        LEFT JOIN abac_tpcds.tpcds_1_delta.UserGroupMembers ugm
          ON esa.subjectType = 'USER_GROUP'
          AND esa.subjectID = ugm.groupID
          AND ugm.memberID = ctx.user
          AND ugm.isDeleted = false
        WHERE esa.isDeleted = false
          AND esa.entityID = entity_id
          AND esa.objectType = object_type
          AND (
            ugm.memberID IS NOT NULL
            OR (esa.subjectType = 'USER_ID' AND esa.subjectID = ctx.user)
          )
      )
    )
  )
);

-- ---------------------------------------------------------------------
-- abac_row_filter_wrapper : the function policies bind to. Injects live context.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.abac_row_filter_wrapper(
  entity_id STRING, object_type STRING, org_id STRING
)
RETURNS BOOLEAN
RETURN abac_tpcds.tpcds_1_delta.abac_row_filter(
  entity_id,
  abac_tpcds.abac.entity_type_to_object_type(object_type),
  org_id,
  abac_tpcds.abac.get_user_context()
);

-- ---------------------------------------------------------------------
-- abac_row_filter_test_wrapper : same, but uses the deterministic test context.
-- Lets the OWNER validate the logic in plain WHERE clauses (see 06).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.abac_row_filter_test_wrapper(
  entity_id STRING, object_type STRING, org_id STRING
)
RETURNS BOOLEAN
RETURN abac_tpcds.tpcds_1_delta.abac_row_filter(
  entity_id,
  abac_tpcds.abac.entity_type_to_object_type(object_type),
  org_id,
  abac_tpcds.abac.get_test_user_context()
);
