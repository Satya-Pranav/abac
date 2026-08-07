"""
Parameterized re-emission of sql_onetrust/03_row_filter_udfs.sql, 02_tags.sql, and
04_policies.sql for a given catalog — needed because a second catalog (abac_onetrust_scale)
requires its own copies of the same UDFs/tags/policies, and the original files hardcode
`abac_onetrust` throughout (see design doc section 6). The original sql_onetrust/*.sql files
are left untouched and remain the source of truth for abac_onetrust itself; this module is
only used for the scale-2 catalog.

Extends coverage from the original 4 governed tables to 8 (design doc section 5): the 4 new
ones (cmb_riskrelatedobjects, cmb_inventory, cmb_v_assessment_v4, entitylink_v3) use real
per-row columns rather than literals wherever the profile data confirmed one exists.

POC-scoped extension to 10 (not in the design doc): cmb_v_assessmentquestionresponse_v3
(9.49M rows) and cmb_v_assessmentstagechangetracker_v4 (3.55M rows) -- the two biggest tables
in the dataset -- were previously ungoverned, so any claim/identity passed against them was
inert (no CREATE POLICY row filter existed to read it). Added so the GEN-ESA/GEN-BIG
performance-shortlist queries exercise real Unity Catalog ABAC enforcement instead of a
hand-simulated join against ABAC_EntitySubjectAssignment in the query text.
"""


def build_udf_sql(catalog: str, schema: str = "onetrust_sim") -> list[str]:
    q = f"{catalog}.{schema}"
    return [
        f"""CREATE OR REPLACE FUNCTION {q}.get_test_user_context()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
COMMENT 'Deterministic ABAC context for test-case validation'
RETURN named_struct(
  'tenant',      1,
  'user',        'u.assessment.owner@example.com',
  'org',         '100',
  'mode',        'ABAC',
  'root',        'ASSESSMENT',
  'permissions', array('TEMPLATE')
);""",
        f"""CREATE OR REPLACE FUNCTION {q}.entity_type_to_object_type(entity_type STRING)
RETURNS STRING
COMMENT 'Normalizes a raw table/column type value to the canonical ABAC object type.'
RETURN CASE
  WHEN upper(entity_type) = 'PROCESSING ACTIVITIES' THEN 'PROCESSING-ACTIVITIES'
  ELSE upper(entity_type)
END;""",
        f"""CREATE OR REPLACE FUNCTION {q}.abac_row_filter(
  entity_id   STRING,
  object_type STRING,
  org_id      STRING,
  ctx STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
)
RETURNS BOOLEAN
RETURN (
  ctx.mode = 'DISABLE'
  OR (
    ctx.root <> object_type
    AND array_contains(ctx.permissions, object_type)
  )
  OR (
    ctx.root = object_type
    AND (
      (
        ctx.mode = 'RBAC_ABAC'
        AND org_id IN (
          SELECT orgId FROM {q}.ABAC_OrgHierarchy
          WHERE parentOrgId = ctx.org
        )
      )
      OR EXISTS (
        SELECT 1
        FROM {q}.ABAC_EntitySubjectAssignment esa
        JOIN {q}.ABAC_Assignment a
          ON esa.assignmentId = a.id
          AND a.isActive
          AND a.isDeleted = false
        LEFT JOIN {q}.UserGroupMembers ugm
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
);""",
        f"""CREATE OR REPLACE FUNCTION {q}.abac_row_filter_wrapper(
  entity_id STRING, object_type STRING, org_id STRING
)
RETURNS BOOLEAN
RETURN {q}.abac_row_filter(
  entity_id, {q}.entity_type_to_object_type(object_type), org_id,
  {q}.get_test_user_context()
);""",
    ]


# (table, id_column, type_tag_column_or_None, org_tag_column_or_None)
_TAG_SPEC = [
    ("cmb_assessment", "id", None, None),
    ("cmb_controlimplementation", "id", None, None),
    ("cmb_template", "id", None, None),
    ("cmb_v_inventoryaggregatedrisksummary", "entityID", "inventoryType", "orgID"),
    ("cmb_riskrelatedobjects", "riskId", "entityType", "organizationID"),
    ("cmb_inventory", "id", "inventoryType", None),
    ("cmb_v_assessment_v4", "id", None, "orgID"),
    ("entitylink_v3", "entityid1", "entityid1typereference", None),
    ("cmb_v_assessmentquestionresponse_v3", "assessmentID", None, "orgID"),
    ("cmb_v_assessmentstagechangetracker_v4", "assessmentID", None, "orgID"),
]


def build_tags_sql(catalog: str, schema: str = "onetrust_sim") -> list[str]:
    q = f"{catalog}.{schema}"
    stmts = []
    for table, id_col, type_col, org_col in _TAG_SPEC:
        stmts.append(f"ALTER TABLE {q}.{table} ALTER COLUMN {id_col} SET TAGS ('abac_column_id' = 'true');")
        if type_col:
            stmts.append(f"ALTER TABLE {q}.{table} ALTER COLUMN {type_col} SET TAGS ('abac_column_type' = 'true');")
        if org_col:
            stmts.append(f"ALTER TABLE {q}.{table} ALTER COLUMN {org_col} SET TAGS ('abac_column_org' = 'true');")
    return stmts


# (table, literal_type_or_None, literal_org_or_None) — None means "bind the real tagged column instead"
_POLICY_SPEC = {
    "cmb_assessment": ("ASSESSMENT", "100"),
    "cmb_controlimplementation": ("CONTROL", "100"),
    "cmb_template": ("TEMPLATE", "100"),
    "cmb_v_inventoryaggregatedrisksummary": (None, None),
    "cmb_riskrelatedobjects": (None, None),
    "cmb_inventory": (None, "100"),
    "cmb_v_assessment_v4": ("ASSESSMENT", None),
    "entitylink_v3": (None, "100"),
    "cmb_v_assessmentquestionresponse_v3": ("ASSESSMENT", None),
    "cmb_v_assessmentstagechangetracker_v4": ("ASSESSMENT", None),
}


def _validate_specs():
    """
    Validate that _TAG_SPEC and _POLICY_SPEC agree on which columns are real vs literal.
    Enforces the invariant: if type_col is None in _TAG_SPEC, then literal_type must NOT be None
    in _POLICY_SPEC (and vice versa), ensuring generated policies reference only tagged columns.
    """
    tag_spec_by_table = {t: (id_c, type_c, org_c) for t, id_c, type_c, org_c in _TAG_SPEC}
    for table, (literal_type, literal_org) in _POLICY_SPEC.items():
        if table not in tag_spec_by_table:
            raise ValueError(f"Table '{table}' in _POLICY_SPEC not found in _TAG_SPEC")
        id_c, type_c, org_c = tag_spec_by_table[table]

        # Invariant: type_col is None ⟺ literal_type is not None
        if (type_c is None) != (literal_type is not None):
            raise ValueError(
                f"Table '{table}': type spec mismatch. _TAG_SPEC has type_col={type_c}, "
                f"_POLICY_SPEC has literal_type={literal_type}. "
                f"If type_col is None, literal_type must not be None (and vice versa)."
            )

        # Invariant: org_col is None ⟺ literal_org is not None
        if (org_c is None) != (literal_org is not None):
            raise ValueError(
                f"Table '{table}': org spec mismatch. _TAG_SPEC has org_col={org_c}, "
                f"_POLICY_SPEC has literal_org={literal_org}. "
                f"If org_col is None, literal_org must not be None (and vice versa)."
            )


# Validate specs at module load time to catch inconsistencies early
_validate_specs()


def build_policies_sql(
    catalog: str,
    schema: str = "onetrust_sim",
    service_principal: str = "<SERVICE_PRINCIPAL>",
    row_filter_fn: str = "abac_row_filter_wrapper",
) -> list[str]:
    """
    row_filter_fn defaults to "abac_row_filter_wrapper" (the deterministic test-claim path built
    by build_udf_sql), preserving prior behavior/tests. build_oauth_wiring_sql calls this with
    row_filter_fn="abac_row_filter_wrapper_oauth" to re-point the same 8 policies at the live
    OAuth wrapper instead of duplicating this table-building loop.
    """
    q = f"{catalog}.{schema}"
    stmts = []
    tag_spec_by_table = {t: (id_c, type_c, org_c) for t, id_c, type_c, org_c in _TAG_SPEC}

    for table, (literal_type, literal_org) in _POLICY_SPEC.items():
        _, type_col, org_col = tag_spec_by_table[table]
        match_cols = ["has_tag('abac_column_id') as id"]
        using_cols = ["id"]

        if literal_type is not None:
            using_cols.append(f"'{literal_type}'")
        else:
            match_cols.append("has_tag('abac_column_type') as type")
            using_cols.append("type")

        if literal_org is not None:
            using_cols.append(f"'{literal_org}'")
        else:
            match_cols.append("has_tag('abac_column_org') as org")
            using_cols.append("org")

        stmts.append(
            f"CREATE OR REPLACE POLICY {schema}_{table}_abac_policy\n"
            f"ON TABLE {q}.{table}\n"
            f"ROW FILTER {q}.{row_filter_fn}\n"
            f"TO `{service_principal}`\n"
            f"FOR TABLES\n"
            f"MATCH COLUMNS {', '.join(match_cols)}\n"
            f"USING COLUMNS ({', '.join(using_cols)});"
        )
    return stmts


def build_seed_principals_sql(catalog: str, schema: str = "onetrust_sim") -> list[str]:
    q = f"{catalog}.{schema}"
    stmts = [
        f"DELETE FROM {q}.ABAC_Assignment WHERE staticIdentifier = 'phase2-test-seed';",
        f"DELETE FROM {q}.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase2-test-seed';",
        f"DELETE FROM {q}.UserGroupMembers WHERE tenantHash = 'phase2-test-seed';",
    ]

    def assignment_insert(aid, object_type, is_active="true"):
        return (
            f"INSERT INTO {q}.ABAC_Assignment "
            "(id, guid, staticIdentifier, name, objectType, sourceType, isActive, createdBy, "
            "createDT, updatedBy, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)\n"
            f"SELECT {aid}, uuid(), 'phase2-test-seed', 'Owner', '{object_type}', 'SYSTEM', {is_active}, "
            "'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), "
            "current_timestamp(), 'phase2-test-seed', false;"
        )

    def esa_insert(aid, table, id_col, filter_clause, subject, subject_type, object_type_expr):
        return (
            f"INSERT INTO {q}.ABAC_EntitySubjectAssignment "
            "(assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, "
            "objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)\n"
            f"SELECT {aid}, NULL, entity_id, NULL, '{subject}', '{subject_type}', {object_type_expr}, "
            "current_timestamp(), current_timestamp(), current_timestamp(), 'phase2-test-seed', false\n"
            f"FROM (SELECT {id_col} AS entity_id FROM {q}.{table} {filter_clause} LIMIT 1) ent;"
        )

    def esa_insert_from_subquery(aid, entity_id_select_sql, subject, subject_type, object_type_expr):
        """
        Same shape as esa_insert, but for a subject that must be visible across MULTIPLE governed
        tables at once. abac_row_filter's EXISTS branch matches purely on (entityId, objectType)
        value equality -- it never looks at which table triggered the check -- so ONE ESA row
        whose entityId is verified (via a JOIN) to exist in every target table's id/assessmentID
        column grants that subject visibility into all of them for that one shared entity,
        without needing one ESA row per table.
        """
        return (
            f"INSERT INTO {q}.ABAC_EntitySubjectAssignment "
            "(assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, "
            "objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)\n"
            f"SELECT {aid}, NULL, entity_id, NULL, '{subject}', '{subject_type}', {object_type_expr}, "
            "current_timestamp(), current_timestamp(), current_timestamp(), 'phase2-test-seed', false\n"
            f"FROM ({entity_id_select_sql}) ent;"
        )

    # --- original 4, replayed verbatim (same subjects/tables as sql_onetrust/05, new catalog) ---
    stmts.append(assignment_insert(900001, "ASSESSMENT"))
    stmts.append(assignment_insert(900002, "ASSESSMENT", is_active="false"))
    stmts.append(assignment_insert(900003, "CONTROL"))
    stmts.append(esa_insert(900001, "cmb_assessment", "id", "ORDER BY id", "u.assessment.owner@example.com", "USER_ID", "'ASSESSMENT'"))
    stmts.append(esa_insert(900002, "cmb_assessment", "id", "ORDER BY id", "u.inactive.grant@example.com", "USER_ID", "'ASSESSMENT'"))
    stmts.append(esa_insert(900003, "cmb_controlimplementation", "id", "ORDER BY id", "test_group_1", "USER_GROUP", "'CONTROL'"))
    stmts.append(
        f"INSERT INTO {q}.UserGroupMembers (memberId, groupId, eventTime, recModifiedTime, isDeleted, tenantHash)\n"
        f"VALUES ('u.group.member@example.com', 'test_group_1', current_timestamp(), current_timestamp(), false, 'phase2-test-seed');"
    )
    stmts.append(assignment_insert(900004, "TEMPLATE"))
    stmts.append(esa_insert(900004, "cmb_template", "id", "ORDER BY id", "u.template.owner@example.com", "USER_ID", "'TEMPLATE'"))
    stmts.append(assignment_insert(900005, "ASSETS"))
    stmts.append(esa_insert(
        900005, "cmb_v_inventoryaggregatedrisksummary", "entityID",
        "WHERE upper(inventoryType) = 'ASSETS' ORDER BY entityID",
        "u.assets.owner@example.com", "USER_ID", "'ASSETS'",
    ))

    # --- 4 new governed tables (design doc section 5) ---
    stmts.append(assignment_insert(900006, "INVENTORY"))
    stmts.append(esa_insert(
        900006, "cmb_riskrelatedobjects", "riskId",
        "WHERE upper(entityType) = 'INVENTORY' ORDER BY riskId",
        "u.risk.owner@example.com", "USER_ID", "'INVENTORY'",
    ))

    stmts.append(assignment_insert(900007, "ASSETS"))
    stmts.append(esa_insert(
        900007, "cmb_inventory", "id",
        "WHERE upper(inventoryType) = 'ASSETS' ORDER BY id",
        "u.inventory.owner@example.com", "USER_ID", "'ASSETS'",
    ))

    stmts.append(assignment_insert(900008, "ASSESSMENT"))
    # cmb_v_assessment_v4's id is a fan-out column (design doc section 5): more than one physical
    # row can share the picked id, which is expected (mirrors TPC-DS A5), not a bug — the test
    # case built on this seed asserts nonzero, not exactly 1.
    stmts.append(esa_insert(
        900008, "cmb_v_assessment_v4", "id",
        "WHERE orgID = 'b99df4a4-2bf5-4c08-9483-bd636470bc11' ORDER BY id",
        "u.assessmentv4.owner@example.com", "USER_ID", "'ASSESSMENT'",
    ))

    stmts.append(assignment_insert(900009, "CONTROLTEMPLATE"))
    # entityid1typereference's real vocabulary has 5 values, but the 500-row sample only
    # observed 'ControlTemplate' (design doc section 5 caveat) — build_generic_table's
    # categorical synthesis is undersampled the same way, so this is the only value
    # guaranteed to actually appear in the generated data. entityid1 is also not unique
    # (fan-out, same as cmb_v_assessment_v4) — nonzero, not exactly-1, expected.
    stmts.append(esa_insert(
        900009, "entitylink_v3", "entityid1",
        "WHERE entityid1typereference = 'ControlTemplate' ORDER BY entityid1",
        "u.entitylink.owner@example.com", "USER_ID", "'CONTROLTEMPLATE'",
    ))

    # --- 2 new big governed tables (POC-scoped, see module docstring) ---
    stmts.append(assignment_insert(900010, "ASSESSMENT"))
    stmts.append(esa_insert(
        900010, "cmb_v_assessmentquestionresponse_v3", "assessmentID",
        "ORDER BY assessmentID",
        "u.assessmentresponse.owner@example.com", "USER_ID", "'ASSESSMENT'",
    ))

    stmts.append(assignment_insert(900011, "ASSESSMENT"))
    stmts.append(esa_insert(
        900011, "cmb_v_assessmentstagechangetracker_v4", "assessmentID",
        "ORDER BY assessmentID",
        "u.assessmentstage.owner@example.com", "USER_ID", "'ASSESSMENT'",
    ))

    # A subject visible across BOTH new tables at once, for complex-join queries that need a
    # non-vacuous narrow claim. cmb_v_assessmentquestionresponse_v3.assessmentID and
    # cmb_v_assessmentstagechangetracker_v4.assessmentID share the same ndv (2666) in the real
    # profile -- strong evidence they're the same underlying assessment id domain -- so this
    # entity_id is picked via a JOIN across both rather than assumed to overlap.
    stmts.append(assignment_insert(900012, "ASSESSMENT"))
    stmts.append(esa_insert_from_subquery(
        900012,
        f"""SELECT r.assessmentID AS entity_id
        FROM {q}.cmb_v_assessmentquestionresponse_v3 r
        JOIN {q}.cmb_v_assessmentstagechangetracker_v4 s ON s.assessmentID = r.assessmentID
        ORDER BY entity_id LIMIT 1""",
        "u.assessment.crossjoin.owner@example.com", "USER_ID", "'ASSESSMENT'",
    ))

    return stmts


def build_oauth_wiring_sql(catalog: str, schema: str = "onetrust_sim", service_principal: str = "<SERVICE_PRINCIPAL>") -> list[str]:
    """
    Parameterized re-emission of sql_onetrust/07_oauth_wiring.sql for a given catalog -- the
    scale-2 catalog's missing piece: without this, its 8 policies stay bound to
    abac_row_filter_wrapper (build_policies_sql's default), which reads get_test_user_context()'s
    hardcoded literal identity, so every injected OAuth claim is silently ignored (positive cases
    fail, negative cases vacuously pass) and the SP has no grants to even read the catalog.

    Emits, in order:
      - get_user_context()             : live identity, from_json(current_oauth_custom_identity_claim())
      - abac_row_filter_wrapper_oauth  : calls abac_row_filter with get_user_context() instead of
                                          get_test_user_context()
      - all 8 governed-table policies (build_policies_sql's full _POLICY_SPEC, extended from 07's
        original 4), re-pointed to abac_row_filter_wrapper_oauth
      - grants: USE CATALOG/USE SCHEMA, SELECT on each of the 8 governed tables, SELECT+MODIFY on
        OrgHierarchyBase (the self-seeding fixture target -- see Runner.setUpOnetrustFixture), and
        EXECUTE on the 4 UDFs the OAuth wrapper's call chain needs.

    get_test_user_context()/abac_row_filter_wrapper (built by build_udf_sql/build_policies_sql's
    default) are left untouched, same as 07's own doc comment explains for the original catalog.
    """
    q = f"{catalog}.{schema}"
    stmts = [
        f"""CREATE OR REPLACE FUNCTION {q}.get_user_context()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
COMMENT 'Live ABAC context from the OAuth custom_claim -- see docs/deployment/oauth-jdbc-flow.md'
RETURN from_json(
  current_oauth_custom_identity_claim(),
  'STRUCT<tenant:int, user:string, org:string, mode:string, root:string, permissions:array<string>>'
);""",
        f"""CREATE OR REPLACE FUNCTION {q}.abac_row_filter_wrapper_oauth(
  entity_id STRING, object_type STRING, org_id STRING
)
RETURNS BOOLEAN
RETURN {q}.abac_row_filter(
  entity_id, {q}.entity_type_to_object_type(object_type), org_id,
  {q}.get_user_context()
);""",
    ]

    stmts.extend(
        build_policies_sql(catalog, schema, service_principal, row_filter_fn="abac_row_filter_wrapper_oauth")
    )

    stmts.append(f"GRANT USE CATALOG ON CATALOG {catalog} TO `{service_principal}`;")
    stmts.append(f"GRANT USE SCHEMA ON SCHEMA {q} TO `{service_principal}`;")

    for table, _id_col, _type_col, _org_col in _TAG_SPEC:
        stmts.append(f"GRANT SELECT ON TABLE {q}.{table} TO `{service_principal}`;")

    # OrgHierarchyBase is not a policied table -- it's the self-seeding test fixture's target
    # (Runner.java's onetrustFixtureInserts()/onetrustFixtureDeletes()). The fixture INSERTs are
    # self-referential and the DELETE reads to scope its WHERE clause, so the SP needs BOTH
    # SELECT and MODIFY here, not just one -- same shape as sql_onetrust/07's grant.
    stmts.append(f"GRANT SELECT, MODIFY ON TABLE {q}.OrgHierarchyBase TO `{service_principal}`;")

    stmts.append(f"GRANT EXECUTE ON FUNCTION {q}.abac_row_filter_wrapper_oauth TO `{service_principal}`;")
    stmts.append(f"GRANT EXECUTE ON FUNCTION {q}.abac_row_filter TO `{service_principal}`;")
    stmts.append(f"GRANT EXECUTE ON FUNCTION {q}.get_user_context TO `{service_principal}`;")
    stmts.append(f"GRANT EXECUTE ON FUNCTION {q}.entity_type_to_object_type TO `{service_principal}`;")

    return stmts
