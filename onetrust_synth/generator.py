"""
Deterministic, hash-based PySpark synthetic-column generation. Uses xxhash64 of
(row_id, salt) rather than F.rand() for both value SELECTION and null-injection,
so results are stable across Spark re-evaluation/re-partitioning (a well-known
F.rand() gotcha) — null-injection hashes on a distinct salt suffix, so it's
independent of which value would have been selected.
"""
from pyspark.sql import functions as F
from pyspark.sql import DataFrame, SparkSession, Window


def base_row_id_df(spark: SparkSession, n: int) -> DataFrame:
    # spark.range(n) without an explicit count defaults to defaultParallelism partitions,
    # regardless of n -- fine at small/moderate scale (which is why this only overrides
    # partition count above _EXPLICIT_PARTITIONING_THRESHOLD), but at n=1e9 that default stays
    # flat while row count doesn't: tens of millions of rows/partition, too coarse for even
    # task distribution and for AQE to rebalance around a slow task. Above the threshold,
    # target ~250k rows/partition instead, capped at 4000.
    #
    # Earlier version floored this at spark.sql.shuffle.partitions (default 200) for every n --
    # that's wrong at small scale (confirmed live 2026-07-31: 200 partitions for a 200-row
    # test table is ~1 row/partition, and the resulting per-task scheduling overhead, repeated
    # across every table build in the test suite, is what actually caused a 6-8min run to
    # balloon past 25+ minutes and get killed). Below the threshold, defer to Spark's own
    # default entirely -- no need to query cluster parallelism (which would need
    # sparkContext, blocked on Spark Connect / Databricks shared clusters anyway).
    _EXPLICIT_PARTITIONING_THRESHOLD = 1_000_000
    if n <= _EXPLICIT_PARTITIONING_THRESHOLD:
        return spark.range(n).withColumnRenamed("id", "_row_id")
    num_partitions = min(n // 250_000, 4000)
    return spark.range(n, numPartitions=num_partitions).withColumnRenamed("id", "_row_id")


def deterministic_index(row_id_col, salt: str, n: int):
    return F.pmod(F.xxhash64(row_id_col, F.lit(salt)), F.lit(n))


def add_zip_index(df: DataFrame, index_col: str) -> DataFrame:
    """
    A contiguous, unique 0..n-1 index per row -- like row_number().over(Window.orderBy(...))
    but without funneling every row through a single partition to compute a total order
    (Spark warns "No Partition Defined for Window operation!" for exactly that pattern, and
    it's a real bottleneck: confirmed live 2026-07-31, entity/subject registry indexing
    stalled the ESA build at 1B-row scale).

    RDD.zipWithIndex() would be the obvious way to get this (per-partition counts + a
    driver-computed prefix-sum offset, no shuffle/global sort needed) but .rdd access is
    *also* blocked on Databricks shared/Unity-Catalog clusters, the same JVM_ATTRIBUTE_
    NOT_SUPPORTED restriction as sparkContext -- confirmed live 2026-07-31. This reimplements
    the same technique with DataFrame-only operations: a per-partition local row_number (each
    partition ranked independently), a small groupBy().count() collected to the driver, a
    driver-side prefix-sum offset per partition, then a broadcast join to add that offset in.

    The caller must .cache() the result before reusing it (e.g. once to count, once to join)
    -- the index assignment depends on physical partition/row layout, which (unlike a
    value-based Window.orderBy) isn't guaranteed stable across independent re-evaluations of
    the same lazy lineage.
    """
    partitioned = df.withColumn("_pid", F.spark_partition_id())
    local_idx = F.row_number().over(Window.partitionBy("_pid").orderBy(F.monotonically_increasing_id())) - 1
    partitioned = partitioned.withColumn("_local_idx", local_idx).cache()

    partition_counts = partitioned.groupBy("_pid").agg(F.count("*").alias("_pcount")).collect()
    offsets = []
    running_total = 0
    for row in sorted(partition_counts, key=lambda r: r["_pid"]):
        offsets.append((row["_pid"], running_total))
        running_total += row["_pcount"]

    offsets_df = df.sparkSession.createDataFrame(offsets, "_pid int, _offset long")
    result = partitioned.join(F.broadcast(offsets_df), on="_pid", how="inner")
    result = result.withColumn(index_col, (F.col("_offset") + F.col("_local_idx")).cast("long"))
    return result.drop("_pid", "_local_idx", "_offset")


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
