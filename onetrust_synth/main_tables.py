from pyspark.sql import functions as F
from pyspark.sql import DataFrame, SparkSession

from onetrust_synth.generator import base_row_id_df, add_categorical_column, add_id_column, deterministic_index
from onetrust_synth.profile_csv import ColumnProfile

_ID_LIKE_SUFFIXES = ("id", "Id", "ID")
_LOW_CARDINALITY_MAX = 50
_PLACEHOLDER_POOL_CAP = 200  # cap on a synthetic value pool for a high-cardinality column with no real samples
_NUMERIC_TYPES = ("int", "bigint", "double", "decimal")  # tuple: str.startswith() requires a tuple, not a set
_TEMPORAL_TYPES = {"timestamp", "date"}


def _is_id_like(col: ColumnProfile, row_count: int) -> bool:
    name_hits = col.name.endswith(_ID_LIKE_SUFFIXES) or col.name == "id"
    high_cardinality = row_count > 0 and col.ndv >= row_count * 0.9
    return name_hits and high_cardinality


def _placeholder_values_for(table: str, col: ColumnProfile) -> list:
    # Sized toward the column's real cardinality (capped for practicality)
    # instead of a fixed 10-value pool — a fixed small pool collapses every
    # high-cardinality column with no real samples to the same handful of
    # values regardless of how varied the real data actually is.
    pool_size = min(col.ndv, _PLACEHOLDER_POOL_CAP) if col.ndv else 10
    return [f"{table}.{col.name}_{i}" for i in range(max(pool_size, 1))]


def _with_null_injection(df: DataFrame, col_name: str, value, null_rate: float, salt: str) -> DataFrame:
    if null_rate > 0:
        null_marker = F.pmod(F.xxhash64(F.col("_row_id"), F.lit(salt + "_null")), F.lit(10000))
        threshold = F.lit(int(null_rate * 10000))
        return df.withColumn(col_name, F.when(null_marker < threshold, F.lit(None)).otherwise(value))
    return df.withColumn(col_name, value)


def build_generic_table(spark: SparkSession, table: str, row_count: int, columns: list[ColumnProfile], sample_lookup) -> DataFrame:
    if row_count == 0:
        schema_fields = ", ".join(f"`{c.name}` STRING" for c in columns)
        return spark.createDataFrame([], schema=schema_fields)

    df = base_row_id_df(spark, row_count)

    for col in columns:
        dtype = col.data_type.lower()
        if _is_id_like(col, row_count):
            df = add_id_column(df, col.name, prefix=f"{table}_")
        elif dtype == "boolean":
            df = add_categorical_column(df, col.name, [True, False], null_rate=col.null_rate, salt=f"{table}.{col.name}")
        elif dtype.startswith(_NUMERIC_TYPES):
            idx = deterministic_index(F.col("_row_id"), f"{table}.{col.name}", 1000)
            value = idx.cast("double" if "double" in dtype or "decimal" in dtype else "long")
            df = _with_null_injection(df, col.name, value, col.null_rate, f"{table}.{col.name}")
        elif dtype in _TEMPORAL_TYPES:
            idx = deterministic_index(F.col("_row_id"), f"{table}.{col.name}", 90).cast("int")
            base_date = F.to_date(F.lit("2026-03-17"))
            d = F.date_add(base_date, idx)
            value = F.to_timestamp(d) if dtype == "timestamp" else d
            df = _with_null_injection(df, col.name, value, col.null_rate, f"{table}.{col.name}")
        elif dtype.startswith(("map", "list", "struct", "array")):
            continue  # nested types handled by Task 7's overrides, not here
        else:
            values = sample_lookup(col.name)
            if not values:
                values = _placeholder_values_for(table, col)
            elif col.ndv and col.ndv <= _LOW_CARDINALITY_MAX:
                values = values[: max(col.ndv, 1)] or _placeholder_values_for(table, col)
            df = add_categorical_column(df, col.name, values, null_rate=col.null_rate, salt=f"{table}.{col.name}")

    return df.drop("_row_id")
