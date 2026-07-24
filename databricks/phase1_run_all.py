# Databricks notebook source
# MAGIC %md
# MAGIC # OneTrust Synthetic Dataset — Phase 1 — Run All
# MAGIC
# MAGIC Runs the full Phase 1 completion checklist end to end, in one notebook, in the
# MAGIC correct order:
# MAGIC
# MAGIC 1. Create the `abac_onetrust` catalog/schemas
# MAGIC 2. Generate the 11 main tables
# MAGIC 3. Generate the 5 ABAC tables + the `ABAC_OrgHierarchy` view
# MAGIC 4. Tag the 4 governed tables + create the row-filter UDFs
# MAGIC 5. Create the row-filter policies (needs your service principal — set it in the
# MAGIC    widget below before running)
# MAGIC 6. Seed 3 test principals + run 8 ABAC functional test cases
# MAGIC 7. Run the 50 real compatible queries against the live, policy-active dataset
# MAGIC
# MAGIC **Source of truth:** this notebook inlines `sql_onetrust/01`–`06` and calls into the
# MAGIC `onetrust_synth` Python package — both already reviewed and locally tested
# MAGIC (75/75 pytest passing). See `docs/superpowers/plans/2026-07-23-onetrust-synthetic-dataset-phase1.md`
# MAGIC for the full design/task history. If you change the SQL or Python source files,
# MAGIC re-sync this notebook by hand — it is not auto-generated.
# MAGIC
# MAGIC ## Prerequisites
# MAGIC - This repo checked out as a **Databricks Repo** (Git folder) — the `onetrust/` sample
# MAGIC   data directory must be present alongside `onetrust_synth/` for the CSV reads to work.
# MAGIC - Attached to a cluster on a **Unity Catalog** workspace. Do **not** `%pip install
# MAGIC   pyspark` — use the cluster's built-in `spark` session (installing a different
# MAGIC   pyspark build can cause a driver/worker version mismatch).
# MAGIC - `CREATE CATALOG`/`CREATE SCHEMA` rights for step 1; `ALTER TABLE ... SET TAGS` +
# MAGIC   `CREATE POLICY` rights for steps 4–5.
# MAGIC - Governed tag keys `abac_column_id`, `abac_column_org`, `abac_column_type` already
# MAGIC   created once via **Settings → Catalog → Governed tags** (a one-time workspace setup
# MAGIC   step, not something this notebook can do).
# MAGIC - The real service principal's application id, to fill into the widget below before
# MAGIC   running Step 5.

# COMMAND ----------

# MAGIC %md
# MAGIC ## Setup — set your service principal before running Step 5

# COMMAND ----------

dbutils.widgets.text("service_principal", "", "Service Principal Application ID (for row-filter policies)")

# COMMAND ----------

# MAGIC %md
# MAGIC ## Step 1 — Create the catalog and schemas
# MAGIC
# MAGIC From `sql_onetrust/01_catalog_schema.sql`. Run as workspace/catalog admin.

# COMMAND ----------

# MAGIC %sql
# MAGIC CREATE CATALOG IF NOT EXISTS abac_onetrust;
# MAGIC CREATE SCHEMA IF NOT EXISTS abac_onetrust.onetrust_sim;
# MAGIC CREATE SCHEMA IF NOT EXISTS abac_onetrust.monitoring;

# COMMAND ----------

# MAGIC %sql
# MAGIC -- Expected: the result set includes onetrust_sim and monitoring.
# MAGIC SHOW SCHEMAS IN abac_onetrust;

# COMMAND ----------

# MAGIC %md
# MAGIC ## Step 2 — Generate the 11 main tables
# MAGIC
# MAGIC From `onetrust_synth/generate_main_tables.py`. Row counts match the real profiled
# MAGIC counts (`scale_factor=1.0`) — largest table is `cmb_v_assessment_v4` at ~1.59M rows,
# MAGIC so this step takes a few minutes.

# COMMAND ----------

from onetrust_synth import config
from onetrust_synth.generate_main_tables import build_all_main_tables
from onetrust_synth.write import write_delta_table

main_tables = build_all_main_tables(spark, scale_factor=config.SCALE_FACTOR_DEFAULT)
for table_name, df in main_tables.items():
    schema = config.MONITORING_SCHEMA if table_name in config.MONITORING_TABLES else config.MAIN_SCHEMA
    write_delta_table(df, config.CATALOG, schema, table_name)
    print(f"Wrote {config.CATALOG}.{schema}.{table_name}: {df.count()} rows")

# COMMAND ----------

# MAGIC %md
# MAGIC ## Step 3 — Generate the 5 ABAC tables + the `ABAC_OrgHierarchy` view
# MAGIC
# MAGIC From `onetrust_synth/generate_abac_tables.py`. `ABAC_EntitySubjectAssignment` targets
# MAGIC 100,000 rows — this is the largest write in Phase 1.
# MAGIC
# MAGIC Note: this rebuilds the 11 main tables in memory again (not by reading back what
# MAGIC Step 2 wrote) to build `entity_registry` for FK consistency. Generation is fully
# MAGIC deterministic (hash-based, no `F.rand()`), so the rebuilt tables are byte-identical
# MAGIC to what Step 2 wrote — this is intentional, not a bug, and is how referential
# MAGIC integrity between the main tables and the ABAC tables is guaranteed.

# COMMAND ----------

from onetrust_synth.generate_abac_tables import build_all_abac_tables
from onetrust_synth.abac_tables import build_org_hierarchy_view_sql
from onetrust_synth.validate import validate_row_counts, validate_referential_integrity

abac_tables = build_all_abac_tables(spark, main_tables)
for table_name, df in abac_tables.items():
    write_table_name = "OrgHierarchyBase" if table_name == "ABAC_OrgHierarchy" else table_name
    partition_by = ["objectType"] if table_name in config.ABAC_PARTITIONED_TABLES else None
    write_delta_table(df, config.CATALOG, config.MAIN_SCHEMA, write_table_name, partition_by=partition_by)
    print(f"Wrote {config.CATALOG}.{config.MAIN_SCHEMA}.{write_table_name}: {df.count()} rows")

spark.sql(build_org_hierarchy_view_sql())
print(f"Created view {config.CATALOG}.{config.MAIN_SCHEMA}.ABAC_OrgHierarchy over OrgHierarchyBase")

# COMMAND ----------

# MAGIC %md
# MAGIC ### Validation gate
# MAGIC
# MAGIC `generate_abac_tables.main()` doesn't call these automatically (they're exercised by
# MAGIC the local test suite instead) — running them here closes that gap for this live run.
# MAGIC Row counts should be within 5% of target (`UserGroupMembers` can land ~0.5% under due
# MAGIC to deduping); all 3 referential-integrity match rates should be 1.0.

# COMMAND ----------

row_count_failures = validate_row_counts(
    {name: df.count() for name, df in abac_tables.items()}, config.ABAC_TABLE_ROW_TARGETS,
)
if row_count_failures:
    raise AssertionError(f"ABAC row-count validation failed: {row_count_failures}")
print("Row counts OK:", {name: df.count() for name, df in abac_tables.items()})

from onetrust_synth.registries import build_org_registry, build_subject_registry, build_entity_registry

entity_registry = build_entity_registry(spark, main_tables)
subject_registry = build_subject_registry(spark)
ri_report = validate_referential_integrity(
    abac_tables["ABAC_EntitySubjectAssignment"], entity_registry, subject_registry, abac_tables["ABAC_Assignment"],
)
print("Referential integrity:", ri_report)
assert all(v == 1.0 for v in ri_report.values()), f"Referential integrity check failed: {ri_report}"

# COMMAND ----------

# MAGIC %md
# MAGIC ## Step 4 — Tag the governed tables + create the row-filter UDFs
# MAGIC
# MAGIC From `sql_onetrust/02_tags.sql` and `sql_onetrust/03_row_filter_udfs.sql`. If `SET
# MAGIC TAGS` errors with an unknown tag key, create `abac_column_id`/`abac_column_org`/
# MAGIC `abac_column_type` via **Settings → Catalog → Governed tags** first, then re-run this
# MAGIC cell.

# COMMAND ----------

# MAGIC %sql
# MAGIC -- cmb_assessment: single type 'ASSESSMENT' (no_type policy shape) -- id only
# MAGIC ALTER TABLE abac_onetrust.onetrust_sim.cmb_assessment ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
# MAGIC
# MAGIC -- cmb_controlimplementation: single type 'CONTROL' (no_type policy shape) -- id only
# MAGIC ALTER TABLE abac_onetrust.onetrust_sim.cmb_controlimplementation ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
# MAGIC
# MAGIC -- cmb_template: single type 'TEMPLATE' (no_type policy shape) -- id only
# MAGIC ALTER TABLE abac_onetrust.onetrust_sim.cmb_template ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
# MAGIC
# MAGIC -- cmb_v_inventoryaggregatedrisksummary: per-row type via inventoryType (default/tagged-type
# MAGIC -- policy shape) -- id, type, AND org
# MAGIC ALTER TABLE abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary ALTER COLUMN entityID SET TAGS ('abac_column_id' = 'true');
# MAGIC ALTER TABLE abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary ALTER COLUMN inventoryType SET TAGS ('abac_column_type' = 'true');
# MAGIC ALTER TABLE abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary ALTER COLUMN orgID SET TAGS ('abac_column_org' = 'true');

# COMMAND ----------

# MAGIC %sql
# MAGIC CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context()
# MAGIC RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
# MAGIC COMMENT 'Deterministic ABAC context for Phase 1 test-case validation'
# MAGIC RETURN named_struct(
# MAGIC   'tenant',      1,
# MAGIC   'user',        'u.assessment.owner@example.com',
# MAGIC   'org',         '100',
# MAGIC   'mode',        'ABAC',
# MAGIC   'root',        'ASSESSMENT',
# MAGIC   'permissions', array('TEMPLATE')
# MAGIC );

# COMMAND ----------

# MAGIC %sql
# MAGIC CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.entity_type_to_object_type(entity_type STRING)
# MAGIC RETURNS STRING
# MAGIC COMMENT 'Normalizes a raw table/column type value to the canonical ABAC object type. NOT a plain upper() -- "Processing Activities" hyphenates to "PROCESSING-ACTIVITIES".'
# MAGIC RETURN CASE
# MAGIC   WHEN upper(entity_type) = 'PROCESSING ACTIVITIES' THEN 'PROCESSING-ACTIVITIES'
# MAGIC   ELSE upper(entity_type)
# MAGIC END;

# COMMAND ----------

# MAGIC %sql
# MAGIC CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.abac_row_filter(
# MAGIC   entity_id   STRING,
# MAGIC   object_type STRING,
# MAGIC   org_id      STRING,
# MAGIC   ctx STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
# MAGIC )
# MAGIC RETURNS BOOLEAN
# MAGIC RETURN (
# MAGIC   ctx.mode = 'DISABLE'
# MAGIC   OR (
# MAGIC     ctx.root <> object_type
# MAGIC     AND array_contains(ctx.permissions, object_type)
# MAGIC   )
# MAGIC   OR (
# MAGIC     ctx.root = object_type
# MAGIC     AND (
# MAGIC       (
# MAGIC         ctx.mode = 'RBAC_ABAC'
# MAGIC         AND org_id IN (
# MAGIC           SELECT orgId FROM abac_onetrust.onetrust_sim.ABAC_OrgHierarchy
# MAGIC           WHERE parentOrgId = ctx.org
# MAGIC         )
# MAGIC       )
# MAGIC       OR EXISTS (
# MAGIC         SELECT 1
# MAGIC         FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment esa
# MAGIC         JOIN abac_onetrust.onetrust_sim.ABAC_Assignment a
# MAGIC           ON esa.assignmentId = a.id
# MAGIC           AND a.isActive
# MAGIC           AND a.isDeleted = false
# MAGIC         LEFT JOIN abac_onetrust.onetrust_sim.UserGroupMembers ugm
# MAGIC           ON esa.subjectType = 'USER_GROUP'
# MAGIC           AND esa.subjectId = ugm.groupId
# MAGIC           AND ugm.memberId = ctx.user
# MAGIC           AND ugm.isDeleted = false
# MAGIC         WHERE esa.isDeleted = false
# MAGIC           AND esa.entityId = entity_id
# MAGIC           AND esa.objectType = object_type
# MAGIC           AND (
# MAGIC             ugm.memberId IS NOT NULL
# MAGIC             OR (esa.subjectType = 'USER_ID' AND esa.subjectId = ctx.user)
# MAGIC           )
# MAGIC       )
# MAGIC     )
# MAGIC   )
# MAGIC );

# COMMAND ----------

# MAGIC %sql
# MAGIC CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.abac_row_filter_wrapper(
# MAGIC   entity_id STRING, object_type STRING, org_id STRING
# MAGIC )
# MAGIC RETURNS BOOLEAN
# MAGIC RETURN abac_onetrust.onetrust_sim.abac_row_filter(
# MAGIC   entity_id, abac_onetrust.onetrust_sim.entity_type_to_object_type(object_type), org_id,
# MAGIC   abac_onetrust.onetrust_sim.get_test_user_context()
# MAGIC );

# COMMAND ----------

# MAGIC %md
# MAGIC ## Step 5 — Create the row-filter policies
# MAGIC
# MAGIC From `sql_onetrust/04_policies.sql`, with `<SERVICE_PRINCIPAL>` substituted from the
# MAGIC `service_principal` widget set at the top of this notebook.

# COMMAND ----------

service_principal = dbutils.widgets.get("service_principal").strip()
if not service_principal:
    raise ValueError(
        "Set the 'service_principal' widget (top of this notebook) to your real "
        "service principal application id before running this cell."
    )

policies = [
    (
        "onetrust_sim_cmb_assessment_abac_policy",
        "abac_onetrust.onetrust_sim.cmb_assessment",
        "MATCH COLUMNS has_tag('abac_column_id') as id",
        "id, 'ASSESSMENT', '100'",
    ),
    (
        "onetrust_sim_cmb_controlimplementation_abac_policy",
        "abac_onetrust.onetrust_sim.cmb_controlimplementation",
        "MATCH COLUMNS has_tag('abac_column_id') as id",
        "id, 'CONTROL', '100'",
    ),
    (
        "onetrust_sim_cmb_template_abac_policy",
        "abac_onetrust.onetrust_sim.cmb_template",
        "MATCH COLUMNS has_tag('abac_column_id') as id",
        "id, 'TEMPLATE', '100'",
    ),
    (
        "onetrust_sim_cmb_v_inventoryaggregatedrisksummary_abac_policy",
        "abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary",
        "MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_type') as type, has_tag('abac_column_org') as org",
        "id, type, org",
    ),
]

for policy_name, table, match_columns, using_columns in policies:
    stmt = f"""
    CREATE OR REPLACE POLICY {policy_name}
    ON TABLE {table}
    ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper
    TO `{service_principal}`
    FOR TABLES
    {match_columns}
    USING COLUMNS ({using_columns})
    """
    spark.sql(stmt)
    print(f"Created policy {policy_name} on {table}")

# COMMAND ----------

# MAGIC %sql
# MAGIC -- Expected: 4 policies listed.
# MAGIC SHOW POLICIES ON SCHEMA abac_onetrust.onetrust_sim;

# COMMAND ----------

# MAGIC %md
# MAGIC ## Step 6 — Seed 3 test principals + run 8 ABAC functional test cases
# MAGIC
# MAGIC From `sql_onetrust/05_seed_test_principals.sql` and `sql_onetrust/06_test_cases.sql`.
# MAGIC These **must** run in the same session (this notebook) — the test cases reference
# MAGIC temporary views the seed step creates. `assert_true` raises and halts the cell if a
# MAGIC check fails, so "no error" on this section means all 8 passed.

# COMMAND ----------

# MAGIC %sql
# MAGIC DELETE FROM abac_onetrust.onetrust_sim.ABAC_Assignment WHERE staticIdentifier = 'phase1-test-seed';
# MAGIC DELETE FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-test-seed';
# MAGIC DELETE FROM abac_onetrust.onetrust_sim.UserGroupMembers WHERE tenantHash = 'phase1-test-seed';
# MAGIC
# MAGIC CREATE OR REPLACE TEMPORARY VIEW seed_assessment_entity AS
# MAGIC   SELECT id AS entity_id FROM abac_onetrust.onetrust_sim.cmb_assessment ORDER BY id LIMIT 1;
# MAGIC CREATE OR REPLACE TEMPORARY VIEW seed_control_entity AS
# MAGIC   SELECT id AS entity_id FROM abac_onetrust.onetrust_sim.cmb_controlimplementation ORDER BY id LIMIT 1;
# MAGIC
# MAGIC INSERT INTO abac_onetrust.onetrust_sim.ABAC_Assignment
# MAGIC   (id, guid, staticIdentifier, name, objectType, sourceType, isActive, createdBy, createDT, updatedBy, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
# MAGIC VALUES
# MAGIC   (900001, uuid(), 'phase1-test-seed', 'Owner', 'ASSESSMENT', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false),
# MAGIC   (900002, uuid(), 'phase1-test-seed', 'Owner', 'ASSESSMENT', 'SYSTEM', false, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false),
# MAGIC   (900003, uuid(), 'phase1-test-seed', 'Owner', 'CONTROL', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false);
# MAGIC
# MAGIC INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
# MAGIC   (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
# MAGIC SELECT 900001, NULL, entity_id, NULL, 'u.assessment.owner@example.com', 'USER_ID', 'ASSESSMENT', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
# MAGIC FROM seed_assessment_entity
# MAGIC UNION ALL
# MAGIC SELECT 900002, NULL, entity_id, NULL, 'u.inactive.grant@example.com', 'USER_ID', 'ASSESSMENT', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
# MAGIC FROM seed_assessment_entity
# MAGIC UNION ALL
# MAGIC SELECT 900003, NULL, entity_id, NULL, 'test_group_1', 'USER_GROUP', 'CONTROL', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
# MAGIC FROM seed_control_entity;
# MAGIC
# MAGIC INSERT INTO abac_onetrust.onetrust_sim.UserGroupMembers (memberId, groupId, eventTime, recModifiedTime, isDeleted, tenantHash)
# MAGIC VALUES ('u.group.member@example.com', 'test_group_1', current_timestamp(), current_timestamp(), false, 'phase1-test-seed');

# COMMAND ----------

# MAGIC %sql
# MAGIC -- Expected: 3
# MAGIC SELECT count(*) AS seeded_esa_rows FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-test-seed';

# COMMAND ----------

# MAGIC %sql
# MAGIC -- T1: root type, explicit assignment -- the seeded assessment IS visible.
# MAGIC SELECT assert_true(count(*) = 1, 'T1 FAILED: seeded assessment should be visible')
# MAGIC FROM abac_onetrust.onetrust_sim.cmb_assessment
# MAGIC WHERE id = (SELECT entity_id FROM seed_assessment_entity)
# MAGIC   AND abac_onetrust.onetrust_sim.abac_row_filter_wrapper(id, 'ASSESSMENT', '100');

# COMMAND ----------

# MAGIC %sql
# MAGIC -- T2: root type, no assignment at all -- a DIFFERENT assessment is NOT visible.
# MAGIC SELECT assert_true(count(*) = 0, 'T2 FAILED: unassigned assessment should not be visible')
# MAGIC FROM abac_onetrust.onetrust_sim.cmb_assessment
# MAGIC WHERE id != (SELECT entity_id FROM seed_assessment_entity)
# MAGIC   AND id NOT IN (SELECT entityId FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE subjectId = 'u.assessment.owner@example.com')
# MAGIC   AND abac_onetrust.onetrust_sim.abac_row_filter_wrapper(id, 'ASSESSMENT', '100');

# COMMAND ----------

# MAGIC %sql
# MAGIC -- T3: non-root type, IN permissions array -- ALL cmb_template rows visible.
# MAGIC SELECT
# MAGIC   assert_true(
# MAGIC     (SELECT count(*) FROM abac_onetrust.onetrust_sim.cmb_template) =
# MAGIC     (SELECT count(*) FROM abac_onetrust.onetrust_sim.cmb_template WHERE abac_onetrust.onetrust_sim.abac_row_filter_wrapper(id, 'TEMPLATE', '100')),
# MAGIC     'T3 FAILED: all templates should be visible (non-root, in permissions)'
# MAGIC   );

# COMMAND ----------

# MAGIC %sql
# MAGIC -- T4: non-root type, NOT in permissions array -- ZERO controlimplementation rows visible.
# MAGIC SELECT assert_true(count(*) = 0, 'T4 FAILED: controls should not be visible (non-root, not in permissions)')
# MAGIC FROM abac_onetrust.onetrust_sim.cmb_controlimplementation
# MAGIC WHERE abac_onetrust.onetrust_sim.abac_row_filter_wrapper(id, 'CONTROL', '100');

# COMMAND ----------

# MAGIC %sql
# MAGIC -- T5: group membership -- a member of test_group_1 sees the group-assigned control.
# MAGIC CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context_group_member()
# MAGIC RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
# MAGIC RETURN named_struct('tenant', 1, 'user', 'u.group.member@example.com', 'org', '100', 'mode', 'ABAC', 'root', 'CONTROL', 'permissions', array());
# MAGIC
# MAGIC SELECT assert_true(count(*) = 1, 'T5 FAILED: group member should see the group-assigned control')
# MAGIC FROM abac_onetrust.onetrust_sim.cmb_controlimplementation
# MAGIC WHERE id = (SELECT entity_id FROM seed_control_entity)
# MAGIC   AND abac_onetrust.onetrust_sim.abac_row_filter(
# MAGIC         id, 'CONTROL', '100', abac_onetrust.onetrust_sim.get_test_user_context_group_member());

# COMMAND ----------

# MAGIC %sql
# MAGIC -- T6: isActive=false assignment must NOT grant visibility.
# MAGIC CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context_inactive()
# MAGIC RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
# MAGIC RETURN named_struct('tenant', 1, 'user', 'u.inactive.grant@example.com', 'org', '100', 'mode', 'ABAC', 'root', 'ASSESSMENT', 'permissions', array());
# MAGIC
# MAGIC SELECT assert_true(count(*) = 0, 'T6 FAILED: an isActive=false assignment must not grant visibility')
# MAGIC FROM abac_onetrust.onetrust_sim.cmb_assessment
# MAGIC WHERE id = (SELECT entity_id FROM seed_assessment_entity)
# MAGIC   AND abac_onetrust.onetrust_sim.abac_row_filter(
# MAGIC         id, 'ASSESSMENT', '100', abac_onetrust.onetrust_sim.get_test_user_context_inactive());

# COMMAND ----------

# MAGIC %sql
# MAGIC -- T7: DISABLE mode -- everything visible regardless of assignments.
# MAGIC CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context_disabled()
# MAGIC RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
# MAGIC RETURN named_struct('tenant', 1, 'user', 'u.disabled.mode@example.com', 'org', '100', 'mode', 'DISABLE', 'root', 'ASSESSMENT', 'permissions', array());
# MAGIC
# MAGIC SELECT
# MAGIC   assert_true(
# MAGIC     (SELECT count(*) FROM abac_onetrust.onetrust_sim.cmb_assessment) =
# MAGIC     (SELECT count(*) FROM abac_onetrust.onetrust_sim.cmb_assessment
# MAGIC      WHERE abac_onetrust.onetrust_sim.abac_row_filter(id, 'ASSESSMENT', '100', abac_onetrust.onetrust_sim.get_test_user_context_disabled())),
# MAGIC     'T7 FAILED: DISABLE mode should show every row'
# MAGIC   );

# COMMAND ----------

# MAGIC %sql
# MAGIC -- T8: RBAC_ABAC over the real orgHierarchy ancestor closure (self-ancestor case --
# MAGIC -- see design doc / plan for why this is a documented Phase-1 limitation, not a bug).
# MAGIC CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context_rbac()
# MAGIC RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
# MAGIC RETURN named_struct(
# MAGIC   'tenant', 1, 'user', 'u.rbac.viewer@example.com', 'org',
# MAGIC   (SELECT orgID FROM abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary LIMIT 1),
# MAGIC   'mode', 'RBAC_ABAC', 'root', 'ASSETS', 'permissions', array()
# MAGIC );
# MAGIC
# MAGIC SELECT assert_true(count(*) >= 1, 'T8 FAILED: RBAC_ABAC org-subtree row should be visible for at least the seeded org itself')
# MAGIC FROM abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary
# MAGIC WHERE upper(inventoryType) = 'ASSETS'
# MAGIC   AND abac_onetrust.onetrust_sim.abac_row_filter(
# MAGIC         entityID, 'ASSETS', orgID, abac_onetrust.onetrust_sim.get_test_user_context_rbac());

# COMMAND ----------

# MAGIC %md
# MAGIC If every cell above ran with no error, all 8 test cases passed (`assert_true` raises
# MAGIC and halts on a failed check — a silent pass means it actually passed).

# COMMAND ----------

# MAGIC %md
# MAGIC ## Step 7 — Run the 50 real compatible queries
# MAGIC
# MAGIC From `onetrust_synth/run_compatible_queries.py`, against the now-live, policy-active
# MAGIC dataset.

# COMMAND ----------

from onetrust_synth.run_compatible_queries import run_all

results = run_all(spark)
print(f"Passed: {len(results['passed'])}")
print(f"Failed: {len(results['failed'])}")
for alias, err in results["failed"]:
    print(f"  FAIL {alias}: {err}")

assert len(results["failed"]) == 0, f"{len(results['failed'])} of 50 compatible queries failed -- see output above"

# COMMAND ----------

# MAGIC %md
# MAGIC ## Done
# MAGIC
# MAGIC If every step above completed without error, Phase 1 is fully live in
# MAGIC `abac_onetrust`: 11 main tables + 5 ABAC tables, tagged and policy-governed on the 4
# MAGIC Phase-1 tables, validated by 8 ABAC test cases and 50 real production queries.
# MAGIC
# MAGIC Next: Phase 2 planning (full-scale ABAC generation up to ~1B rows + the actual
# MAGIC performance benchmark) — a separate plan, not covered by this notebook.
