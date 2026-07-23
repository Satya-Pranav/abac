"""
orghierarchy and cmb_v_inventoryaggregatedrisksummary are small enough (183 and
14 rows) that the design calls for using the real sample data near-verbatim
instead of synthesizing — see design doc section 3. Every value coming out of
load_rows() is a Python string (CSV has no native types), so numeric/temporal/
boolean columns are explicitly cast to their real profiled type — a prior task
review caught that leaving everything string-typed silently breaks ORDER BY on
numeric columns (lexicographic instead of numeric order), on the table that's
the single most-queried one in the Phase-1 compatible-query set.
"""
from pyspark.sql import functions as F
from pyspark.sql import SparkSession, DataFrame

from onetrust_synth import config
from onetrust_synth.sample_csv import load_rows
from onetrust_synth.profile_csv import load_table_profile, get_columns

_TARGET_TENANT_SCHEMA = "auto_qa_e40yx52dkbjpcqazimno9yvh4k"


def _spark_cast_type(profiled_dtype: str) -> str | None:
    dtype = profiled_dtype.lower()
    if dtype.startswith("bigint"):
        return "bigint"
    if dtype.startswith("int"):
        return "int"
    if dtype.startswith(("double", "decimal")):
        return "double"
    if dtype == "boolean":
        return "boolean"
    if dtype == "timestamp":
        return "timestamp"
    if dtype == "date":
        return "date"
    return None  # string and nested types: leave as the CSV's native string


def _cast_to_real_types(df: DataFrame, table: str) -> DataFrame:
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    for col in get_columns(profile, _TARGET_TENANT_SCHEMA, table):
        cast_type = _spark_cast_type(col.data_type)
        if cast_type and col.name in df.columns:
            df = df.withColumn(col.name, F.col(col.name).cast(cast_type))
    return df


def build_orghierarchy_df(spark: SparkSession) -> DataFrame:
    rows = load_rows("orghierarchy")
    df = spark.createDataFrame(rows)
    return _cast_to_real_types(df, "orghierarchy")


def build_cmb_v_inventoryaggregatedrisksummary_df(spark: SparkSession) -> DataFrame:
    rows = load_rows("cmb_v_inventoryaggregatedrisksummary")
    df = spark.createDataFrame(rows)
    return _cast_to_real_types(df, "cmb_v_inventoryaggregatedrisksummary")
