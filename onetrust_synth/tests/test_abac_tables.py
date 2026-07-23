from pyspark.sql import functions as F

from onetrust_synth import config
from onetrust_synth.abac_schema import ABAC_ASSIGNMENT_COLUMNS, ABAC_ASSIGNMENT_PERMISSION_COLUMNS, ABAC_ENTITY_SUBJECT_ASSIGNMENT_COLUMNS
from onetrust_synth.abac_tables import build_abac_assignment, build_abac_assignment_permission, build_abac_entity_subject_assignment
from onetrust_synth.registries import build_org_registry, build_subject_registry, build_entity_registry
from onetrust_synth.generate_main_tables import build_all_main_tables


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


def test_esa_row_count_and_columns(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    entity_reg = build_entity_registry(spark, main_tables)
    org_reg = build_org_registry(spark)
    subj_reg = build_subject_registry(spark)
    assignments = build_abac_assignment(spark, 200)

    esa = build_abac_entity_subject_assignment(spark, assignments, entity_reg, org_reg, subj_reg, 5000)
    assert esa.count() == 5000
    assert set(ABAC_ENTITY_SUBJECT_ASSIGNMENT_COLUMNS) == set(esa.columns)


def test_esa_entity_id_and_object_type_are_from_entity_registry(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    entity_reg = build_entity_registry(spark, main_tables)
    org_reg = build_org_registry(spark)
    subj_reg = build_subject_registry(spark)
    assignments = build_abac_assignment(spark, 200)

    esa = build_abac_entity_subject_assignment(spark, assignments, entity_reg, org_reg, subj_reg, 3000)
    valid_pairs = {(r["entityId"], r["objectType"]) for r in entity_reg.select("entityId", "objectType").collect()}
    esa_pairs = {(r["entityId"], r["objectType"]) for r in esa.select("entityId", "objectType").collect()}
    assert esa_pairs <= valid_pairs


def test_esa_subject_type_matches_subject_registry(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    entity_reg = build_entity_registry(spark, main_tables)
    org_reg = build_org_registry(spark)
    subj_reg = build_subject_registry(spark)
    assignments = build_abac_assignment(spark, 200)

    esa = build_abac_entity_subject_assignment(spark, assignments, entity_reg, org_reg, subj_reg, 3000)
    types = {r["subjectType"] for r in esa.select("subjectType").distinct().collect()}
    assert types <= {"USER_ID", "USER_GROUP"}


def test_esa_assignment_id_object_type_matches_the_assignment(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    entity_reg = build_entity_registry(spark, main_tables)
    org_reg = build_org_registry(spark)
    subj_reg = build_subject_registry(spark)
    assignments = build_abac_assignment(spark, 200)

    esa = build_abac_entity_subject_assignment(spark, assignments, entity_reg, org_reg, subj_reg, 3000)
    joined = esa.join(
        assignments.select(F.col("id").alias("assignmentId"), F.col("objectType").alias("a_objectType")),
        on="assignmentId",
    )
    mismatches = joined.filter(F.col("objectType") != F.col("a_objectType")).count()
    assert mismatches == 0
