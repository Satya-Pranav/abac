from pyspark.sql import functions as F
from pyspark.sql import SparkSession, DataFrame

from onetrust_synth import config
from onetrust_synth.verbatim_tables import build_orghierarchy_df
from onetrust_synth.generator import base_row_id_df, add_id_column


def build_org_registry(spark: SparkSession) -> DataFrame:
    return build_orghierarchy_df(spark).select("orgId", "parentOrgId")


def build_subject_registry(spark: SparkSession) -> DataFrame:
    users = add_id_column(base_row_id_df(spark, config.SUBJECT_REGISTRY_USER_COUNT), "subjectId", prefix="user_")
    users = users.withColumn("subjectType", F.lit("USER_ID")).drop("_row_id")

    groups = add_id_column(base_row_id_df(spark, config.SUBJECT_REGISTRY_GROUP_COUNT), "subjectId", prefix="group_")
    groups = groups.withColumn("subjectType", F.lit("USER_GROUP")).drop("_row_id")

    return users.unionByName(groups)
