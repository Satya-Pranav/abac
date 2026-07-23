"""
Deterministic, hash-based PySpark synthetic-column generation. Uses xxhash64 of
(row_id, salt) rather than F.rand() for both value SELECTION and null-injection,
so results are stable across Spark re-evaluation/re-partitioning (a well-known
F.rand() gotcha) — null-injection hashes on a distinct salt suffix, so it's
independent of which value would have been selected.
"""
from pyspark.sql import functions as F
from pyspark.sql import DataFrame, SparkSession


def base_row_id_df(spark: SparkSession, n: int) -> DataFrame:
    return spark.range(n).withColumnRenamed("id", "_row_id")


def deterministic_index(row_id_col, salt: str, n: int):
    return F.pmod(F.xxhash64(row_id_col, F.lit(salt)), F.lit(n))


def add_categorical_column(df: DataFrame, col_name: str, values: list, null_rate: float = 0.0, salt: str = None, row_id_col: str = "_row_id") -> DataFrame:
    salt = salt or col_name
    values_array = F.array(*[F.lit(v) for v in values])
    idx = deterministic_index(F.col(row_id_col), salt, len(values))
    base = F.element_at(values_array, (idx + F.lit(1)).cast("int"))
    if null_rate > 0:
        null_marker = F.pmod(F.xxhash64(F.col(row_id_col), F.lit(salt + "_null")), F.lit(10000))
        threshold = F.lit(int(null_rate * 10000))
        return df.withColumn(col_name, F.when(null_marker < threshold, F.lit(None)).otherwise(base))
    return df.withColumn(col_name, base)


def add_id_column(df: DataFrame, col_name: str, prefix: str = "") -> DataFrame:
    return df.withColumn(col_name, F.concat(F.lit(prefix), F.col("_row_id").cast("string")))
