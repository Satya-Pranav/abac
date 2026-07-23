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
