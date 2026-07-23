# onetrust_synth/tests/test_sample_csv.py
from onetrust_synth.sample_csv import (
    sample_file_path, load_column_values, load_entity_type_reference_values, load_rows,
)


def test_sample_file_path_resolves_known_table():
    path = sample_file_path("cmb_assessment")
    assert path.endswith("sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_assessment.csv")


def test_load_column_values_returns_real_distinct_values():
    # parentOrgID has real distinct non-null values in the sample data
    values = load_column_values("cmb_assessment", "parentOrgID")
    assert set(values) == {"0", "1"}


def test_load_entity_type_reference_values_handles_misaligned_header():
    # The sample file's header row is a stale copy from a different table's export;
    # the real (reportingModule, entityTypeReference) pairs are the first two columns
    # positionally. This must return the real 21 pairs / 20 distinct types, not the
    # misleading header-named columns (inventoryID/entityID/etc, which are all empty).
    pairs = load_entity_type_reference_values()
    assert len(pairs) == 21
    assert ("ASSESSMENT", "ASSESSMENT") in pairs
    assert ("AI_GOVERNANCE", "AIAGENTS") in pairs
    distinct_types = {t for _, t in pairs}
    assert len(distinct_types) == 20
    assert "ASSETS" in distinct_types
    assert "VENDORS" in distinct_types


def test_load_rows_returns_full_dicts_for_small_table():
    rows = load_rows("orghierarchy")
    assert len(rows) == 183
    # Verify the row contains expected columns (actual column names from sample data)
    assert set(rows[0].keys()) >= {"orgID", "parentOrgID", "entityID"}


def test_cmb_inventory_sample_file_is_flagged_as_known_bad():
    # Verified: sample_..._cmb_inventory.csv's header is a 21-column copy of
    # cmb_v_inventoryaggregatedrisksummary's header, but the data rows have 19
    # fields matching cmb_inventory's REAL (different) schema — the header simply
    # does not describe its own file's data. Unlike
    # reportingmoduletoentityreferencemapping_v (misaligned but recoverable by
    # reading positionally), there's no reliable way to recover real column values
    # here since we don't know the true positional order. Calling code must not
    # silently trust this file.
    import pytest
    with pytest.raises(ValueError, match="known-bad"):
        load_column_values("cmb_inventory", "inventoryType")
