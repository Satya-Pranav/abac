from onetrust_synth import config
from onetrust_synth.registries import build_org_registry, build_subject_registry


def test_org_registry_reuses_real_orghierarchy(spark):
    reg = build_org_registry(spark)
    assert reg.count() == 183
    assert set(reg.columns) >= {"orgId", "parentOrgId"}


def test_subject_registry_has_users_and_groups(spark):
    reg = build_subject_registry(spark)
    total = config.SUBJECT_REGISTRY_USER_COUNT + config.SUBJECT_REGISTRY_GROUP_COUNT
    assert reg.count() == total
    types = {r["subjectType"] for r in reg.select("subjectType").distinct().collect()}
    assert types == {"USER_ID", "USER_GROUP"}
    user_count = reg.filter(reg.subjectType == "USER_ID").count()
    assert user_count == config.SUBJECT_REGISTRY_USER_COUNT


def test_subject_registry_ids_are_unique(spark):
    reg = build_subject_registry(spark)
    ids = [r["subjectId"] for r in reg.select("subjectId").collect()]
    assert len(ids) == len(set(ids))


from onetrust_synth.registries import build_entity_registry
from onetrust_synth.generate_main_tables import build_all_main_tables


def test_entity_registry_covers_all_source_tables(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    reg = build_entity_registry(spark, main_tables)
    types = {r["objectType"] for r in reg.select("objectType").distinct().collect()}
    assert "ASSESSMENT" in types
    assert "CONTROL" in types
    assert "RISK" in types
    assert "TEMPLATE" in types


def test_entity_registry_inventory_type_comes_from_row_data(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    reg = build_entity_registry(spark, main_tables)
    inv_types = {
        r["objectType"] for r in reg.select("objectType").distinct().collect()
        if r["objectType"] in set(config.INVENTORY_TYPE_TO_OBJECT_TYPE.values())
    }
    assert len(inv_types) > 0


def test_entity_registry_inventory_type_mapping_hyphenates_correctly(spark):
    # regression guard for the F.upper()-is-wrong bug: "Processing Activities" must
    # map to "PROCESSING-ACTIVITIES" (hyphenated), not "PROCESSING ACTIVITIES"
    main_tables = build_all_main_tables(spark, scale_factor=1.0)
    reg = build_entity_registry(spark, main_tables)
    types = {r["objectType"] for r in reg.select("objectType").distinct().collect()}
    assert "PROCESSING-ACTIVITIES" in types
    assert "PROCESSING ACTIVITIES" not in types


def test_entity_registry_includes_standalone_entities_for_uncovered_types(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    reg = build_entity_registry(spark, main_tables)
    # WORKPAPER has no source table among our 11 — must still appear via standalone entities
    types = {r["objectType"] for r in reg.select("objectType").distinct().collect()}
    assert "WORKPAPER" in types
    count = reg.filter(reg.objectType == "WORKPAPER").count()
    assert count == config.STANDALONE_ENTITIES_PER_TYPE


def test_entity_registry_accepts_standalone_per_type_override(spark):
    # Regression guard for config.SCALE2_STANDALONE_ENTITIES_PER_TYPE being dead code: an override
    # must actually change the BUILT ROW COUNT for uncovered entity types, not just be accepted
    # and ignored.
    main_tables = build_all_main_tables(spark, scale_factor=0.1)

    default_reg = build_entity_registry(spark, main_tables)
    default_count = default_reg.filter(default_reg.objectType == "WORKPAPER").count()
    assert default_count == config.STANDALONE_ENTITIES_PER_TYPE

    overridden_reg = build_entity_registry(spark, main_tables, standalone_per_type=5)
    overridden_count = overridden_reg.filter(overridden_reg.objectType == "WORKPAPER").count()
    assert overridden_count == 5
    assert overridden_count != default_count


def test_entity_registry_entity_ids_are_unique_within_type(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    reg = build_entity_registry(spark, main_tables)
    dup_check = reg.groupBy("entityId", "objectType").count().filter("count > 1").count()
    assert dup_check == 0


from onetrust_synth.registries import build_entitylink_v3_entity_piece


def test_entitylink_v3_entity_piece_shape(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    piece = build_entitylink_v3_entity_piece(main_tables)
    assert set(piece.columns) == {"entityId", "objectType", "orgId"}
    assert piece.count() > 0


def test_entity_registry_accepts_extra_pieces(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    base_reg = build_entity_registry(spark, main_tables)
    extra = build_entitylink_v3_entity_piece(main_tables)
    combined_reg = build_entity_registry(spark, main_tables, extra_pieces=[extra])
    assert combined_reg.count() >= base_reg.count()
    types = {r["objectType"] for r in combined_reg.select("objectType").distinct().collect()}
    assert "CONTROLTEMPLATE" in types


def test_entity_registry_without_extra_pieces_is_unchanged(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    reg = build_entity_registry(spark, main_tables)
    types = {r["objectType"] for r in reg.select("objectType").distinct().collect()}
    assert "CONTROLTEMPLATE" not in types


def test_subject_registry_accepts_size_override(spark):
    reg = build_subject_registry(spark, user_count=10, group_count=5)
    assert reg.filter(reg.subjectType == "USER_ID").count() == 10
    assert reg.filter(reg.subjectType == "USER_GROUP").count() == 5


def test_subject_registry_default_is_unchanged(spark):
    reg = build_subject_registry(spark)
    assert reg.filter(reg.subjectType == "USER_ID").count() == config.SUBJECT_REGISTRY_USER_COUNT
