from onetrust_synth.generator import base_row_id_df, add_id_column
from onetrust_synth.nested_columns import attach_cmb_assessment_nested_columns, attach_cmb_inventory_nested_columns


def test_question_root_map_is_a_well_typed_map(spark):
    df = add_id_column(base_row_id_df(spark, 20), "id", prefix="assess_")
    df = attach_cmb_assessment_nested_columns(df)
    row = df.select("questionRootMap").first()
    assert row["questionRootMap"] is not None
    # a MAP<STRING, STRUCT<...>> value: dict of key -> Row
    (key, value), = list(row["questionRootMap"].items())[:1]
    assert isinstance(key, str)
    assert value["questionType"] is not None
    assert value["responseType"] is not None
    assert isinstance(value["responses"], list)


def test_user_ids_associated_with_assessment_is_array_of_strings(spark):
    df = add_id_column(base_row_id_df(spark, 20), "id", prefix="assess_")
    df = attach_cmb_assessment_nested_columns(df)
    row = df.select("userIdsAssociatedWithAssessment").first()
    assert isinstance(row["userIdsAssociatedWithAssessment"], list)
    assert all(isinstance(x, str) for x in row["userIdsAssociatedWithAssessment"])


def test_unreferenced_nested_columns_are_mostly_null_placeholders(spark):
    df = add_id_column(base_row_id_df(spark, 1000), "id", prefix="assess_")
    df = attach_cmb_assessment_nested_columns(df)
    null_rate = df.filter(df.questionMap.isNull()).count() / 1000
    assert null_rate > 0.9  # "mostly null" per design doc section 5.3


def test_cmb_inventory_nested_columns_present_and_mostly_null(spark):
    df = add_id_column(base_row_id_df(spark, 1000), "id", prefix="inv_")
    df = attach_cmb_inventory_nested_columns(df)
    assert "attributes" in df.columns
    assert "personalDataObjects" in df.columns
    null_rate = df.filter(df.attributes.isNull()).count() / 1000
    assert null_rate > 0.9


def test_user_ids_associated_with_assessment_are_uuid_shaped(spark):
    # Design doc section 5.3 explicitly requires "a real LIST<STRING> of
    # UUID-shaped values per row" for this column — a prior review caught an
    # earlier version generating "user_0".."user_1999" instead. Deterministic
    # (not a real random UUID, which would break this project's core
    # reproducibility guarantee — see generator.py), but must look like a
    # UUID: 36 chars, 4 hyphens at the standard positions.
    df = add_id_column(base_row_id_df(spark, 200), "id", prefix="assess_")
    df = attach_cmb_assessment_nested_columns(df)
    rows = df.select("userIdsAssociatedWithAssessment").collect()
    all_ids = [uid for r in rows for uid in r["userIdsAssociatedWithAssessment"]]
    assert len(all_ids) > 0
    for uid in all_ids:
        assert len(uid) == 36
        assert uid[8] == "-" and uid[13] == "-" and uid[18] == "-" and uid[23] == "-"


def test_question_root_map_is_queryable_via_element_at_at_sql_level(spark):
    # The whole point of NOT null-placeholder-ing this column is that real
    # compatible queries call element_at(questionRootMap, '<uuid>') in a
    # SELECT list. A test that only inspects values already collected into
    # the Python driver doesn't verify this — it must be checked as an
    # actual Spark SQL expression, the same way the real queries use it.
    from pyspark.sql import functions as F

    df = add_id_column(base_row_id_df(spark, 500), "id", prefix="assess_")
    df = attach_cmb_assessment_nested_columns(df)
    for key in ["a2d09d79-b6e2-42d7-a04d-a5726a062738", "d82a01e9-276b-4499-8b47-7d5068536f4f", "f3c1a0aa-1234-4a1b-9c3d-9a1b2c3d4e5f"]:
        matched = df.filter(F.element_at(F.col("questionRootMap"), F.lit(key)).isNotNull()).count()
        assert matched > 0, f"element_at never resolved for key {key}"


def test_user_ids_associated_with_assessment_is_array_contains_queryable_at_sql_level(spark):
    from pyspark.sql import functions as F

    df = add_id_column(base_row_id_df(spark, 500), "id", prefix="assess_")
    df = attach_cmb_assessment_nested_columns(df)
    any_id = df.select(F.element_at(F.col("userIdsAssociatedWithAssessment"), 1).alias("uid")).first()["uid"]
    matched = df.filter(F.array_contains(F.col("userIdsAssociatedWithAssessment"), any_id)).count()
    assert matched > 0
