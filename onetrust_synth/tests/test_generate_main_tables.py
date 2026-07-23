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
    # cmb_inventory's own sample file is known-bad (see sample_csv.py); this locks
    # in that the generator overrides with the real, verified values instead of
    # whatever build_generic_table's generic placeholder fallback would produce.
    tables = build_all_main_tables(spark, scale_factor=1.0)
    seen = {r["inventoryType"] for r in tables["cmb_inventory"].select("inventoryType").collect() if r["inventoryType"] is not None}
    assert seen <= set(config.INVENTORY_TYPE_TO_OBJECT_TYPE.keys())
