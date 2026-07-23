"""
orghierarchy and cmb_v_inventoryaggregatedrisksummary are small enough (183 and
14 rows) that the design calls for using the real sample data near-verbatim
instead of synthesizing — see design doc section 3.
"""
from pyspark.sql import SparkSession, DataFrame

from onetrust_synth.sample_csv import load_rows


def build_orghierarchy_df(spark: SparkSession) -> DataFrame:
    rows = load_rows("orghierarchy")
    return spark.createDataFrame(rows)


def build_cmb_v_inventoryaggregatedrisksummary_df(spark: SparkSession) -> DataFrame:
    rows = load_rows("cmb_v_inventoryaggregatedrisksummary")
    return spark.createDataFrame(rows)
