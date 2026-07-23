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


def test_entity_registry_entity_ids_are_unique_within_type(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    reg = build_entity_registry(spark, main_tables)
    dup_check = reg.groupBy("entityId", "objectType").count().filter("count > 1").count()
    assert dup_check == 0
