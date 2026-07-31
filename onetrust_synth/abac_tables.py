# onetrust_synth/abac_tables.py
from pyspark.sql import functions as F
from pyspark.sql import SparkSession, DataFrame, Window

from onetrust_synth import config
from onetrust_synth.generator import base_row_id_df, add_categorical_column, deterministic_index, add_zip_index
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

    # add_zip_index, not row_number().over(Window.orderBy(...)) -- see the ESA build's identical
    # fix for why (single-partition global sort). assignment_ids is small (bounded by
    # ABAC_Assignment's row target), but this keeps the indexing idiom consistent everywhere.
    indexed_assignments = add_zip_index(assignment_ids, "_pick_idx").cache()
    idx = deterministic_index(F.col("_row_id"), "perm.assignment_pick", indexed_assignments.count())
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

    # add_zip_index, not row_number().over(Window.orderBy(...)) -- the latter has no
    # partitionBy, so Spark funnels every row through a single partition to compute the
    # global order (its own runtime warning: "No Partition Defined for Window operation!
    # ... serious performance degradation") -- confirmed live 2026-07-31 as the actual
    # bottleneck once entity/subject registries reach real scale-2 size. .cache() is required
    # here: zipWithIndex's index depends on physical partition/row order, so without caching,
    # the .count() below and the later join could each independently re-derive the index from
    # a different physical execution.
    entity_reg_indexed = add_zip_index(entity_registry, "_e_idx").cache()
    n_entities = entity_reg_indexed.count()

    subj_reg_indexed = add_zip_index(subject_registry, "_s_idx").cache()
    n_subjects = subj_reg_indexed.count()

    org_ids = [r["orgId"] for r in org_registry.select("orgId").distinct().collect()]

    df = base_row_id_df(spark, row_count)
    df = df.withColumn("_e_idx", deterministic_index(F.col("_row_id"), "esa.entity", n_entities))
    df = df.join(entity_reg_indexed, on="_e_idx", how="inner").drop("_e_idx")

    df = df.withColumn("_s_idx", deterministic_index(F.col("_row_id"), "esa.subject", n_subjects))
    # subject_registry is bounded (SCALE2_SUBJECT_REGISTRY_USER_COUNT + _GROUP_COUNT =
    # 230k rows at real scale) -- small enough to always broadcast rather than shuffle the
    # 1B-row side of this join by _s_idx.
    subj_reg_broadcast = F.broadcast(
        subj_reg_indexed.withColumnRenamed("subjectId", "_subjectId").withColumnRenamed("subjectType", "_subjectType")
    )
    df = df.join(subj_reg_broadcast, on="_s_idx", how="inner").drop("_s_idx")
    df = df.withColumnRenamed("_subjectId", "subjectId").withColumnRenamed("_subjectType", "subjectType")

    # pick an assignment whose objectType matches this row's entity objectType. A plain
    # join-then-filter (join on objectType, then rank+filter to 1) fans out every row to ALL
    # assignments sharing its objectType before trimming back down -- with ~100k assignments
    # spread across ~20 types (~5k/type), that's ~5k intermediate rows per input row, i.e.
    # trillions of rows at 1B scale (confirmed live 2026-07-31: Step 3 stalled 48+ min at 1B
    # ESA scale on a 32-core/256GB cluster). Indexing each objectType's assignments and
    # picking by a per-row local index instead makes this a clean 1:1 join -- same
    # deterministic-index-then-join pattern already used above for entity/subject, just
    # grouped by objectType instead of global.
    assignment_by_type = assignment_df.select(F.col("id").alias("assignmentId"), "objectType")
    assignment_indexed = assignment_by_type.withColumn(
        "_local_idx", F.row_number().over(Window.partitionBy("objectType").orderBy("assignmentId")) - 1
    )
    type_counts = assignment_by_type.groupBy("objectType").agg(F.count("*").alias("_type_count"))

    df = df.join(F.broadcast(type_counts), on="objectType", how="inner")
    df = df.withColumn(
        "_local_idx",
        F.pmod(F.xxhash64(F.col("_row_id"), F.lit("esa.assignment_pick")), F.col("_type_count")),
    ).drop("_type_count")
    df = df.join(F.broadcast(assignment_indexed), on=["objectType", "_local_idx"], how="inner").drop("_local_idx")

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
    # add_zip_index, not row_number().over(Window.orderBy(...)) -- see the ESA build's identical
    # fix for why (single-partition global sort, ~200k/~30k rows at real scale).
    users = add_zip_index(
        subject_registry.filter(subject_registry.subjectType == "USER_ID").select(F.col("subjectId").alias("memberId")),
        "_u_idx",
    ).cache()
    n_users = users.count()

    groups = add_zip_index(
        subject_registry.filter(subject_registry.subjectType == "USER_GROUP").select(F.col("subjectId").alias("groupId")),
        "_g_idx",
    ).cache()
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
        f"CREATE OR REPLACE VIEW {config.CATALOG}.{config.MAIN_SCHEMA}.ABAC_OrgHierarchy AS "
        f"SELECT * FROM {config.CATALOG}.{config.MAIN_SCHEMA}.OrgHierarchyBase "
        f"WHERE isDeleted IS NOT TRUE"
    )
