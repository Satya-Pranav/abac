"""
Runs the 50 compatible queries from onetrust_sanity_run_annotated.csv against the
live, policy-active abac_onetrust dataset. Must run on Databricks (needs spark.sql
against a real Unity Catalog session) — no local equivalent, so no pytest here.
Run via: databricks-connect, a notebook %run, or `python3 run_compatible_queries.py`
from a cluster driver with `spark` already in scope.
"""
import csv

from onetrust_synth import config


def load_compatible_queries() -> list[dict]:
    with open(config.ANNOTATED_QUERIES_CSV, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        return [row for row in reader if row["in_scope"] == "yes"]


def run_all(spark) -> dict:
    queries = load_compatible_queries()
    results = {"passed": [], "failed": []}
    for row in queries:
        alias = row["query_alias"]
        try:
            df = spark.sql(row["modified_query"])
            count = df.count()
            results["passed"].append((alias, count))
        except Exception as e:
            results["failed"].append((alias, str(e)[:300]))
    return results


def main():
    from pyspark.sql import SparkSession
    spark = SparkSession.builder.appName("onetrust_synth-query-validation").getOrCreate()
    results = run_all(spark)
    print(f"Passed: {len(results['passed'])}")
    print(f"Failed: {len(results['failed'])}")
    for alias, err in results["failed"]:
        print(f"  FAIL {alias}: {err}")


if __name__ == "__main__":
    main()
