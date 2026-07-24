# onetrust_synth/abac_tables.py
from pyspark.sql import functions as F
from pyspark.sql import SparkSession, DataFrame, Window

from onetrust_synth import config
from onetrust_synth.generator import base_row_id_df, add_categorical_column, deterministic_index
from onetrust_synth.sample_csv import load_entity_type_reference_values
from onetrust_synth.verbatim_tables import build_orghierarchy_df

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


def build_abac_entity_subject_assignment(
    spark: SparkSession, assignment_df: DataFrame, entity_registry: DataFrame,
    org_registry: DataFrame, subject_registry: DataFrame, row_count: int,
) -> DataFrame:
    # Only entities whose objectType is actually represented in assignment_df
    # can ever be picked here — the later join against assignment_df is an
    # inner join on objectType, so anything else would be silently dropped,
    # undershooting row_count. Two real cases land in "anything else":
    #  1. entity_registry rows with objectType IS NULL — the ~35% of rows
    #     harvested from cmb_inventory, mirroring that column's real 99.57%
    #     null rate (see Task 11). NULL never equals a real assignment
    #     objectType.
    #  2. entity_registry rows with objectType == "TEMPLATE" — cmb_template is
    #     harvested with the static type TEMPLATE (see config.ENTITY_SOURCE_TABLES),
    #     but TEMPLATE is NOT one of the 20 real entityTypeReference values
    #     (verified against reportingmoduletoentityreferencemapping_v's sample
    #     data), so it never appears in assignment_df.objectType, which is
    #     drawn from that same 20-value vocabulary. This isn't a sampling
    #     fluke fixable by a bigger assignment_df — TEMPLATE structurally has
    #     no ABAC assignment in the real system.
    # Filtering to the objectTypes assignment_df actually has (rather than a
    # hardcoded IS NOT NULL) handles both cases generically and adapts to
    # whatever assignment_df is passed in.
    assignment_object_types = [r["objectType"] for r in assignment_df.select("objectType").distinct().collect()]
    entity_registry = entity_registry.filter(F.col("objectType").isin(assignment_object_types))

    entity_reg_indexed = entity_registry.withColumn(
        "_e_idx", F.row_number().over(Window.orderBy("entityId")) - 1
    )
    n_entities = entity_registry.count()

    subj_reg_indexed = subject_registry.withColumn(
        "_s_idx", F.row_number().over(Window.orderBy("subjectId")) - 1
    )
    n_subjects = subject_registry.count()

    org_ids = [r["orgId"] for r in org_registry.select("orgId").distinct().collect()]

    df = base_row_id_df(spark, row_count)
    df = df.withColumn("_e_idx", deterministic_index(F.col("_row_id"), "esa.entity", n_entities))
    df = df.join(entity_reg_indexed, on="_e_idx", how="inner").drop("_e_idx")

    df = df.withColumn("_s_idx", deterministic_index(F.col("_row_id"), "esa.subject", n_subjects))
    df = df.join(subj_reg_indexed.withColumnRenamed("subjectId", "_subjectId").withColumnRenamed("subjectType", "_subjectType"), on="_s_idx", how="inner").drop("_s_idx")
    df = df.withColumnRenamed("_subjectId", "subjectId").withColumnRenamed("_subjectType", "subjectType")

    # pick an assignment whose objectType matches this row's entity objectType
    assignment_by_type = assignment_df.select(F.col("id").alias("assignmentId"), "objectType")
    df = df.join(assignment_by_type, on="objectType", how="inner")
    # a broadcast join on objectType can multiply rows if several assignments share
    # a type; pick one deterministically per source row instead of keeping all matches
    df = df.withColumn(
        "_pick",
        F.row_number().over(Window.partitionBy("_row_id").orderBy(F.xxhash64(F.col("_row_id"), F.col("assignmentId")))),
    )
    df = df.filter(F.col("_pick") == 1).drop("_pick")

    org_array = F.array(*[F.lit(o) for o in org_ids]) if org_ids else F.array(F.lit(None).cast("string"))
    org_idx = deterministic_index(F.col("_row_id"), "esa.org", max(len(org_ids), 1))
    df = df.withColumn("entityOrganizationId", F.element_at(org_array, (org_idx + F.lit(1)).cast("int")))

    df = df.withColumn("policyId", F.lit(None).cast("long"))
    df = df.withColumn("updateDT", F.to_timestamp(F.lit("2026-04-01 00:00:00")))
    df = df.withColumn("eventTime", F.col("updateDT"))
    df = df.withColumn("recModifiedTime", F.col("updateDT"))
    df = df.withColumn("tenantHash", F.lit("e40yx52dkbjpcqazimno9yvh4k"))
    del_marker = F.pmod(F.xxhash64(F.col("_row_id"), F.lit("esa.isDeleted_rare")), F.lit(20))
    df = df.withColumn("isDeleted", del_marker < 1)

    return df.drop("_row_id", "orgId")


def build_user_group_members(spark: SparkSession, subject_registry: DataFrame, row_count: int) -> DataFrame:
    users = subject_registry.filter(subject_registry.subjectType == "USER_ID").select(
        F.col("subjectId").alias("memberId")
    ).withColumn("_u_idx", F.row_number().over(Window.orderBy("memberId")) - 1)
    n_users = users.count()

    groups = subject_registry.filter(subject_registry.subjectType == "USER_GROUP").select(
        F.col("subjectId").alias("groupId")
    ).withColumn("_g_idx", F.row_number().over(Window.orderBy("groupId")) - 1)
    n_groups = groups.count()

    df = base_row_id_df(spark, row_count)
    df = df.withColumn("_u_idx", deterministic_index(F.col("_row_id"), "ugm.member", n_users))
    df = df.join(users, on="_u_idx", how="inner").drop("_u_idx")
    df = df.withColumn("_g_idx", deterministic_index(F.col("_row_id"), "ugm.group", n_groups))
    df = df.join(groups, on="_g_idx", how="inner").drop("_g_idx")

    df = df.withColumn("eventTime", F.to_timestamp(F.lit("2026-04-01 00:00:00")))
    df = df.withColumn("recModifiedTime", F.col("eventTime"))
    df = df.withColumn("isDeleted", F.lit(False))
    df = df.withColumn("tenantHash", F.lit("e40yx52dkbjpcqazimno9yvh4k"))
    return df.drop("_row_id").dropDuplicates(["memberId", "groupId"])


def build_org_hierarchy_base(spark: SparkSession) -> DataFrame:
    return build_orghierarchy_df(spark)


def build_org_hierarchy_view_sql() -> str:
    return (
        f"CREATE OR REPLACE VIEW {config.CATALOG}.{config.MAIN_SCHEMA}.OrgHierarchy AS "
        f"SELECT * FROM {config.CATALOG}.{config.MAIN_SCHEMA}.OrgHierarchyBase "
        f"WHERE isDeleted IS NOT TRUE"
    )
