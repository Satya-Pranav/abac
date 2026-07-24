from onetrust_synth import config


def test_main_tables_has_11_entries_matching_design_doc():
    assert len(config.MAIN_TABLES) == 11
    assert config.MAIN_TABLES["cmb_assessment"] == 4984
    assert config.MAIN_TABLES["cmb_v_assessment_v4"] == 1591030
    assert config.MAIN_TABLES["orghierarchy"] == 183
    assert config.MAIN_TABLES["entitygroupconfig"] == 0


def test_monitoring_table_is_flagged():
    assert config.MONITORING_TABLES == {"entitygroupconfig"}
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
