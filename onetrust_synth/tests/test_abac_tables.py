from onetrust_synth import config
from onetrust_synth.abac_schema import ABAC_ASSIGNMENT_COLUMNS, ABAC_ASSIGNMENT_PERMISSION_COLUMNS
from onetrust_synth.abac_tables import build_abac_assignment, build_abac_assignment_permission


def test_abac_assignment_has_all_columns_and_row_count(spark):
    df = build_abac_assignment(spark, 500)
    assert df.count() == 500
    assert set(ABAC_ASSIGNMENT_COLUMNS) == set(df.columns)


def test_abac_assignment_object_type_from_real_vocabulary(spark):
    df = build_abac_assignment(spark, 500)
    types = {r["objectType"] for r in df.select("objectType").distinct().collect()}
    assert "ASSESSMENT" in types


def test_abac_assignment_id_is_unique_long(spark):
    df = build_abac_assignment(spark, 500)
    ids = [r["id"] for r in df.select("id").collect()]
    assert len(ids) == len(set(ids))
    assert df.schema["id"].dataType.typeName() == "long"


def test_abac_assignment_permission_references_real_assignment_ids(spark):
    assignments = build_abac_assignment(spark, 200)
    perms = build_abac_assignment_permission(spark, assignments, 2000)
    assert perms.count() == 2000
    assert set(ABAC_ASSIGNMENT_PERMISSION_COLUMNS) == set(perms.columns)
    valid_ids = {r["id"] for r in assignments.select("id").collect()}
    perm_ids = {r["assignmentId"] for r in perms.select("assignmentId").collect()}
    assert perm_ids <= valid_ids  # every permission references a real assignment
