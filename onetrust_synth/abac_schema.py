"""
Authoritative column lists for the 5 legacy ABAC tables, per design doc section 4:
RTF DDL (abac_docs/customer_data/*.rtf) is the base; createdBy/updatedBy are added
to ABAC_Assignment and ABAC_AssignmentPermission because
onetrust_abac_table_profile_results.csv shows them as currently deployed, even
though the RTF template doesn't list them. UserGroupMembers and OrgHierarchyBase
have no profile CSV entry at all — RTF DDL is the only source for those two.
"""

ABAC_ASSIGNMENT_COLUMNS = [
    "id", "guid", "staticIdentifier", "name", "objectType", "sourceType",
    "isActive", "createdBy", "createDT", "updatedBy", "updateDT",
    "eventTime", "recModifiedTime", "tenantHash", "isDeleted",
]

ABAC_ASSIGNMENT_PERMISSION_COLUMNS = [
    "assignmentId", "name", "createdBy", "createDT", "updatedBy", "updateDT",
    "eventTime", "recModifiedTime", "tenantHash", "isDeleted",
]

ABAC_ENTITY_SUBJECT_ASSIGNMENT_COLUMNS = [
    "assignmentId", "policyId", "entityId", "entityOrganizationId",
    "subjectId", "subjectType", "objectType", "updateDT", "eventTime",
    "recModifiedTime", "tenantHash", "isDeleted",
]

USER_GROUP_MEMBERS_COLUMNS = [
    "memberId", "groupId", "eventTime", "recModifiedTime", "isDeleted", "tenantHash",
]

ORG_HIERARCHY_BASE_COLUMNS = [
    "rootOrgId", "rootOrgName", "orgId", "orgName", "parentOrgId",
    "parentOrgName", "eventTime", "recModifiedTime", "isDeleted", "tenantHash",
]
