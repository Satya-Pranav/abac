from onetrust_synth.governance_sql import (
    build_udf_sql, build_tags_sql, build_policies_sql, build_seed_principals_sql, build_oauth_wiring_sql,
)


def test_build_udf_sql_is_catalog_qualified():
    stmts = build_udf_sql("abac_onetrust_scale")
    assert len(stmts) == 4  # get_test_user_context, entity_type_to_object_type, abac_row_filter, abac_row_filter_wrapper
    joined = "\n".join(stmts)
    assert "abac_onetrust_scale.onetrust_sim.abac_row_filter" in joined
    assert "abac_onetrust.onetrust_sim" not in joined  # never leaks the original catalog name


def test_build_tags_sql_covers_all_10_tables():
    stmts = build_tags_sql("abac_onetrust_scale")
    joined = "\n".join(stmts)
    for table in [
        "cmb_assessment", "cmb_controlimplementation", "cmb_template",
        "cmb_v_inventoryaggregatedrisksummary", "cmb_riskrelatedobjects",
        "cmb_inventory", "cmb_v_assessment_v4", "entitylink_v3",
        "cmb_v_assessmentquestionresponse_v3", "cmb_v_assessmentstagechangetracker_v4",
    ]:
        assert f"abac_onetrust_scale.onetrust_sim.{table}" in joined
    # spot-check the 3 new tag columns from design doc section 5
    assert "ALTER COLUMN riskId SET TAGS ('abac_column_id' = 'true')" in joined
    assert "ALTER COLUMN entityType SET TAGS ('abac_column_type' = 'true')" in joined
    assert "ALTER COLUMN organizationID SET TAGS ('abac_column_org' = 'true')" in joined
    assert "ALTER COLUMN entityid1 SET TAGS ('abac_column_id' = 'true')" in joined
    assert "ALTER COLUMN entityid1typereference SET TAGS ('abac_column_type' = 'true')" in joined
    # spot-check the 2 POC-scoped big-table additions (both use assessmentID + real orgID)
    assert joined.count("ALTER COLUMN assessmentID SET TAGS ('abac_column_id' = 'true')") == 2
    # orgID is also cmb_v_assessment_v4's and cmb_v_inventoryaggregatedrisksummary's real org
    # column, so 4 total = those 2 existing + the 2 new tables
    assert joined.count("ALTER COLUMN orgID SET TAGS ('abac_column_org' = 'true')") == 4


def test_build_policies_sql_covers_all_10_tables_with_correct_shapes():
    stmts = build_policies_sql("abac_onetrust_scale", service_principal="sp-app-id")
    joined = "\n".join(stmts)
    assert len(stmts) == 10
    assert "TO `sp-app-id`" in joined
    # id-only shape (literal type + literal org)
    assert "USING COLUMNS (id, 'ASSESSMENT', '100')" in joined  # cmb_assessment, unchanged
    # full 3-tag shape (new): cmb_riskrelatedobjects
    assert "MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_type') as type, has_tag('abac_column_org') as org" in joined
    # cmb_v_assessment_v4: id + literal type + org column
    assert "USING COLUMNS (id, 'ASSESSMENT', org)" in joined
    # cmb_inventory and entitylink_v3: id + type from real tagged columns, org literal '100'
    assert "MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_type') as type\nUSING COLUMNS (id, type, '100')" in joined
    # cmb_v_assessmentquestionresponse_v3 and cmb_v_assessmentstagechangetracker_v4 (POC-scoped,
    # new): same id+literal-type+org-column shape as cmb_v_assessment_v4
    assert "onetrust_sim_cmb_v_assessmentquestionresponse_v3_abac_policy" in joined
    assert "onetrust_sim_cmb_v_assessmentstagechangetracker_v4_abac_policy" in joined
    assert joined.count("USING COLUMNS (id, 'ASSESSMENT', org)") == 3  # cmb_v_assessment_v4 + the 2 new tables


def test_build_seed_principals_sql_covers_all_10_tables():
    stmts = build_seed_principals_sql("abac_onetrust_scale")
    joined = "\n".join(stmts)
    # 3 DELETEs (idempotent re-run) + inserts for 12 assignment ids (900001-900012: 5 for the
    # original 4 tables + 1 each for the 4 design-doc tables + 3 for the 2 POC big tables --
    # 900010, 900011 dedicated seeds, plus 900012 the cross-table seed)
    assert joined.count("DELETE FROM") == 3
    for assignment_id in range(900001, 900013):
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
    # POC-scoped big-table seeds (new)
    assert "u.assessmentresponse.owner@example.com" in joined
    assert "u.assessmentstage.owner@example.com" in joined
    assert "u.assessment.crossjoin.owner@example.com" in joined
    assert "JOIN abac_onetrust_scale.onetrust_sim.cmb_v_assessmentstagechangetracker_v4 s ON s.assessmentID = r.assessmentID" in joined


def test_build_policies_sql_default_row_filter_fn_is_unchanged():
    # Guards the refactor: no row_filter_fn arg must still bind the deterministic test-claim
    # wrapper, exactly like before build_oauth_wiring_sql existed.
    stmts = build_policies_sql("abac_onetrust_scale")
    joined = "\n".join(stmts)
    assert "ROW FILTER abac_onetrust_scale.onetrust_sim.abac_row_filter_wrapper\n" in joined
    assert "abac_row_filter_wrapper_oauth" not in joined


def test_build_policies_sql_accepts_row_filter_fn_override():
    stmts = build_policies_sql("abac_onetrust_scale", row_filter_fn="abac_row_filter_wrapper_oauth")
    joined = "\n".join(stmts)
    assert len(stmts) == 10
    assert "ROW FILTER abac_onetrust_scale.onetrust_sim.abac_row_filter_wrapper_oauth\n" in joined


def test_build_oauth_wiring_sql_is_catalog_qualified():
    stmts = build_oauth_wiring_sql("abac_onetrust_scale", service_principal="sp-app-id")
    joined = "\n".join(stmts)
    assert "abac_onetrust.onetrust_sim" not in joined  # never leaks the original catalog name
    assert "abac_onetrust_scale.onetrust_sim.get_user_context" in joined
    assert "current_oauth_custom_identity_claim()" in joined
    assert "abac_onetrust_scale.onetrust_sim.abac_row_filter_wrapper_oauth" in joined


def test_build_oauth_wiring_sql_repoints_all_10_policies_to_the_oauth_wrapper():
    stmts = build_oauth_wiring_sql("abac_onetrust_scale", service_principal="sp-app-id")
    policy_stmts = [s for s in stmts if s.startswith("CREATE OR REPLACE POLICY")]
    assert len(policy_stmts) == 10
    for stmt in policy_stmts:
        assert "ROW FILTER abac_onetrust_scale.onetrust_sim.abac_row_filter_wrapper_oauth\n" in stmt
        assert "TO `sp-app-id`" in stmt


def test_build_oauth_wiring_sql_grants_cover_all_10_tables_plus_orghierarchybase():
    stmts = build_oauth_wiring_sql("abac_onetrust_scale", service_principal="sp-app-id")
    joined = "\n".join(stmts)
    assert "GRANT USE CATALOG ON CATALOG abac_onetrust_scale TO `sp-app-id`;" in joined
    assert "GRANT USE SCHEMA ON SCHEMA abac_onetrust_scale.onetrust_sim TO `sp-app-id`;" in joined
    for table in [
        "cmb_assessment", "cmb_controlimplementation", "cmb_template",
        "cmb_v_inventoryaggregatedrisksummary", "cmb_riskrelatedobjects",
        "cmb_inventory", "cmb_v_assessment_v4", "entitylink_v3",
        "cmb_v_assessmentquestionresponse_v3", "cmb_v_assessmentstagechangetracker_v4",
    ]:
        assert f"GRANT SELECT ON TABLE abac_onetrust_scale.onetrust_sim.{table} TO `sp-app-id`;" in joined
    assert "GRANT SELECT, MODIFY ON TABLE abac_onetrust_scale.onetrust_sim.OrgHierarchyBase TO `sp-app-id`;" in joined
    assert "GRANT EXECUTE ON FUNCTION abac_onetrust_scale.onetrust_sim.abac_row_filter_wrapper_oauth TO `sp-app-id`;" in joined
    assert "GRANT EXECUTE ON FUNCTION abac_onetrust_scale.onetrust_sim.abac_row_filter TO `sp-app-id`;" in joined
    assert "GRANT EXECUTE ON FUNCTION abac_onetrust_scale.onetrust_sim.get_user_context TO `sp-app-id`;" in joined
    assert "GRANT EXECUTE ON FUNCTION abac_onetrust_scale.onetrust_sim.entity_type_to_object_type TO `sp-app-id`;" in joined


def test_build_oauth_wiring_sql_default_service_principal_placeholder():
    stmts = build_oauth_wiring_sql("abac_onetrust_scale")
    joined = "\n".join(stmts)
    assert "TO `<SERVICE_PRINCIPAL>`" in joined
