# onetrust_synth/write.py
from pyspark.sql import DataFrame


def write_delta_table(
    df: DataFrame, catalog: str, schema: str, table: str,
    partition_by: list[str] | None = None, enable_column_mapping: bool = False,
) -> None:
    full_name = f"{catalog}.{schema}.{table}"
    writer = df.write.format("delta").mode("overwrite").option("overwriteSchema", "true")
    if enable_column_mapping:
        # Some real OneTrust column names contain characters Delta rejects by default (e.g.
        # a literal space in "lastModified Date", cmb_v_inventory_v4/cmb_v_controlimplementation_v4)
        # -- DELTA_INVALID_CHARACTERS_IN_COLUMN_NAMES otherwise. Column mapping preserves the
        # real name exactly rather than sanitizing it, since real customer queries reference it
        # verbatim (via backticks). Off by default -- phase1_run_all.py's tables never hit this,
        # so this stays opt-in rather than changing write_delta_table's behavior for every caller.
        writer = (
            writer
            .option("delta.columnMapping.mode", "name")
            .option("delta.minReaderVersion", "2")
            .option("delta.minWriterVersion", "5")
        )
    if partition_by:
        writer = writer.partitionBy(*partition_by)
    writer.saveAsTable(full_name)
