import csv
import os
import tempfile

from onetrust_synth import config
from onetrust_synth.profile_csv import ColumnProfile, load_table_profile, get_columns


def test_parses_real_profile_csv_orghierarchy():
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "orghierarchy")
    names = [c.name for c in cols]
    assert names == [
        "rootOrgId", "rootOrgName", "orgId", "orgName", "parentOrgId",
        "parentOrgName", "eventTime", "recModifiedTime", "isDeleted", "tenantHash",
    ]
    org_id_col = next(c for c in cols if c.name == "orgId")
    assert org_id_col.ndv == 68
    assert org_id_col.null_rate == 0.0


def test_thirteen_distinct_tables_in_real_csv():
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    assert len(profile) == 13


def test_null_rate_computed_from_null_count_over_row_count():
    with tempfile.NamedTemporaryFile(mode="w", suffix=".csv", delete=False, newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["schema", "table", "column_name", "data_type", "row_count", "ndv", "null_count", "non_null_count", "min_val", "max_val", "error"])
        writer.writerow(["s", "t", "col_a", "string", "100", "10", "25.0", "75", "a", "z", ""])
        path = f.name
    try:
        profile = load_table_profile(path)
        cols = get_columns(profile, "s", "t")
        assert cols[0].null_rate == 0.25
    finally:
        os.unlink(path)


def test_missing_ndv_defaults_to_zero_for_unsupported_nested_types():
    # cmb_assessment.assessmentSectionReportInformations has no ndv/null stats
    # (the profiling engine couldn't compute min/max on nested types)
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "cmb_assessment")
    nested_col = next(c for c in cols if c.name == "questionRootMap")
    assert nested_col.ndv == 0
    assert nested_col.null_rate == 0.0
