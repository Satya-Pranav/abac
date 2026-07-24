from onetrust_synth import config
from onetrust_synth.generate_main_tables import build_all_main_tables
from onetrust_synth.generate_abac_tables import build_all_abac_tables


def test_builds_all_5_abac_tables(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    abac_tables = build_all_abac_tables(spark, main_tables)
    assert set(abac_tables.keys()) == set(config.ABAC_TABLE_ROW_TARGETS.keys())


def test_abac_table_row_counts_match_phase1_targets(spark):
    from onetrust_synth.validate import validate_row_counts

    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    abac_tables = build_all_abac_tables(spark, main_tables)
    built = {table: df.count() for table, df in abac_tables.items()}
    # UserGroupMembers dedupes (memberId, groupId) pairs, so it can land slightly
    # under target — validate_row_counts' default 5% tolerance covers that; every
    # other table hits its target exactly.
    failures = validate_row_counts(built, config.ABAC_TABLE_ROW_TARGETS, tolerance=0.05)
    assert failures == []
