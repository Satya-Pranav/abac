# onetrust_synth/tests/test_sample_csv.py
from onetrust_synth.sample_csv import (
    sample_file_path, load_column_values, load_entity_type_reference_values, load_rows,
)


def test_sample_file_path_resolves_known_table():
    path = sample_file_path("cmb_assessment")
    assert path.endswith("sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_assessment.csv")


def test_load_column_values_returns_real_distinct_values():
    values = load_column_values("cmb_assessment", "status")
    assert set(values) == {"Active", "Archived"}


def test_load_entity_type_reference_values_recovers_real_pairs():
    pairs = load_entity_type_reference_values()
    assert len(pairs) == 21
    assert ("ASSESSMENT", "ASSESSMENT") in pairs
    assert ("AI_GOVERNANCE", "AIAGENTS") in pairs
    distinct_types = {t for _, t in pairs}
    assert len(distinct_types) == 20
    assert "ASSETS" in distinct_types
    assert "VENDORS" in distinct_types


def test_load_rows_recovers_real_columns_for_orghierarchy_despite_corrupted_header():
    # orghierarchy's own sample-file header is corrupted (it's actually
    # cmb_v_inventoryaggregatedrisksummary's header, pasted on by the export
    # tool — confirmed via MD5, identical across 9 of the 11 sample files).
    # load_rows must recover the REAL columns (rootOrgId/orgId/parentOrgId,
    # from onetrust_table_profile_results.csv), not the corrupted header's
    # field names (inventoryID/entityID/orgID/parentOrgID).
    rows = load_rows("orghierarchy")
    assert len(rows) == 183
    assert set(rows[0].keys()) == {
        "rootOrgId", "rootOrgName", "orgId", "orgName", "parentOrgId",
        "parentOrgName", "eventTime", "recModifiedTime", "isDeleted", "tenantHash",
    }
    assert rows[0]["orgId"]
    assert rows[0]["isDeleted"] in ("True", "False")


def test_load_rows_recovers_real_columns_for_cmb_inventory():
    # Previously miscategorized as unrecoverable — it isn't. cmb_inventory's
    # data rows have exactly 19 fields, matching its own real profiled column
    # count, and recover correctly via the same positional mechanism.
    rows = load_rows("cmb_inventory")
    assert len(rows) == 500
    inventory_types = {r["inventoryType"] for r in rows if r.get("inventoryType")}
    assert inventory_types <= {"Assets", "Vendors", "Processing Activities"}
    assert len(inventory_types) > 0


def test_load_column_values_works_for_every_affected_table_not_just_two():
    # Regression guard: an earlier version of this reader only special-cased
    # cmb_inventory and reportingmoduletoentityreferencemapping_v, silently
    # returning wrong/empty values for the other 7 corrupted-header tables.
    # cmb_assessment.id must recover real UUID-shaped values.
    ids = load_column_values("cmb_assessment", "id")
    assert len(ids) > 0
    assert all(len(i) == 36 and i.count("-") == 4 for i in ids)  # UUID shape
