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
