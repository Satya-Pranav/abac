from onetrust_synth.verbatim_tables import build_orghierarchy_df, build_cmb_v_inventoryaggregatedrisksummary_df


def test_orghierarchy_has_183_real_rows(spark):
    df = build_orghierarchy_df(spark)
    assert df.count() == 183
    assert df.select("orgId").distinct().count() == 68


def test_orghierarchy_preserves_ancestor_closure_shape(spark):
    df = build_orghierarchy_df(spark)
    # an org with multiple parent rows (ancestor closure), not single-level adjacency
    counts = df.groupBy("orgId").count().collect()
    assert any(r["count"] > 1 for r in counts)


def test_inventory_risk_summary_has_14_real_rows(spark):
    df = build_cmb_v_inventoryaggregatedrisksummary_df(spark)
    assert df.count() == 14
    assert "inventoryType" in df.columns
    types = {r["inventoryType"] for r in df.select("inventoryType").collect()}
    # verified against the real sample file: {'Assets', 'Processing Activities', 'Vendors'}
    assert types <= {"Assets", "Vendors", "Processing Activities"}


def test_inventory_risk_summary_numeric_columns_are_real_typed_not_string(spark):
    # A prior task review caught that every column coming straight out of a CSV
    # is string-typed by default, which silently breaks ORDER BY on numeric
    # columns (lexicographic '10' < '2' instead of numeric 2 < 10). This table
    # is the single most-queried one in the Phase-1 compatible-query set (39 of
    # 50 — design doc section 3), so its numeric/temporal columns must be cast
    # to their real profiled types.
    df = build_cmb_v_inventoryaggregatedrisksummary_df(spark)
    schema = {f.name: f.dataType.typeName() for f in df.schema.fields}
    assert schema["inherentRiskScore"] == "double"
    assert schema["residualRiskScore"] == "double"
    assert schema["targetRiskScore"] == "double"
    assert schema["inventoryTypeID"] == "integer"
    assert schema["inventoryNumber"] == "long"
    ordered = [r["inventoryNumber"] for r in df.orderBy("inventoryNumber").select("inventoryNumber").collect()]
    assert ordered == sorted(ordered)  # numeric order, not lexicographic


def test_orghierarchy_temporal_and_boolean_columns_are_real_typed(spark):
    df = build_orghierarchy_df(spark)
    schema = {f.name: f.dataType.typeName() for f in df.schema.fields}
    assert schema["eventTime"] == "timestamp"
    assert schema["recModifiedTime"] == "timestamp"
    assert schema["isDeleted"] == "boolean"


def test_cast_tolerates_empty_string_values(spark):
    # The real sample data has empty-string values in otherwise-numeric columns
    # (e.g. inherentRiskScore, residualRiskScore, targetRiskScore on row 0). Under
    # ANSI mode (Databricks Runtime's default, and this suite's since conftest.py
    # enables it), a plain cast() on '' raises CAST_INVALID_INPUT instead of
    # returning NULL -- this failed on a real Databricks run before the fix.
    df = build_cmb_v_inventoryaggregatedrisksummary_df(spark)
    rows = df.select("inherentRiskScore", "residualRiskScore", "targetRiskScore").collect()
    assert any(r["inherentRiskScore"] is None for r in rows)
    assert any(r["residualRiskScore"] is None for r in rows)
    assert any(r["targetRiskScore"] is None for r in rows)
