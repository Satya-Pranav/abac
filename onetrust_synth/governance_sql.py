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


def build_policies_sql(catalog: str, schema: str = "onetrust_sim", service_principal: str = "<SERVICE_PRINCIPAL>") -> list[str]:
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
            f"ROW FILTER {q}.abac_row_filter_wrapper\n"
            f"TO `{service_principal}`\n"
            f"FOR TABLES\n"
            f"MATCH COLUMNS {', '.join(match_cols)}\n"
            f"USING COLUMNS ({', '.join(using_cols)});"
        )
    return stmts
