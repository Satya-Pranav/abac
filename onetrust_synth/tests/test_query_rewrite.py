from onetrust_synth.query_rewrite import (
    load_all_annotated_queries, tables_referenced, is_now_eligible, catalog_qualify, build_modified_query,
)


def test_load_all_annotated_queries_returns_357_rows():
    rows = load_all_annotated_queries()
    assert len(rows) == 357
    assert sum(1 for r in rows if r["in_scope"] == "yes") == 50
    assert sum(1 for r in rows if r["in_scope"] == "no") == 307


def test_tables_referenced_parses_comma_separated_list():
    assert tables_referenced("cmb_assessment, cmb_template") == ["cmb_assessment", "cmb_template"]
    assert tables_referenced("EntityGroupConfig") == ["EntityGroupConfig"]


def test_is_now_eligible_true_when_all_tables_now_present():
    available = {"cmb_assessment", "entity_v3"}
    row = {"tables_used": "cmb_assessment, entity_v3", "reason": "references table(s) outside our 11: entity_v3"}
    assert is_now_eligible(row, available) is True


def test_is_now_eligible_false_when_a_table_is_still_missing():
    available = {"cmb_assessment"}
    row = {"tables_used": "cmb_assessment, entityattributevalue_v3", "reason": "references table(s) outside our 11"}
    assert is_now_eligible(row, available) is False


def test_is_now_eligible_false_for_non_table_exclusion_reasons():
    available = {"cmb_assessment"}
    row = {"tables_used": "cmb_assessment", "reason": "different tenant schema"}
    assert is_now_eligible(row, available) is False
    row2 = {"tables_used": "", "reason": "no real table reference (BI/AAS plumbing query)"}
    assert is_now_eligible(row2, available) is False


def test_catalog_qualify_adds_catalog_prefix_to_bare_schema_table():
    sql = "SELECT egc1_0.entityType FROM monitoring.EntityGroupConfig egc1_0"
    result = catalog_qualify(sql, "abac_onetrust_scale")
    assert "abac_onetrust_scale.monitoring.EntityGroupConfig" in result
    assert "FROM monitoring.EntityGroupConfig" not in result


def test_catalog_qualify_is_idempotent_on_already_qualified_sql():
    sql = "SELECT * FROM abac_onetrust_scale.onetrust_sim.cmb_assessment"
    result = catalog_qualify(sql, "abac_onetrust_scale")
    assert result.count("abac_onetrust_scale.abac_onetrust_scale") == 0


def test_build_modified_query_reuses_existing_modified_query_when_present():
    row = {"query": "select 1", "modified_query": "SELECT 1  -- already rewritten", "tables_used": ""}
    assert build_modified_query(row, "abac_onetrust_scale") == "SELECT 1  -- already rewritten"


def test_build_modified_query_rewrites_when_modified_query_is_empty():
    row = {"query": "select x from monitoring.EntityGroupConfig", "modified_query": "", "tables_used": "EntityGroupConfig"}
    result = build_modified_query(row, "abac_onetrust_scale")
    assert "abac_onetrust_scale.monitoring.EntityGroupConfig" in result
