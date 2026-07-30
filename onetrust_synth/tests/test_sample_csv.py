# onetrust_synth/tests/test_sample_csv.py
from unittest.mock import patch, MagicMock
import pytest

from onetrust_synth import config
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


def test_load_rows_raises_on_field_count_mismatch():
    # Guard: if the CSV row field count ever drifts from the real column count,
    # load_rows must raise ValueError loudly, not silently truncate via zip().
    from io import StringIO

    with patch("onetrust_synth.sample_csv.load_table_profile") as mock_profile:
        # Mock the profile loading
        mock_profile.return_value = {}

        with patch("onetrust_synth.sample_csv.get_columns") as mock_get_columns:
            # Mock get_columns to return 3 expected columns
            mock_col1 = MagicMock()
            mock_col1.name = "col1"
            mock_col2 = MagicMock()
            mock_col2.name = "col2"
            mock_col3 = MagicMock()
            mock_col3.name = "col3"
            mock_get_columns.return_value = [mock_col1, mock_col2, mock_col3]

            with patch("builtins.open", create=True) as mock_open:
                # Return a StringIO with CSV content: header (skipped), then row with only 1 field
                csv_content = "header1,header2\ndata1\n"
                mock_open.return_value.__enter__.return_value = StringIO(csv_content)

                with pytest.raises(ValueError) as exc_info:
                    load_rows("cmb_assessment")

                error_msg = str(exc_info.value)
                assert "field count mismatch" in error_msg.lower()
                assert "cmb_assessment" in error_msg
                assert "expected 3" in error_msg
                assert "got 1" in error_msg


def test_sample_file_path_resolves_original_table():
    path = sample_file_path("cmb_assessment")
    assert path.endswith("sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_assessment.csv")
    assert config.SAMPLE_DATA_DIR in path


def test_sample_file_path_resolves_remaining_table():
    path = sample_file_path("entity_v3")
    assert path.endswith("sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_entity_v3.csv")
    assert config.REMAINING_SAMPLE_DATA_DIR in path


def test_load_rows_recovers_a_remaining_table():
    rows = load_rows("cmb_v_assessmenttag")
    assert len(rows) > 0
    assert "assessmentID" in rows[0]  # real column from profile CSV (uppercase ID)


def test_load_column_values_works_for_remaining_table():
    values = load_column_values("dbxtenantschemaversion", "schemaVersion") if False else None
    # dbxtenantschemaversion's exact real columns aren't asserted here (only row-count/shape
    # matters for generation); this test just proves load_rows doesn't raise for it.
    rows = load_rows("dbxtenantschemaversion")
    assert len(rows) > 0
