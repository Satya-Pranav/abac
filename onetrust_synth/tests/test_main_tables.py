from onetrust_synth import config
from onetrust_synth.profile_csv import load_table_profile, get_columns
from onetrust_synth.main_tables import build_generic_table


def test_build_generic_table_matches_row_count(spark):
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "cmb_template")
    df = build_generic_table(spark, "cmb_template", 200, cols, sample_lookup=lambda col: [])
    assert df.count() == 200


def test_build_generic_table_produces_all_profiled_columns(spark):
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "cmb_controlimplementation")
    df = build_generic_table(spark, "cmb_controlimplementation", 50, cols, sample_lookup=lambda col: [])
    expected_cols = {c.name for c in cols}
    assert expected_cols.issubset(set(df.columns))


def test_low_cardinality_column_uses_real_sample_values_when_available(spark):
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "cmb_assessment")

    def sample_lookup(col_name):
        if col_name == "status":
            return ["Active", "Archived"]
        return []

    df = build_generic_table(spark, "cmb_assessment", 300, cols, sample_lookup=sample_lookup)
    seen = {r["status"] for r in df.select("status").collect() if r["status"] is not None}
    assert seen <= {"Active", "Archived"}


def test_id_like_column_is_unique(spark):
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "cmb_riskrelatedobjects")
    df = build_generic_table(spark, "cmb_riskrelatedobjects", 500, cols, sample_lookup=lambda col: [])
    ids = [r["riskId"] for r in df.select("riskId").collect()]
    assert len(ids) == len(set(ids))
