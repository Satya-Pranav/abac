# onetrust_synth/generate_main_tables.py
from pyspark.sql import SparkSession

from onetrust_synth import config
from onetrust_synth.profile_csv import load_table_profile, get_columns
from onetrust_synth.sample_csv import load_column_values
from onetrust_synth.main_tables import build_generic_table
from onetrust_synth.nested_columns import attach_cmb_assessment_nested_columns, attach_cmb_inventory_nested_columns
from onetrust_synth.verbatim_tables import build_orghierarchy_df, build_cmb_v_inventoryaggregatedrisksummary_df
from onetrust_synth.generator import add_categorical_column
from onetrust_synth.write import write_delta_table

_TARGET_SCHEMA_HASH = "auto_qa_e40yx52dkbjpcqazimno9yvh4k"


def build_all_main_tables(spark: SparkSession, scale_factor: float = config.SCALE_FACTOR_DEFAULT) -> dict:
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    tables = {}

    # verbatim small tables — ignore scale_factor, they're real observed data
    tables["orghierarchy"] = build_orghierarchy_df(spark)
    tables["cmb_v_inventoryaggregatedrisksummary"] = build_cmb_v_inventoryaggregatedrisksummary_df(spark)

    for table_name in config.MAIN_TABLES:
        if table_name in tables:
            continue
        schema_key = config.MONITORING_SCHEMA if table_name in config.MONITORING_TABLES else _TARGET_SCHEMA_HASH
        cols = get_columns(profile, schema_key, table_name)
        row_count = config.scaled_row_count(table_name, scale_factor)

        def sample_lookup(col_name, _table=table_name):
            try:
                return load_column_values(_table, col_name)
            except (FileNotFoundError, KeyError, ValueError):
                return []

        df = build_generic_table(spark, table_name, row_count, cols, sample_lookup)

        if table_name == "cmb_assessment":
            df = attach_cmb_assessment_nested_columns(df)
        if table_name == "cmb_inventory":
            df = attach_cmb_inventory_nested_columns(df)
            # cmb_inventory's own sample file IS now correctly recovered by
            # sample_csv.py's positional-reconstruction fix (it is not header-
            # corrupted). But inventoryType is non-null in only 38/8750 real
            # rows, and the ~500-row sample happens to only capture 2 of the 3
            # real categories (verified: sample_lookup("cmb_inventory",
            # "inventoryType") == ["Assets", "Vendors"], missing "Processing
            # Activities") — too few observations for a reliable sample, not a
            # data-corruption issue. Override with the full, verified 3-value
            # vocabulary confirmed from cmb_v_inventoryaggregatedrisksummary's
            # trustworthy verbatim data (all 14/14 real rows, all 3 values
            # present) instead of leaving cmb_inventory's synthesized
            # inventoryType artificially limited to 2 of 3 real categories.
            df = add_categorical_column(
                df, "inventoryType", list(config.INVENTORY_TYPE_TO_OBJECT_TYPE.keys()),
                null_rate=next((c.null_rate for c in cols if c.name == "inventoryType"), 0.0),
                salt="cmb_inventory.inventoryType.real_vocab",
                row_id_col="id",  # _row_id was already dropped by build_generic_table; "id" is unique per row
            )

        tables[table_name] = df

    return tables


def main():
    spark = SparkSession.builder.appName("onetrust_synth-main-tables").getOrCreate()
    tables = build_all_main_tables(spark, scale_factor=config.SCALE_FACTOR_DEFAULT)
    for table_name, df in tables.items():
        schema = config.MONITORING_SCHEMA if table_name in config.MONITORING_TABLES else config.MAIN_SCHEMA
        write_delta_table(df, config.CATALOG, schema, table_name)
        print(f"Wrote {config.CATALOG}.{schema}.{table_name}: {df.count()} rows")


if __name__ == "__main__":
    main()
