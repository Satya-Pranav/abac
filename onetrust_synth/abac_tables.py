# onetrust_synth/abac_tables.py
from pyspark.sql import functions as F
from pyspark.sql import SparkSession, DataFrame, Window

from onetrust_synth.generator import base_row_id_df, add_categorical_column, deterministic_index
from onetrust_synth.sample_csv import load_entity_type_reference_values

_STATIC_IDENTIFIERS = ["owner", "viewer", "internal-owner"]
_ASSIGNMENT_PERMISSION_SUFFIXES = ["basic.view", "advanced.view"]


def _entity_type_pool() -> list:
    return sorted({t for _, t in load_entity_type_reference_values()})


def build_abac_assignment(spark: SparkSession, row_count: int) -> DataFrame:
    df = base_row_id_df(spark, row_count)
    df = df.withColumn("id", F.col("_row_id"))  # long, unique by construction
    df = df.withColumn("guid", F.expr("uuid()"))
    df = add_categorical_column(df, "staticIdentifier", _STATIC_IDENTIFIERS, salt="assignment.staticIdentifier")
    df = df.withColumn("name", F.initcap(F.col("staticIdentifier")))
    df = add_categorical_column(df, "objectType", _entity_type_pool(), salt="assignment.objectType")
    df = df.withColumn("sourceType", F.lit("SYSTEM"))
    df = add_categorical_column(df, "isActive", [True, False], null_rate=0.0, salt="assignment.isActive")
    df = df.withColumn("createdBy", F.lit("synthetic-generator"))
    df = df.withColumn("createDT", F.to_timestamp(F.lit("2026-03-17 00:00:00")))
    df = df.withColumn("updatedBy", F.lit("synthetic-generator"))
    df = df.withColumn("updateDT", F.to_timestamp(F.lit("2026-04-01 00:00:00")))
    df = df.withColumn("eventTime", F.col("updateDT"))
    df = df.withColumn("recModifiedTime", F.col("updateDT"))
    df = df.withColumn("tenantHash", F.lit("e40yx52dkbjpcqazimno9yvh4k"))
    # isDeleted should be rare (~5%), not an even split — pmod < 1 out of 20 buckets
    del_marker = F.pmod(F.xxhash64(F.col("_row_id"), F.lit("assignment.isDeleted_rare")), F.lit(20))
    df = df.withColumn("isDeleted", del_marker < 1)
    return df.drop("_row_id")


def build_abac_assignment_permission(spark: SparkSession, assignment_df: DataFrame, row_count: int) -> DataFrame:
    assignment_ids = assignment_df.select("id", "objectType").withColumnRenamed("id", "assignmentId")
    df = base_row_id_df(spark, row_count)

    idx = deterministic_index(F.col("_row_id"), "perm.assignment_pick", assignment_ids.count())
    indexed_assignments = assignment_ids.withColumn(
        "_pick_idx", F.row_number().over(Window.orderBy("assignmentId")) - 1
    )
    df = df.withColumn("_pick_idx", idx).join(indexed_assignments, on="_pick_idx", how="inner").drop("_pick_idx")

    df = add_categorical_column(df, "_suffix", _ASSIGNMENT_PERMISSION_SUFFIXES, salt="perm.suffix")
    df = df.withColumn("name", F.concat(F.lower(F.col("objectType")), F.lit(".fields."), F.col("_suffix"))).drop("_suffix", "objectType")
    df = df.withColumn("createdBy", F.lit("synthetic-generator"))
    df = df.withColumn("createDT", F.to_timestamp(F.lit("2026-03-17 00:00:00")))
    df = df.withColumn("updatedBy", F.lit("synthetic-generator"))
    df = df.withColumn("updateDT", F.to_timestamp(F.lit("2026-04-01 00:00:00")))
    df = df.withColumn("eventTime", F.col("updateDT"))
    df = df.withColumn("recModifiedTime", F.col("updateDT"))
    df = df.withColumn("tenantHash", F.lit("e40yx52dkbjpcqazimno9yvh4k"))
    df = df.withColumn("isDeleted", F.lit(False))
    return df.drop("_row_id")
