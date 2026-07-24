import os
import sys

import pytest
from pyspark.sql import SparkSession

# Must be set before the JVM/worker subprocesses launch, or PySpark workers pick
# up the system python3 (3.9 on this machine) instead of this venv's python3.12,
# and any code path that spawns real Python workers (e.g. spark.createDataFrame
# on a list of dicts, as opposed to spark.range()-based tests) fails with
# PYSPARK_VERSION_MISMATCH.
os.environ["PYSPARK_PYTHON"] = sys.executable
os.environ["PYSPARK_DRIVER_PYTHON"] = sys.executable


@pytest.fixture(scope="session")
def spark():
    session = (
        SparkSession.builder
        .master("local[2]")
        .appName("onetrust_synth-tests")
        .config("spark.ui.showConsoleProgress", "false")
        # Databricks Runtime enables ANSI mode by default; local Spark does not.
        # Matching it here means a malformed cast (e.g. '' -> DOUBLE) fails locally
        # instead of silently returning NULL and only surfacing on a real cluster run.
        .config("spark.sql.ansi.enabled", "true")
        .getOrCreate()
    )
    yield session
    session.stop()
