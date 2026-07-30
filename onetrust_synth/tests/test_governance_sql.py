from onetrust_synth.governance_sql import build_udf_sql, build_tags_sql, build_policies_sql, build_seed_principals_sql


def test_build_udf_sql_is_catalog_qualified():
    stmts = build_udf_sql("abac_onetrust_scale")
    assert len(stmts) == 4  # get_test_user_context, entity_type_to_object_type, abac_row_filter, abac_row_filter_wrapper
    joined = "\n".join(stmts)
    assert "abac_onetrust_scale.onetrust_sim.abac_row_filter" in joined
    assert "abac_onetrust.onetrust_sim" not in joined  # never leaks the original catalog name


def test_build_tags_sql_covers_all_8_tables():
    stmts = build_tags_sql("abac_onetrust_scale")
    joined = "\n".join(stmts)
    for table in [
        "cmb_assessment", "cmb_controlimplementation", "cmb_template",
        "cmb_v_inventoryaggregatedrisksummary", "cmb_riskrelatedobjects",
        "cmb_inventory", "cmb_v_assessment_v4", "entitylink_v3",
    ]:
        assert f"abac_onetrust_scale.onetrust_sim.{table}" in joined
    # spot-check the 3 new tag columns from design doc section 5
    assert "ALTER COLUMN riskId SET TAGS ('abac_column_id' = 'true')" in joined
    assert "ALTER COLUMN entityType SET TAGS ('abac_column_type' = 'true')" in joined
    assert "ALTER COLUMN organizationID SET TAGS ('abac_column_org' = 'true')" in joined
    assert "ALTER COLUMN entityid1 SET TAGS ('abac_column_id' = 'true')" in joined
    assert "ALTER COLUMN entityid1typereference SET TAGS ('abac_column_type' = 'true')" in joined


def test_build_policies_sql_covers_all_8_tables_with_correct_shapes():
    stmts = build_policies_sql("abac_onetrust_scale", service_principal="sp-app-id")
    joined = "\n".join(stmts)
    assert len(stmts) == 8
    assert "TO `sp-app-id`" in joined
    # id-only shape (literal type + literal org)
    assert "USING COLUMNS (id, 'ASSESSMENT', '100')" in joined  # cmb_assessment, unchanged
    # full 3-tag shape (new): cmb_riskrelatedobjects
    assert "MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_type') as type, has_tag('abac_column_org') as org" in joined
    # cmb_v_assessment_v4: id + literal type + org column
    assert "USING COLUMNS (id, 'ASSESSMENT', org)" in joined
    # cmb_inventory and entitylink_v3: id + type from real tagged columns, org literal '100'
    assert "MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_type') as type\nUSING COLUMNS (id, type, '100')" in joined


def test_build_seed_principals_sql_covers_all_8_tables():
    stmts = build_seed_principals_sql("abac_onetrust_scale")
    joined = "\n".join(stmts)
    # 3 DELETEs (idempotent re-run) + inserts for 9 assignment ids (900001-900009: 5 for the
    # original 4 tables + 1 each for the 4 new tables)
    assert joined.count("DELETE FROM") == 3
    for assignment_id in range(900001, 900010):
        assert str(assignment_id) in joined
    assert "u.assessment.owner@example.com" in joined  # original 4, replayed
    assert "u.risk.owner@example.com" in joined  # new
    assert "u.inventory.owner@example.com" in joined  # new
    assert "u.assessmentv4.owner@example.com" in joined  # new
    assert "u.entitylink.owner@example.com" in joined  # new
    assert "abac_onetrust_scale.onetrust_sim.cmb_riskrelatedobjects" in joined
    assert "WHERE upper(entityType) = 'INVENTORY'" in joined
    assert "WHERE upper(inventoryType) = 'ASSETS'" in joined
    assert "b99df4a4-2bf5-4c08-9483-bd636470bc11" in joined
    assert "WHERE entityid1typereference = 'ControlTemplate'" in joined
