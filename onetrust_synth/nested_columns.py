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
_QUESTION_STATES = ["ANSWERED", "UNANSWERED", "SKIPPED"]
_QUESTION_DETAILS = [
    "auto-generated question detail A",
    "auto-generated question detail B",
    "auto-generated question detail C",
]


def _null_placeholder(df: DataFrame, col_name: str, spark_type: str) -> DataFrame:
    return df.withColumn(col_name, F.lit(None).cast(spark_type))


def _pick(id_col, salt: str, values: list):
    idx = F.pmod(F.xxhash64(id_col, F.lit(salt)), F.lit(len(values)))
    return F.element_at(F.array(*[F.lit(v) for v in values]), (idx + F.lit(1)).cast("int"))


def _deterministic_uuid_shaped(id_col, salt: str):
    # Deterministic (hash-based, not a real random UUID — a random value
    # would break this project's reproducibility guarantee, see
    # generator.py), but formatted to LOOK like one: 8-4-4-4-12 hex groups,
    # matching the design doc's "UUID-shaped values" requirement.
    h = F.md5(F.concat(id_col, F.lit(salt)))
    return F.concat(
        F.substring(h, 1, 8), F.lit("-"),
        F.substring(h, 9, 4), F.lit("-"),
        F.substring(h, 13, 4), F.lit("-"),
        F.substring(h, 17, 4), F.lit("-"),
        F.substring(h, 21, 12),
    )


def attach_cmb_assessment_nested_columns(df: DataFrame) -> DataFrame:
    key = _pick(F.col("id"), "questionRootMap", _QUESTION_KEYS)
    qtype = _pick(F.col("id"), "qtype", _QUESTION_TYPES)
    rtype = _pick(F.col("id"), "rtype", _RESPONSE_TYPES)
    qstate = _pick(F.col("id"), "qstate", _QUESTION_STATES)
    qdetail = _pick(F.col("id"), "qdetail", _QUESTION_DETAILS)
    response_value = _pick(F.col("id"), "resp_value", ["response A", "response B", "response C"])
    response_key = _pick(F.col("id"), "resp_key", ["resp_key_1", "resp_key_2", "resp_key_3"])

    response_struct = F.struct(response_value.alias("value"), response_key.alias("valueKey"))
    value_struct = F.struct(
        qtype.alias("questionType"),
        F.lit("STRING").alias("dataType"),
        qstate.alias("state"),
        F.lit(False).alias("maturityScaleAllowed"),
        qdetail.alias("questionDetailedInfo"),
        F.array(response_struct).alias("responses"),
        rtype.alias("responseType"),
    )
    df = df.withColumn("questionRootMap", F.create_map(key, value_struct))

    user_id_1 = _deterministic_uuid_shaped(F.col("id"), "uid1")
    user_id_2 = _deterministic_uuid_shaped(F.col("id"), "uid2")
    df = df.withColumn("userIdsAssociatedWithAssessment", F.array(user_id_1, user_id_2))

    struct_type = "struct<id:struct<id:string>,name:struct<respondents:array<struct<value:string,valueKey:string>>>>"
    df = _null_placeholder(df, "assessmentSectionReportInformations", f"array<{struct_type}>")
    df = _null_placeholder(df, "questionMap", "map<string,struct<key:string,value:string>>")
    return df


def attach_cmb_inventory_nested_columns(df: DataFrame) -> DataFrame:
    df = _null_placeholder(df, "attributes", "map<string,array<struct<value:string,valueKey:string>>>")
    df = _null_placeholder(df, "personalDataObjects", "array<struct<dataElement:string,dataCategory:string>>")
    return df
