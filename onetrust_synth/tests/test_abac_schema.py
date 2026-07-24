from onetrust_synth.abac_schema import (
    ABAC_ASSIGNMENT_COLUMNS, ABAC_ASSIGNMENT_PERMISSION_COLUMNS,
    ABAC_ENTITY_SUBJECT_ASSIGNMENT_COLUMNS, USER_GROUP_MEMBERS_COLUMNS,
    ORG_HIERARCHY_BASE_COLUMNS,
)


def test_abac_assignment_matches_rtf_plus_deployed_audit_columns():
    assert ABAC_ASSIGNMENT_COLUMNS == [
        "id", "guid", "staticIdentifier", "name", "objectType", "sourceType",
        "isActive", "createdBy", "createDT", "updatedBy", "updateDT",
        "eventTime", "recModifiedTime", "tenantHash", "isDeleted",
    ]


def test_abac_assignment_permission_matches_rtf_plus_deployed_audit_columns():
    assert ABAC_ASSIGNMENT_PERMISSION_COLUMNS == [
        "assignmentId", "name", "createdBy", "createDT", "updatedBy", "updateDT",
        "eventTime", "recModifiedTime", "tenantHash", "isDeleted",
    ]


def test_entity_subject_assignment_matches_rtf():
    assert ABAC_ENTITY_SUBJECT_ASSIGNMENT_COLUMNS == [
        "assignmentId", "policyId", "entityId", "entityOrganizationId",
        "subjectId", "subjectType", "objectType", "updateDT", "eventTime",
        "recModifiedTime", "tenantHash", "isDeleted",
    ]


def test_user_group_members_matches_rtf_exactly_no_profile_csv_entry():
    assert USER_GROUP_MEMBERS_COLUMNS == [
        "memberId", "groupId", "eventTime", "recModifiedTime", "isDeleted", "tenantHash",
    ]


def test_org_hierarchy_base_matches_rtf():
    assert ORG_HIERARCHY_BASE_COLUMNS == [
        "rootOrgId", "rootOrgName", "orgId", "orgName", "parentOrgId",
        "parentOrgName", "eventTime", "recModifiedTime", "isDeleted", "tenantHash",
    ]
