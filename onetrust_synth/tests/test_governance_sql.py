from onetrust_synth.governance_sql import build_udf_sql, build_tags_sql, build_policies_sql


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
