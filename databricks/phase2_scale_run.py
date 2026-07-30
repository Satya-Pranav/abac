# Databricks notebook source
# =====================================================================
# Phase 2: scale-testing catalog (abac_onetrust_scale)
#
# Prerequisites (same as databricks/phase1_run_all.py):
#   - This repo checked out as a Databricks Repo (Git folder), attached to a cluster on a
#     Unity Catalog workspace, with the cluster's built-in `spark` session (do not
#     %pip install pyspark).
#   - CREATE CATALOG / CREATE SCHEMA rights.
#   - ALTER TABLE...SET TAGS + CREATE POLICY rights.
#   - The 3 governed tag keys (abac_column_id, abac_column_type, abac_column_org) already
#     created once via Settings > Catalog > Governed tags (same keys phase1 uses — no new
#     keys needed).
#   - The real service principal's application id (fill into the widget below).
#
# DRY_RUN=true (default) builds everything at Phase-1-equivalent scale (scale_factor=1,
# ESA=100,000) to prove the whole pipeline end-to-end cheaply before committing to the real
# 1B-row run — see design doc section 9. Only flip DRY_RUN to false once a dry run has
# passed validate_row_counts/validate_referential_integrity cleanly.
# =====================================================================

# COMMAND ----------
dbutils.widgets.text("service_principal", "", "Service principal application id")
dbutils.widgets.dropdown("dry_run", "true", ["true", "false"], "Dry run (small scale)")
SERVICE_PRINCIPAL = dbutils.widgets.get("service_principal")
DRY_RUN = dbutils.widgets.get("dry_run") == "true"
assert SERVICE_PRINCIPAL, "service_principal widget is required"

CATALOG = "abac_onetrust_scale"
MAIN_SCHEMA = "onetrust_sim"
MONITORING_SCHEMA = "monitoring"

# COMMAND ----------
# Step 1: catalog/schema creation
spark.sql(f"CREATE CATALOG IF NOT EXISTS {CATALOG}")
spark.sql(f"CREATE SCHEMA IF NOT EXISTS {CATALOG}.{MAIN_SCHEMA}")
spark.sql(f"CREATE SCHEMA IF NOT EXISTS {CATALOG}.{MONITORING_SCHEMA}")

# COMMAND ----------
# Step 2: main tables (34: 11 original + 23 new), scale_factor=5 for real, =1 for dry run
from onetrust_synth import config
from onetrust_synth.generate_main_tables import build_all_main_tables
from onetrust_synth.write import write_delta_table

scale_factor = 1.0 if DRY_RUN else 5.0
main_tables = build_all_main_tables(spark, scale_factor=scale_factor, table_row_counts=config.ALL_SCALE2_MAIN_TABLES)
# Rebind each entry to a fresh read of the just-written Delta table rather than keeping the
# in-memory generation lineage around. main_tables is reused below (extra_entity_pieces, the
# validation gate's entity_registry_check) -- without this rebind, those re-derive the full
# generation lineage from scratch instead of scanning the materialized table (costly at 1B-row
# scale). Reassigning an existing key's value while iterating dict.items() is safe (only
# add/remove during iteration raises); list(...) is used anyway for defensiveness.
for table_name, df in list(main_tables.items()):
    schema = MONITORING_SCHEMA if table_name in config.MONITORING_TABLES else MAIN_SCHEMA
    write_delta_table(df, CATALOG, schema, table_name)
    main_tables[table_name] = spark.table(f"{CATALOG}.{schema}.{table_name}")
    print(f"Wrote {CATALOG}.{schema}.{table_name}: {main_tables[table_name].count()} rows")

# COMMAND ----------
# Step 3: ABAC tables — Phase-1-sized targets for a dry run, SCALE2 targets (up to 1B) for real
from onetrust_synth.generate_abac_tables import build_all_abac_tables
from onetrust_synth.registries import build_entitylink_v3_entity_piece

row_targets = config.ABAC_TABLE_ROW_TARGETS if DRY_RUN else config.SCALE2_ABAC_TABLE_ROW_TARGETS
registry_sizes = None if DRY_RUN else {
    "users": config.SCALE2_SUBJECT_REGISTRY_USER_COUNT,
    "groups": config.SCALE2_SUBJECT_REGISTRY_GROUP_COUNT,
    "standalone_per_type": config.SCALE2_STANDALONE_ENTITIES_PER_TYPE,
}
extra_entity_pieces = [build_entitylink_v3_entity_piece(main_tables)]

abac_tables = build_all_abac_tables(
    spark, main_tables, row_targets=row_targets,
    extra_entity_pieces=extra_entity_pieces, registry_sizes=registry_sizes,
)
# Same rebind pattern as Step 2's main_tables loop: built_counts and the referential-integrity
# check below both reuse abac_tables, so read the materialized Delta table back rather than
# re-executing the generation lineage a 2nd/3rd time.
for table_name, df in list(abac_tables.items()):
    write_table_name = "OrgHierarchyBase" if table_name == "ABAC_OrgHierarchy" else table_name
    partition_by = ["objectType"] if table_name in config.ABAC_PARTITIONED_TABLES else None
    write_delta_table(df, CATALOG, MAIN_SCHEMA, write_table_name, partition_by=partition_by)
    abac_tables[table_name] = spark.table(f"{CATALOG}.{MAIN_SCHEMA}.{write_table_name}")
    print(f"Wrote {CATALOG}.{MAIN_SCHEMA}.{write_table_name}: {abac_tables[table_name].count()} rows")

# abac_tables.build_org_hierarchy_view_sql() is NOT used here — it hardcodes
# config.CATALOG/config.MAIN_SCHEMA (the phase1 "abac_onetrust" catalog), so it cannot
# target this notebook's own CATALOG/MAIN_SCHEMA. Build the equivalent view SQL inline,
# parameterized by this notebook's variables, instead.
spark.sql(
    f"CREATE OR REPLACE VIEW {CATALOG}.{MAIN_SCHEMA}.ABAC_OrgHierarchy AS "
    f"SELECT * FROM {CATALOG}.{MAIN_SCHEMA}.OrgHierarchyBase WHERE isDeleted IS NOT TRUE"
)

# COMMAND ----------
# Validation gate — must pass before Step 4 (tags/policies) runs, same order as phase1_run_all.py
from onetrust_synth.validate import validate_row_counts, validate_referential_integrity
from onetrust_synth.registries import build_subject_registry, build_entity_registry

# abac_tables' values are now the rebound (materialized-table) DataFrames from the Step 3 write
# loop above, so this reads back from storage rather than recomputing the build lineage.
built_counts = {k: v.count() for k, v in abac_tables.items()}
row_failures = validate_row_counts(built_counts, row_targets, tolerance=0.05)
assert not row_failures, f"Row count validation failed: {row_failures}"
print("Row-count validation passed.")

# build_all_abac_tables doesn't return the registries it built internally (Task 4's return
# contract stays {table_name: DataFrame} only, so Task 4's tests are unaffected) — rebuild them
# here for the FK-integrity check. Deterministic generation (generator.py's hash-based approach)
# means this reproduces byte-identical registries, not a second random draw. standalone_per_type
# must match what build_all_abac_tables actually used above (via registry_sizes), or this
# recomputes a DIFFERENT entity registry than the one ESA was built against and the integrity
# check below would spuriously fail. main_tables is also already rebound (Step 2), so this reads
# back the materialized main tables instead of re-deriving their generation lineage.
entity_registry_check = build_entity_registry(
    spark, main_tables, extra_pieces=extra_entity_pieces,
    standalone_per_type=(registry_sizes or {}).get("standalone_per_type"),
)
subject_registry_check = build_subject_registry(
    spark,
    user_count=(registry_sizes or {}).get("users"),
    group_count=(registry_sizes or {}).get("groups"),
)
integrity = validate_referential_integrity(
    abac_tables["ABAC_EntitySubjectAssignment"], entity_registry_check,
    subject_registry_check, abac_tables["ABAC_Assignment"],
)
assert integrity["entity_match_rate"] > 0.99, f"Entity FK integrity too low: {integrity}"
assert integrity["subject_match_rate"] > 0.99, f"Subject FK integrity too low: {integrity}"
assert integrity["assignment_match_rate"] > 0.99, f"Assignment FK integrity too low: {integrity}"
print(f"Referential integrity validation passed: {integrity}")

# COMMAND ----------
# Step 4: tags (8 tables, including the 4 new ones)
from onetrust_synth.governance_sql import (
    build_udf_sql, build_tags_sql, build_policies_sql, build_seed_principals_sql, build_oauth_wiring_sql,
)

for stmt in build_udf_sql(CATALOG, MAIN_SCHEMA):
    spark.sql(stmt)
print("UDFs created.")

for stmt in build_tags_sql(CATALOG, MAIN_SCHEMA):
    spark.sql(stmt)
print("Tags applied.")

# COMMAND ----------
# Step 5: policies (8 tables)
for stmt in build_policies_sql(CATALOG, MAIN_SCHEMA, service_principal=SERVICE_PRINCIPAL):
    spark.sql(stmt)
print("Policies created.")
display(spark.sql(f"SHOW POLICIES ON SCHEMA {CATALOG}.{MAIN_SCHEMA}"))

# COMMAND ----------
# Step 6: seeded test principals (8 tables: 4 replayed + 4 new)
for stmt in build_seed_principals_sql(CATALOG, MAIN_SCHEMA):
    spark.sql(stmt)
seed_count = spark.sql(
    f"SELECT count(*) AS n FROM {CATALOG}.{MAIN_SCHEMA}.ABAC_EntitySubjectAssignment "
    "WHERE tenantHash = 'phase2-test-seed'"
).collect()[0]["n"]
assert seed_count == 9, f"Expected 9 seeded ESA rows, got {seed_count}"
print(f"Seeded {seed_count} test-principal ESA rows.")

# COMMAND ----------
# Step 7: OAuth wiring (get_user_context/abac_row_filter_wrapper_oauth, 8 policies re-pointed to
# it, and grants) -- mirrors sql_onetrust/07_oauth_wiring.sql. Without this the 8 policies stay
# bound to abac_row_filter_wrapper (Step 5), which reads the hardcoded get_test_user_context()
# literal, so every real OAuth claim would be silently ignored and the SP would have no grants to
# even read the catalog.
for stmt in build_oauth_wiring_sql(CATALOG, MAIN_SCHEMA, SERVICE_PRINCIPAL):
    spark.sql(stmt)
print("OAuth wiring applied: get_user_context, abac_row_filter_wrapper_oauth, "
      "8 policies re-pointed to it, grants issued.")
display(spark.sql(f"SHOW POLICIES ON SCHEMA {CATALOG}.{MAIN_SCHEMA}"))

# COMMAND ----------
print(f"Phase 2 {'DRY RUN' if DRY_RUN else 'FULL SCALE'} complete: catalog={CATALOG}, "
      f"scale_factor={scale_factor}, ESA target={row_targets['ABAC_EntitySubjectAssignment']}")
