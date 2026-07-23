"""
Handles the 6 struct/map/list columns with no flat profiled stats.
questionRootMap and userIdsAssociatedWithAssessment (both cmb_assessment) are
referenced by real compatible queries (via element_at()/array_contains()) and
get genuine, well-typed generated data. The other 4 are confirmed unreferenced
by any compatible query and get mostly-null placeholders — see design doc
section 5.3.
"""
from pyspark.sql import functions as F
from pyspark.sql import DataFrame

_QUESTION_KEYS = [
    "a2d09d79-b6e2-42d7-a04d-a5726a062738",
    "d82a01e9-276b-4499-8b47-7d5068536f4f",
    "f3c1a0aa-1234-4a1b-9c3d-9a1b2c3d4e5f",
]
_QUESTION_TYPES = ["SINGLE_CHOICE", "MULTI_CHOICE", "TEXT"]
_RESPONSE_TYPES = ["TEXT", "OPTION"]


def _null_placeholder(df: DataFrame, col_name: str, spark_type: str) -> DataFrame:
    return df.withColumn(col_name, F.lit(None).cast(spark_type))


def attach_cmb_assessment_nested_columns(df: DataFrame) -> DataFrame:
    idx = F.pmod(F.xxhash64(F.col("id"), F.lit("questionRootMap")), F.lit(len(_QUESTION_KEYS))).cast("int")
    key = F.element_at(F.array(*[F.lit(k) for k in _QUESTION_KEYS]), idx + F.lit(1))
    qtype_idx = F.pmod(F.xxhash64(F.col("id"), F.lit("qtype")), F.lit(len(_QUESTION_TYPES))).cast("int")
    qtype = F.element_at(F.array(*[F.lit(t) for t in _QUESTION_TYPES]), qtype_idx + F.lit(1))
    rtype_idx = F.pmod(F.xxhash64(F.col("id"), F.lit("rtype")), F.lit(len(_RESPONSE_TYPES))).cast("int")
    rtype = F.element_at(F.array(*[F.lit(t) for t in _RESPONSE_TYPES]), rtype_idx + F.lit(1))

    response_struct = F.struct(F.lit("sample response value").alias("value"), F.lit("resp_key_1").alias("valueKey"))
    value_struct = F.struct(
        qtype.alias("questionType"),
        F.lit("STRING").alias("dataType"),
        F.lit("ANSWERED").alias("state"),
        F.lit(False).alias("maturityScaleAllowed"),
        F.lit("auto-generated question detail").alias("questionDetailedInfo"),
        F.array(response_struct).alias("responses"),
        rtype.alias("responseType"),
    )
    df = df.withColumn("questionRootMap", F.create_map(key, value_struct))

    user_id_1 = F.concat(F.lit("user_"), (F.pmod(F.xxhash64(F.col("id"), F.lit("uid1")), F.lit(2000))).cast("string"))
    user_id_2 = F.concat(F.lit("user_"), (F.pmod(F.xxhash64(F.col("id"), F.lit("uid2")), F.lit(2000))).cast("string"))
    df = df.withColumn("userIdsAssociatedWithAssessment", F.array(user_id_1, user_id_2))

    struct_type = "struct<id:struct<id:string>,name:struct<respondents:array<struct<value:string,valueKey:string>>>>"
    df = _null_placeholder(df, "assessmentSectionReportInformations", f"array<{struct_type}>")
    df = _null_placeholder(df, "questionMap", "map<string,struct<key:string,value:string>>")
    return df


def attach_cmb_inventory_nested_columns(df: DataFrame) -> DataFrame:
    df = _null_placeholder(df, "attributes", "map<string,array<struct<value:string,valueKey:string>>>")
    df = _null_placeholder(df, "personalDataObjects", "array<struct<dataElement:string,dataCategory:string>>")
    return df
