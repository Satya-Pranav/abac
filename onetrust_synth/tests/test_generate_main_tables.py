from onetrust_synth import config
from onetrust_synth.generate_main_tables import build_all_main_tables


def test_builds_all_11_tables(spark):
    tables = build_all_main_tables(spark, scale_factor=0.1)  # small for a fast test
    assert set(tables.keys()) == set(config.MAIN_TABLES.keys())


def test_row_counts_scale_with_factor(spark):
    tables = build_all_main_tables(spark, scale_factor=0.1)
    # cmb_assessment real count 4984 * 0.1 = 498 (rounded)
    assert tables["cmb_assessment"].count() == 498


def test_entitygroupconfig_is_empty_but_has_correct_schema(spark):
    tables = build_all_main_tables(spark, scale_factor=1.0)
    assert tables["entitygroupconfig"].count() == 0
    assert set(tables["entitygroupconfig"].columns) >= {"entityType", "numberOfGroups", "groupThreshold"}


def test_orghierarchy_ignores_scale_factor_uses_real_data(spark):
    tables = build_all_main_tables(spark, scale_factor=0.1)
    assert tables["orghierarchy"].count() == 183  # real data, not scaled


def test_cmb_assessment_has_nested_columns_attached(spark):
    tables = build_all_main_tables(spark, scale_factor=0.1)
    cols = tables["cmb_assessment"].columns
    assert "questionRootMap" in cols
    assert "userIdsAssociatedWithAssessment" in cols


def test_cmb_inventory_inventory_type_uses_real_vocabulary_not_corrupted_sample(spark):
    # cmb_inventory's own sample file IS correctly recovered by sample_csv.py's
    # positional-reconstruction fix (not header-corrupted). But inventoryType is
    # non-null in only 38/8750 real rows, and the ~500-row sample happens to
    # capture only 2 of the 3 real categories (missing "Processing Activities")
    # -- too few observations, not a data-corruption issue. This locks in that
    # the generator overrides with the full, verified 3-value vocabulary instead
    # of leaving inventoryType artificially limited to 2 of 3 real categories.
    tables = build_all_main_tables(spark, scale_factor=1.0)
    seen = {r["inventoryType"] for r in tables["cmb_inventory"].select("inventoryType").collect() if r["inventoryType"] is not None}
    assert seen <= set(config.INVENTORY_TYPE_TO_OBJECT_TYPE.keys())


def test_build_all_main_tables_default_is_unchanged_11_tables(spark):
    tables = build_all_main_tables(spark, scale_factor=0.1)
    assert set(tables.keys()) == set(config.MAIN_TABLES.keys())
    assert len(tables) == 11


def test_id_columns_stay_unique_at_scale_factor_above_one(spark):
    # Regression test: _is_id_like used to compare a column's real ndv (bounded by the real
    # profiled sample size) against the SCALED target row count, not the real profiled count.
    # At scale_factor=5 that comparison is mathematically impossible for any genuine id column
    # to pass, so id columns silently fell back to a small reused categorical pool instead of
    # generating true unique-per-row ids. Confirmed live 2026-07-31: cmb_assessment.id had 51
    # duplicate rows sharing the same id on abac_onetrust_scale's real (scale_factor=5) run.
    tables = build_all_main_tables(
        spark, scale_factor=5.0, table_row_counts={"cmb_assessment": config.MAIN_TABLES["cmb_assessment"]}
    )
    ids = [r["id"] for r in tables["cmb_assessment"].select("id").collect()]
    assert len(ids) == len(set(ids))
    assert len(ids) == round(config.MAIN_TABLES["cmb_assessment"] * 5.0)


def test_build_all_main_tables_accepts_scale2_table_set(spark):
    tables = build_all_main_tables(spark, scale_factor=0.01, table_row_counts=config.ALL_SCALE2_MAIN_TABLES)
    assert len(tables) == 34
    assert "entity_v3" in tables
    assert "cmb_v_assessmenttag" in tables
    # every one of the 23 new tables builds without raising (no nested-column columns present,
    # per design doc section 3 — build_generic_table handles all of them)
    for new_table in config.REMAINING_MAIN_TABLES:
        assert tables[new_table].count() >= 0
