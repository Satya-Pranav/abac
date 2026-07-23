import pytest
from pyspark.sql import SparkSession


@pytest.fixture(scope="session")
def spark():
    session = (
        SparkSession.builder
        .master("local[2]")
        .appName("onetrust_synth-tests")
        .config("spark.ui.showConsoleProgress", "false")
        .getOrCreate()
    )
    yield session
    session.stop()
