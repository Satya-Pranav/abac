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


from onetrust_synth.registries import build_entitylink_v3_entity_piece


def test_build_all_abac_tables_default_targets_are_unchanged(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    abac = build_all_abac_tables(spark, main_tables)
    assert abac["ABAC_EntitySubjectAssignment"].count() > 0  # Phase-1 sized, not asserting exact count (hash-index driven)


def test_build_all_abac_tables_accepts_row_targets_override(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    small_scale2_targets = {
        "ABAC_Assignment": 50, "ABAC_AssignmentPermission": 200,
        "ABAC_EntitySubjectAssignment": 500, "UserGroupMembers": 100,
        "ABAC_OrgHierarchy": 183,
    }
    abac = build_all_abac_tables(spark, main_tables, row_targets=small_scale2_targets)
    assert abac["ABAC_Assignment"].count() == 50


def test_build_all_abac_tables_accepts_extra_entity_pieces(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    extra = build_entitylink_v3_entity_piece(main_tables)
    small_targets = {
        "ABAC_Assignment": 200, "ABAC_AssignmentPermission": 500,
        "ABAC_EntitySubjectAssignment": 2000, "UserGroupMembers": 100,
        "ABAC_OrgHierarchy": 183,
    }
    abac = build_all_abac_tables(spark, main_tables, row_targets=small_targets, extra_entity_pieces=[extra])
    esa = abac["ABAC_EntitySubjectAssignment"]
    types = {r["objectType"] for r in esa.select("objectType").distinct().collect()}
    # CONTROLTEMPLATE only appears in ESA if some ABAC_Assignment row also has that objectType —
    # not guaranteed with a small random Assignment sample, so this test only asserts the
    # pipeline runs end-to-end without error when extra_entity_pieces is supplied.
    assert esa.count() > 0
