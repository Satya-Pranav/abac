"""
Deterministic, hash-based PySpark synthetic-column generation. Uses xxhash64 of
(row_id, salt) rather than F.rand() for both value SELECTION and null-injection,
so results are stable across Spark re-evaluation/re-partitioning (a well-known
F.rand() gotcha) — null-injection hashes on a distinct salt suffix, so it's
independent of which value would have been selected.
"""
from pyspark.sql import functions as F
from pyspark.sql import DataFrame, SparkSession
from pyspark.sql.types import LongType, StructField, StructType


def base_row_id_df(spark: SparkSession, n: int) -> DataFrame:
    # spark.range(n) without an explicit count defaults to defaultParallelism partitions
    # (e.g. 32 on a 32-core cluster) -- fine for small tables, but at n=1e9 that's ~31M
    # rows/partition: too coarse for even task distribution and for AQE to rebalance around
    # a slow task. Aim for ~250k rows/partition instead, floored at defaultParallelism (so
    # small tables don't lose parallelism) and capped at 4000 (so small/mid tables don't
    # pay per-task scheduling overhead for no benefit).
    default_parallelism = spark.sparkContext.defaultParallelism
    num_partitions = max(default_parallelism, min(n // 250_000, 4000)) if n > 0 else default_parallelism
    return spark.range(n, numPartitions=num_partitions).withColumnRenamed("id", "_row_id")


def deterministic_index(row_id_col, salt: str, n: int):
    return F.pmod(F.xxhash64(row_id_col, F.lit(salt)), F.lit(n))


def add_zip_index(df: DataFrame, index_col: str) -> DataFrame:
    """
    A contiguous, unique 0..n-1 index per row -- like row_number().over(Window.orderBy(...))
    but without funneling every row through a single partition to compute a total order
    (Spark warns "No Partition Defined for Window operation!" for exactly that pattern, and
    it's a real bottleneck: confirmed live 2026-07-31, entity/subject registry indexing
    stalled the ESA build at 1B-row scale). RDD.zipWithIndex() gets the same contiguous-index
    guarantee via per-partition row counts (cheap, parallel) + a driver-computed prefix-sum
    offset per partition -- no shuffle or global sort needed. The caller must .cache() the
    result before reusing it (e.g. once to count, once to join) -- zipWithIndex's index
    assignment depends on physical partition/row iteration order, which (unlike a
    value-based Window.orderBy) isn't guaranteed stable across independent re-evaluations of
    the same lazy lineage.
    """
    schema_with_idx = StructType(df.schema.fields + [StructField(index_col, LongType(), False)])
    indexed_rdd = df.rdd.zipWithIndex().map(lambda pair: pair[0] + (pair[1],))
    return df.sparkSession.createDataFrame(indexed_rdd, schema_with_idx)


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
