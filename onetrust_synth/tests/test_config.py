from onetrust_synth import config


def test_main_tables_has_11_entries_matching_design_doc():
    assert len(config.MAIN_TABLES) == 11
    assert config.MAIN_TABLES["cmb_assessment"] == 4984
    assert config.MAIN_TABLES["cmb_v_assessment_v4"] == 1591030
    assert config.MAIN_TABLES["orghierarchy"] == 183
    assert config.MAIN_TABLES["entitygroupconfig"] == 0


def test_monitoring_table_is_flagged():
    assert config.MONITORING_TABLES == {"entitygroupconfig", "dbxtenantschemaversion"}
    assert "entitygroupconfig" not in (config.MAIN_TABLES.keys() - config.MONITORING_TABLES)


def test_abac_row_targets_phase1():
    assert config.ABAC_TABLE_ROW_TARGETS["ABAC_Assignment"] == 1000
    assert config.ABAC_TABLE_ROW_TARGETS["ABAC_EntitySubjectAssignment"] == 100000
    assert config.ABAC_TABLE_ROW_TARGETS["ABAC_OrgHierarchy"] == 183


def test_scaled_row_count_applies_multiplier():
    assert config.scaled_row_count("cmb_assessment", 1.0) == 4984
    assert config.scaled_row_count("cmb_assessment", 0.1) == 498


def test_entity_source_tables_cover_expected_five():
    assert set(config.ENTITY_SOURCE_TABLES.keys()) == {
        "cmb_assessment", "cmb_v_assessment_v4", "cmb_controlimplementation",
        "cmb_riskrelatedobjects", "cmb_template", "cmb_inventory",
        "cmb_v_inventoryaggregatedrisksummary",
    }
    # static single-type tables carry a literal object type
    assert config.ENTITY_SOURCE_TABLES["cmb_assessment"] == ("id", "ASSESSMENT")
    assert config.ENTITY_SOURCE_TABLES["cmb_riskrelatedobjects"] == ("riskId", "RISK")
    # per-row-type tables carry None — the type comes from a column, not a literal
    assert config.ENTITY_SOURCE_TABLES["cmb_inventory"] == ("id", None)


def test_inventory_type_mapping_handles_hyphenation():
    # confirmed against real cmb_v_inventoryaggregatedrisksummary sample data — NOT
    # a plain .upper(), "Processing Activities" hyphenates in the real vocabulary
    assert config.INVENTORY_TYPE_TO_OBJECT_TYPE["Processing Activities"] == "PROCESSING-ACTIVITIES"
    assert config.INVENTORY_TYPE_TO_OBJECT_TYPE["Assets"] == "ASSETS"
    assert config.INVENTORY_TYPE_TO_OBJECT_TYPE["Vendors"] == "VENDORS"


def test_remaining_main_tables_has_23_entries_with_real_row_counts():
    assert len(config.REMAINING_MAIN_TABLES) == 23
    assert config.REMAINING_MAIN_TABLES["entity_v3"] == 4153100
    assert config.REMAINING_MAIN_TABLES["cmb_v_assessmentquestionresponse_v3"] == 9493225
    assert config.REMAINING_MAIN_TABLES["dbxtenantschemaversion"] == 1070
    assert config.REMAINING_MAIN_TABLES["cmb_v_assessmenttag"] == 18
    # the 3 large tables with no matching sample data must NOT be present
    assert "entityattributevalue_v3" not in config.REMAINING_MAIN_TABLES
    assert "cmb_v_riskattributevalue_v3" not in config.REMAINING_MAIN_TABLES
    assert "cmb_v_inventorylinkattributemap" not in config.REMAINING_MAIN_TABLES


def test_all_scale2_main_tables_is_34_entries_and_does_not_mutate_main_tables():
    assert len(config.ALL_SCALE2_MAIN_TABLES) == 34
    assert set(config.ALL_SCALE2_MAIN_TABLES) == set(config.MAIN_TABLES) | set(config.REMAINING_MAIN_TABLES)
    # Phase 1's MAIN_TABLES must be completely unaffected
    assert len(config.MAIN_TABLES) == 11


def test_dbxtenantschemaversion_is_flagged_monitoring():
    assert config.MONITORING_TABLES == {"entitygroupconfig", "dbxtenantschemaversion"}


def test_scaled_row_count_accepts_alternate_table_dict():
    assert config.scaled_row_count("entity_v3", 5.0, config.REMAINING_MAIN_TABLES) == 20765500
    # default behavior (no override) must be unchanged
    assert config.scaled_row_count("cmb_assessment", 1.0) == 4984


def test_scale2_abac_row_targets():
    assert config.SCALE2_ABAC_TABLE_ROW_TARGETS["ABAC_EntitySubjectAssignment"] == 1_000_000_000
    assert config.SCALE2_ABAC_TABLE_ROW_TARGETS["ABAC_Assignment"] == 100_000
    assert config.SCALE2_ABAC_TABLE_ROW_TARGETS["ABAC_AssignmentPermission"] == 1_000_000
    assert config.SCALE2_ABAC_TABLE_ROW_TARGETS["UserGroupMembers"] == 500_000
    assert config.SCALE2_ABAC_TABLE_ROW_TARGETS["ABAC_OrgHierarchy"] == 183
    # Phase 1's targets must be completely unaffected
    assert config.ABAC_TABLE_ROW_TARGETS["ABAC_EntitySubjectAssignment"] == 100_000


def test_scale2_registry_sizes():
    assert config.SCALE2_SUBJECT_REGISTRY_USER_COUNT == 200_000
    assert config.SCALE2_SUBJECT_REGISTRY_GROUP_COUNT == 30_000
    assert config.SCALE2_STANDALONE_ENTITIES_PER_TYPE == 10_000
    # Phase 1's registry sizes must be completely unaffected
    assert config.SUBJECT_REGISTRY_USER_COUNT == 2000
