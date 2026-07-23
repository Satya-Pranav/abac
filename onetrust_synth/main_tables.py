from pyspark.sql import functions as F
from pyspark.sql import DataFrame, SparkSession

from onetrust_synth.generator import base_row_id_df, add_categorical_column, add_id_column, deterministic_index
from onetrust_synth.profile_csv import ColumnProfile

_ID_LIKE_SUFFIXES = ("id", "Id", "ID")
_LOW_CARDINALITY_MAX = 50
_PLACEHOLDER_VALUES = [f"value_{i}" for i in range(10)]
_NUMERIC_TYPES = ("int", "bigint", "double", "decimal")  # tuple: str.startswith() requires a tuple, not a set
_TEMPORAL_TYPES = {"timestamp", "date"}


def _is_id_like(col: ColumnProfile, row_count: int) -> bool:
    name_hits = col.name.endswith(_ID_LIKE_SUFFIXES) or col.name == "id"
    high_cardinality = row_count > 0 and col.ndv >= row_count * 0.9
    return name_hits and high_cardinality


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
            df = df.withColumn(col.name, idx.cast("double" if "double" in dtype or "decimal" in dtype else "long"))
        elif dtype in _TEMPORAL_TYPES:
            idx = deterministic_index(F.col("_row_id"), f"{table}.{col.name}", 90).cast("int")
            base_date = F.to_date(F.lit("2026-03-17"))
            d = F.date_add(base_date, idx)
            df = df.withColumn(col.name, F.to_timestamp(d) if dtype == "timestamp" else d)
        elif dtype.startswith(("map", "list", "struct", "array")):
            continue  # nested types handled by Task 7's overrides, not here
        else:
            values = sample_lookup(col.name)
            if not values:
                values = _PLACEHOLDER_VALUES
            if col.ndv and col.ndv <= _LOW_CARDINALITY_MAX:
                values = values[: max(col.ndv, 1)] or _PLACEHOLDER_VALUES
            df = add_categorical_column(df, col.name, values, null_rate=col.null_rate, salt=f"{table}.{col.name}")

    return df.drop("_row_id")
