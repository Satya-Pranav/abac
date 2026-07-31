from onetrust_synth.query_rewrite import (
    load_all_annotated_queries, tables_referenced, is_now_eligible, catalog_qualify, build_modified_query,
    _extract_tables_from_reason, _extract_tables_from_query_schema_refs,
)
from onetrust_synth import config


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
    # a "different tenant schema" row with no query text to extract tables from has
    # nothing to confirm coverage against, so it stays ineligible
    row = {"tables_used": "cmb_assessment", "reason": "different tenant schema", "query": ""}
    assert is_now_eligible(row, available) is False
    row2 = {"tables_used": "", "reason": "no real table reference (BI/AAS plumbing query)"}
    assert is_now_eligible(row2, available) is False


def test_extract_tables_from_query_schema_refs_finds_tenant_hash_and_monitoring_refs():
    query = (
        "SELECT a.id FROM auto_qa_jreh7hspwyy0tukt4lt3zn3ea.cmb_assessment a "
        "JOIN monitoring.EntityGroupConfig e ON a.id = e.entityId"
    )
    result = _extract_tables_from_query_schema_refs(query)
    assert result == ["cmb_assessment", "EntityGroupConfig"]


def test_extract_tables_from_query_schema_refs_dedupes_case_insensitively():
    query = "SELECT * FROM auto_qa_abc123.cmb_template t1 JOIN auto_qa_xyz789.CMB_Template t2 ON t1.id = t2.id"
    result = _extract_tables_from_query_schema_refs(query)
    assert len(result) == 1


def test_is_now_eligible_true_for_different_tenant_schema_when_all_referenced_tables_present():
    available = {"cmb_assessment", "cmb_template"}
    row = {
        "reason": "different tenant schema",
        "query": "SELECT * FROM auto_qa_someotherhash123.cmb_assessment a JOIN auto_qa_someotherhash123.cmb_template t ON a.id = t.id",
    }
    assert is_now_eligible(row, available) is True


def test_is_now_eligible_false_for_different_tenant_schema_when_a_table_is_missing():
    available = {"cmb_assessment"}
    row = {
        "reason": "different tenant schema",
        "query": "SELECT * FROM auto_qa_someotherhash123.cmb_assessment a JOIN auto_qa_someotherhash123.cmb_riskattributevalue r ON a.id = r.id",
    }
    assert is_now_eligible(row, available) is False


def test_catalog_qualify_rewrites_any_tenant_hash_to_our_onetrust_sim_schema():
    sql = "SELECT * FROM auto_qa_jreh7hspwyy0tukt4lt3zn3ea.cmb_assessment"
    result = catalog_qualify(sql, "abac_onetrust_scale")
    assert "abac_onetrust_scale.onetrust_sim.cmb_assessment" in result
    assert "auto_qa_" not in result


def test_catalog_qualify_rewrites_our_own_tenant_hash_too():
    sql = "SELECT * FROM auto_qa_e40yx52dkbjpcqazimno9yvh4k.cmb_template"
    result = catalog_qualify(sql, "abac_onetrust_scale")
    assert "abac_onetrust_scale.onetrust_sim.cmb_template" in result


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


def test_extract_tables_from_reason_parses_multiple_bare_table_names():
    reason = "references table(s) outside our 11: CMB_InventoryRelatedAttributeMap, CMB_InventoryPersonalDataAssociation"
    result = _extract_tables_from_reason(reason)
    assert len(result) == 2
    assert "CMB_InventoryRelatedAttributeMap" in result
    assert "CMB_InventoryPersonalDataAssociation" in result


def test_extract_tables_from_reason_parses_schema_qualified_table_name():
    reason = "references table(s) outside our 11, in another schema: monitoring.dbxtenantschemaversion"
    result = _extract_tables_from_reason(reason)
    assert len(result) == 1
    assert result[0] == "dbxtenantschemaversion"  # Should extract just the table name, not the schema


def test_extract_tables_from_reason_returns_empty_when_no_colon():
    reason = "some reason without a colon marker"
    result = _extract_tables_from_reason(reason)
    assert result == []


def test_is_now_eligible_uses_reason_when_tables_used_empty():
    # When tables_used is empty, should extract from reason string
    available = {"dbxtenantschemaversion"}
    row = {
        "tables_used": "",
        "reason": "references table(s) outside our 11, in another schema: monitoring.dbxtenantschemaversion"
    }
    assert is_now_eligible(row, available) is True


def test_is_now_eligible_rejects_missing_tables_from_reason():
    # Tables extracted from reason that don't exist should make row ineligible
    available = {"some_other_table"}
    row = {
        "tables_used": "",
        "reason": "references table(s) outside our 11: CMB_InventoryRelatedAttributeMap, CMB_InventoryPersonalDataAssociation"
    }
    assert is_now_eligible(row, available) is False


def test_real_data_different_tenant_schema_eligibility_count():
    """Verify against the actual CSV: of the 281 rows excluded for referencing a different
    tenant's schema hash, exactly 64 reference only tables that exist in our 34-table set
    (and are therefore now eligible), once BOTH tenant-hash-qualified and shared
    monitoring-schema references are extracted from the raw query text."""
    rows = load_all_annotated_queries()
    diff_tenant_rows = [
        r for r in rows
        if r.get("in_scope") == "no" and (r.get("reason") or "").strip().lower() == "different tenant schema"
    ]
    assert len(diff_tenant_rows) == 281, f"Expected 281 different-tenant-schema rows, found {len(diff_tenant_rows)}"

    available_tables = set(config.ALL_SCALE2_MAIN_TABLES.keys())
    newly_eligible = [r for r in diff_tenant_rows if is_now_eligible(r, available_tables)]

    assert len(newly_eligible) == 64, (
        f"Expected exactly 64 newly-eligible different-tenant-schema rows, found {len(newly_eligible)}"
    )


def test_real_data_total_shortlist_count():
    """End-to-end verified count: 50 originally in-scope + 1 newly-eligible missing-table
    row + 64 newly-eligible different-tenant-schema rows = 115 total real queries runnable
    against the new abac_onetrust_scale catalog."""
    from onetrust_synth.query_shortlist import build_shortlist_rows

    rows = build_shortlist_rows("abac_onetrust_scale")
    assert len(rows) == 115


def test_real_data_exactly_one_newly_eligible_row():
    """Verify against the actual CSV: exactly 1 of 4 missing-table excluded rows becomes eligible."""
    rows = load_all_annotated_queries()
    excluded_rows = [r for r in rows if r.get("in_scope") == "no"]

    # Filter to rows with missing-table reasons
    missing_table_rows = [
        r for r in excluded_rows
        if "references table(s) outside our" in (r.get("reason") or "").lower()
    ]

    assert len(missing_table_rows) == 4, f"Expected 4 missing-table rows, found {len(missing_table_rows)}"

    # Check eligibility against the real 34-table catalog
    available_tables = set(config.ALL_SCALE2_MAIN_TABLES.keys())
    newly_eligible = [r for r in missing_table_rows if is_now_eligible(r, available_tables)]

    assert len(newly_eligible) == 1, (
        f"Expected exactly 1 newly-eligible row, found {len(newly_eligible)}. "
        f"Reasons: {[r.get('reason') for r in newly_eligible]}"
    )

    # Verify it's the dbxtenantschemaversion one
    eligible_reason = newly_eligible[0].get("reason", "")
    assert "dbxtenantschemaversion" in eligible_reason.lower()
