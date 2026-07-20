CREATE OR REPLACE FUNCTION `@DBNAME`.`abac_should_mask_column`(entity_id string, object_type string, permission string, ctx STRUCT<tenant: int, user: string, org: string, mode: string, root: string, permissions: array<string>>)
RETURNS BOOLEAN
RETURN NOT EXISTS (
	SELECT 1
	FROM `@DBNAME`.ABAC_EntitySubjectAssignment esa
	JOIN `@DBNAME`.ABAC_Assignment a
		ON esa.assignmentID = a.id
		AND a.isActive
		AND a.isDeleted = false
	JOIN `@DBNAME`.ABAC_AssignmentPermission ap
		ON ap.assignmentID = a.id
		AND (ap.name = permission OR replace(ap.name, '.advanced.', '.basic.') = permission)
		AND ap.isDeleted = false
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
