-- =====================================================================
-- 99_optional_masking.sql   (OPTIONAL — NOT part of the test path)
-- The customer DEFINES this function but applies masking in the APPLICATION layer
-- (only under OAuth), never via a Unity Catalog policy. We do NOT test masking.
-- This file exists only to complete the customer function set for fidelity.
-- Safe to skip entirely.
-- =====================================================================

CREATE OR REPLACE FUNCTION abac_tpcds.tpcds_1_delta.abac_should_mask_column(
  entity_id   STRING,
  object_type STRING,
  permission  STRING,
  ctx STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
)
RETURNS BOOLEAN
COMMENT 'Fidelity only; customer masks in the app layer, not via policy'
RETURN NOT EXISTS (
  SELECT 1
  FROM abac_tpcds.tpcds_1_delta.ABAC_EntitySubjectAssignment esa
  JOIN abac_tpcds.tpcds_1_delta.ABAC_Assignment a
    ON esa.assignmentID = a.id
    AND a.isActive
    AND a.isDeleted = false
  JOIN abac_tpcds.tpcds_1_delta.ABAC_AssignmentPermission ap
    ON ap.assignmentID = a.id
    AND (ap.name = permission OR replace(ap.name, '.advanced.', '.basic.') = permission)
    AND ap.isDeleted = false
  LEFT JOIN abac_tpcds.tpcds_1_delta.UserGroupMembers ugm
    ON esa.subjectType = 'USER_GROUP'
    AND esa.subjectID = ugm.groupID
    AND ugm.memberID = ctx.user
    AND ugm.isDeleted = false
  WHERE esa.isDeleted = false
    AND esa.entityID = entity_id
    AND esa.objectType = object_type
    AND (ugm.memberID IS NOT NULL OR (esa.subjectType = 'USER_ID' AND esa.subjectID = ctx.user))
);
