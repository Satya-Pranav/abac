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


def test_numeric_and_temporal_columns_respect_real_null_rate(spark):
    # cmb_controlimplementation.deadline is real null_rate=1.0 (always null in
    # production); number (bigint) is real null_rate≈0.998. A generator that
    # ignores null_rate for numeric/temporal columns silently fabricates dense
    # data the real table doesn't have — caught by a prior task review.
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "cmb_controlimplementation")
    df = build_generic_table(spark, "cmb_controlimplementation", 500, cols, sample_lookup=lambda col: [])
    deadline_col = next(c for c in cols if c.name == "deadline")
    assert deadline_col.null_rate == 1.0
    non_null_deadlines = df.filter(df.deadline.isNotNull()).count()
    assert non_null_deadlines == 0

    number_col = next(c for c in cols if c.name == "number")
    assert number_col.null_rate > 0.9
    non_null_numbers = df.filter(df.number.isNotNull()).count()
    assert non_null_numbers < 25  # ~0.2% of 500 should be non-null, not all 500


def test_high_cardinality_string_column_without_samples_does_not_collapse(spark):
    # cmb_assessment.templateID has real ndv=2558 with no calibrated sample
    # values supplied here (sample_lookup returns []). A generator that funnels
    # every string column through the same low-cardinality categorical path
    # collapses this to ~10 generic placeholder values regardless of real
    # cardinality — caught by a prior task review.
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "cmb_assessment")
    df = build_generic_table(spark, "cmb_assessment", 500, cols, sample_lookup=lambda col: [])
    distinct_template_ids = df.select("templateID").distinct().count()
    assert distinct_template_ids > 50  # nowhere near the real ndv=2558, but far above a 10-value collapse
