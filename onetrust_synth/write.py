# onetrust_synth/write.py
from pyspark.sql import DataFrame


def write_delta_table(df: DataFrame, catalog: str, schema: str, table: str, partition_by: list[str] | None = None) -> None:
    full_name = f"{catalog}.{schema}.{table}"
    writer = df.write.format("delta").mode("overwrite").option("overwriteSchema", "true")
    if partition_by:
        writer = writer.partitionBy(*partition_by)
    writer.saveAsTable(full_name)
