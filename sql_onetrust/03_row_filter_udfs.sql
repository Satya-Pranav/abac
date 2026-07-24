-- =====================================================================
-- 03_row_filter_udfs.sql   -> abac_onetrust.onetrust_sim
-- Same customer semantics as sql/04_helper_udfs.sql + sql/05_dataset_udfs.sql,
-- pointed at abac_onetrust and the real OneTrust column names (entityId/subjectId/
-- assignmentId/objectType, camelCase per the RTF DDL -- see design doc section 4).
--
-- Phase 1 has no OAuth wiring, so there is no live get_user_context(); the wrapper
-- below runs on the deterministic get_test_user_context() (mirrors the TPC-DS POC's
-- abac_row_filter_test_wrapper, collapsed into the one wrapper Phase 1 needs).
-- =====================================================================

-- ---------------------------------------------------------------------
-- get_test_user_context() : deterministic context for Phase 1 test-case validation.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
COMMENT 'Deterministic ABAC context for Phase 1 test-case validation'
RETURN named_struct(
  'tenant',      1,
  'user',        'u.assessment.owner@example.com',
  'org',         '100',
  'mode',        'ABAC',
  'root',        'ASSESSMENT',
  'permissions', array('TEMPLATE')
);

-- ---------------------------------------------------------------------
-- entity_type_to_object_type() : raw table/column type value -> canonical ABAC object type.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.entity_type_to_object_type(entity_type STRING)
RETURNS STRING
COMMENT 'Normalizes a raw table/column type value to the canonical ABAC object type. NOT
a plain upper() -- "Processing Activities" hyphenates to "PROCESSING-ACTIVITIES" in the
real entityTypeReference vocabulary (verified against real sample data; see
onetrust_synth/config.py INVENTORY_TYPE_TO_OBJECT_TYPE for the Python-side source of truth).'
RETURN CASE
  WHEN upper(entity_type) = 'PROCESSING ACTIVITIES' THEN 'PROCESSING-ACTIVITIES'
  ELSE upper(entity_type)
END;

-- ---------------------------------------------------------------------
-- abac_row_filter(entity_id, object_type, org_id, ctx) -> BOOLEAN
-- Body is the customer's create_row_filter.sql VERBATIM (only table/column names
-- adapted to the real OneTrust ABAC schema -- see onetrust_synth/abac_schema.py).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.abac_row_filter(
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
      -- RBAC_ABAC: show everything in the user's org subtree.
      -- ABAC_OrgHierarchy is the view over OrgHierarchyBase filtered to isDeleted IS NOT TRUE
      -- (see onetrust_synth/abac_tables.py build_org_hierarchy_view_sql), so no separate
      -- isDeleted filter is needed here. Named ABAC_OrgHierarchy (not OrgHierarchy) to avoid
      -- a case-insensitive collision with the real "orghierarchy" main table in this schema.
      (
        ctx.mode = 'RBAC_ABAC'
        AND org_id IN (
          SELECT orgId FROM abac_onetrust.onetrust_sim.ABAC_OrgHierarchy
          WHERE parentOrgId = ctx.org
        )
      )
      -- ABAC: explicit assignment to the user, or to a group they belong to
      OR EXISTS (
        SELECT 1
        FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment esa
        JOIN abac_onetrust.onetrust_sim.ABAC_Assignment a
          ON esa.assignmentId = a.id
          AND a.isActive
          AND a.isDeleted = false
        LEFT JOIN abac_onetrust.onetrust_sim.UserGroupMembers ugm
          ON esa.subjectType = 'USER_GROUP'
          AND esa.subjectId = ugm.groupId
          AND ugm.memberId = ctx.user
          AND ugm.isDeleted = false
        WHERE esa.isDeleted = false
          AND esa.entityId = entity_id
          AND esa.objectType = object_type
          AND (
            ugm.memberId IS NOT NULL
            OR (esa.subjectType = 'USER_ID' AND esa.subjectId = ctx.user)
          )
      )
    )
  )
);

-- ---------------------------------------------------------------------
-- abac_row_filter_wrapper : the function policies bind to. Injects the deterministic
-- test context (Phase 1 has no live OAuth claim source -- see file header).
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.abac_row_filter_wrapper(
  entity_id STRING, object_type STRING, org_id STRING
)
RETURNS BOOLEAN
RETURN abac_onetrust.onetrust_sim.abac_row_filter(
  entity_id, abac_onetrust.onetrust_sim.entity_type_to_object_type(object_type), org_id,
  abac_onetrust.onetrust_sim.get_test_user_context()
);

-- Expected: all 4 CREATE OR REPLACE FUNCTION statements succeed with no error.
