from pyspark.sql import functions as F
from pyspark.sql import SparkSession, DataFrame

from onetrust_synth import config
from onetrust_synth.verbatim_tables import build_orghierarchy_df
from onetrust_synth.generator import base_row_id_df, add_id_column
from onetrust_synth.sample_csv import load_entity_type_reference_values


def build_org_registry(spark: SparkSession) -> DataFrame:
    return build_orghierarchy_df(spark).select("orgId", "parentOrgId")


def build_subject_registry(spark: SparkSession) -> DataFrame:
    users = add_id_column(base_row_id_df(spark, config.SUBJECT_REGISTRY_USER_COUNT), "subjectId", prefix="user_")
    users = users.withColumn("subjectType", F.lit("USER_ID")).drop("_row_id")

    groups = add_id_column(base_row_id_df(spark, config.SUBJECT_REGISTRY_GROUP_COUNT), "subjectId", prefix="group_")
    groups = groups.withColumn("subjectType", F.lit("USER_GROUP")).drop("_row_id")

    return users.unionByName(groups)


def _inventory_type_to_object_type_column():
    """
    inventoryType -> objectType is NOT a plain .upper() — "Processing Activities"
    hyphenates to "PROCESSING-ACTIVITIES" in the real entityTypeReference
    vocabulary (config.INVENTORY_TYPE_TO_OBJECT_TYPE, verified against real sample
    data). Falls back to .upper() for any unmapped value rather than erroring.
    """
    mapping = config.INVENTORY_TYPE_TO_OBJECT_TYPE
    expr = F.upper(F.col("inventoryType"))
    for raw, mapped in mapping.items():
        expr = F.when(F.col("inventoryType") == raw, F.lit(mapped)).otherwise(expr)
    return expr


def build_entity_registry(spark: SparkSession, main_tables: dict) -> DataFrame:
    pieces = []

    for table_name, (id_col, static_type) in config.ENTITY_SOURCE_TABLES.items():
        df = main_tables[table_name]
        if static_type is not None:
            piece = df.select(F.col(id_col).alias("entityId")).withColumn("objectType", F.lit(static_type))
        else:
            piece = df.select(
                F.col(id_col).alias("entityId"),
                _inventory_type_to_object_type_column().alias("objectType"),
            )
        pieces.append(piece.withColumn("orgId", F.lit(None).cast("string")))

    harvested = pieces[0]
    for p in pieces[1:]:
        harvested = harvested.unionByName(p)
    # cmb_v_assessment_v4 is a fan-out view (one row per assessment-question,
    # not per assessment): its real `id` column has ndv=2,666 across
    # 1,591,030 real rows, so build_generic_table correctly synthesizes it as
    # a *repeated* categorical value, not a unique-per-row id (see
    # main_tables._is_id_like's 0.9-ndv-ratio threshold). Harvesting it
    # verbatim would emit the same assessment id hundreds of times. Every
    # other source table's id/riskId column is already unique per row, so
    # this dedup is a no-op for them and only fixes the real fan-out case.
    harvested = harvested.dropDuplicates(["entityId", "objectType"])

    covered_types = {t for _, (_, t) in config.ENTITY_SOURCE_TABLES.items() if t is not None}
    covered_types |= set(config.INVENTORY_TYPE_TO_OBJECT_TYPE.values())  # the per-row inventory types
    all_types = {t for _, t in load_entity_type_reference_values()}
    uncovered_types = sorted(all_types - covered_types)

    standalone_pieces = []
    for object_type in uncovered_types:
        df = add_id_column(
            base_row_id_df(spark, config.STANDALONE_ENTITIES_PER_TYPE),
            "entityId",
            prefix=f"{object_type.lower()}_",
        )
        df = df.withColumn("objectType", F.lit(object_type)).withColumn("orgId", F.lit(None).cast("string")).drop("_row_id")
        standalone_pieces.append(df)

    result = harvested
    for p in standalone_pieces:
        result = result.unionByName(p)
    return result
