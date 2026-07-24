from onetrust_synth.generator import base_row_id_df, add_categorical_column, add_id_column


def test_base_row_id_df_has_expected_row_count(spark):
    df = base_row_id_df(spark, 500)
    assert df.count() == 500
    assert df.columns == ["_row_id"]


def test_add_categorical_column_only_uses_given_values(spark):
    df = base_row_id_df(spark, 200)
    df = add_categorical_column(df, "status", ["Active", "Archived"], salt="status")
    seen = {r["status"] for r in df.select("status").collect()}
    assert seen <= {"Active", "Archived"}
    assert len(seen) == 2  # with 200 rows and 2 values, both should appear


def test_add_categorical_column_respects_null_rate(spark):
    df = base_row_id_df(spark, 10000)
    df = add_categorical_column(df, "maybe_null", ["A", "B"], null_rate=0.5, salt="maybe_null")
    null_count = df.filter(df.maybe_null.isNull()).count()
    # deterministic hash-based nulling won't be exactly 50% but should be close
    assert 4000 < null_count < 6000


def test_add_categorical_column_is_deterministic_across_runs(spark):
    df1 = add_categorical_column(base_row_id_df(spark, 100), "x", ["A", "B", "C"], salt="x")
    df2 = add_categorical_column(base_row_id_df(spark, 100), "x", ["A", "B", "C"], salt="x")
    rows1 = [r["x"] for r in df1.orderBy("_row_id").collect()]
    rows2 = [r["x"] for r in df2.orderBy("_row_id").collect()]
    assert rows1 == rows2


def test_add_id_column_produces_unique_values(spark):
    df = base_row_id_df(spark, 1000)
    df = add_id_column(df, "id", prefix="assess_")
    ids = [r["id"] for r in df.select("id").collect()]
    assert len(ids) == len(set(ids))
    assert all(i.startswith("assess_") for i in ids)
