CREATE OR REPLACE FUNCTION `@DBNAME`.`abac_row_filter`(entity_id string, object_type string, org_id string, ctx STRUCT<tenant: int, user: string, org: string, mode: string, root: string, permissions: array<string>>)
RETURNS BOOLEAN
RETURN (
	ctx.mode = 'DISABLE'
	-- If we aren't on the root, just check for subobject view permission, e.g. assets.risks.view
	OR (
		ctx.root <> object_type
		AND array_contains(ctx.permissions, object_type)
	)
	-- Actual ABAC checks
	OR (
		ctx.root = object_type
		AND (
			-- RBAC_ABAC means we have basic or advanced field permission, so show everything in the org tree
			(
				ctx.mode = 'RBAC_ABAC'
				AND org_id IN (SELECT orgID FROM `@DBNAME`.orgHierarchy WHERE parentOrgID = ctx.org AND isDeleted = false)
			)
			-- Check that we have any kind of access to the entity
			OR
			EXISTS (
				SELECT 1
				FROM `@DBNAME`.ABAC_EntitySubjectAssignment esa
				JOIN `@DBNAME`.ABAC_Assignment a
					ON esa.assignmentID = a.id
					AND a.isActive
					AND a.isDeleted = false
				LEFT JOIN `@DBNAME`.UserGroupMembers ugm
					ON esa.subjectType = 'USER_GROUP'
					AND esa.subjectID = ugm.groupID
					AND ugm.memberID = ctx.user
					AND ugm.isDeleted = false
				WHERE esa.isDeleted = false
				AND esa.entityID = entity_id
				AND esa.objectType = object_type
				AND (ugm.memberID IS NOT NULL OR (esa.subjectType = 'USER_ID' AND esa.subjectID = ctx.user))
			)
		)
	)
)
