# Databricks notebook source
# =====================================================================
# Incremental deploy: attach real ABAC row-filter policies to the 2 biggest, previously
# ungoverned tables -- cmb_v_assessmentquestionresponse_v3 (9.49M rows) and
# cmb_v_assessmentstagechangetracker_v4 (3.55M rows).
#
# Why this exists: neither table had a CREATE POLICY row filter, so the GEN-ESA-* shortlist
# queries' claim/identity was inert against them -- any SP, with any claim, saw the exact same
# (unfiltered) data. The queries hand-simulated a filter in the SQL text itself instead of
# relying on Unity Catalog's real enforcement. This script closes that gap by extending
# onetrust_synth/governance_sql.py's _TAG_SPEC/_POLICY_SPEC (8 governed tables -> 10) and
# re-applying tags/policies/seeds/grants against the ALREADY-BUILT abac_onetrust_scale catalog.
#
# This does NOT rebuild the catalog, reload data, or touch the other 8 governed tables' data --
# it only:
#   1. Applies governed tags to the 2 new tables' id/org columns (ALTER TABLE ... SET TAGS,
#      idempotent).
#   2. Re-applies build_tags_sql/build_policies_sql/build_oauth_wiring_sql's FULL statement
#      lists (all 10 tables) -- safe to re-run, since CREATE POLICY is CREATE OR REPLACE, ALTER
#      TABLE...SET TAGS is idempotent, and GRANT is idempotent. The 8 existing tables' tags/
#      policies/grants are harmlessly re-applied unchanged.
#   3. Re-seeds ALL test principals (build_seed_principals_sql deletes-then-reinserts by design)
#      -- adds 3 new seeded ESA rows (900010, 900011, 900012) alongside the existing 9.
#
# Prerequisites:
#   - Same as phase2_scale_run.py: this repo checked out as a Databricks Repo, attached to a
#     cluster on the Unity Catalog workspace that already has abac_onetrust_scale built.
#   - ALTER TABLE...SET TAGS + CREATE POLICY rights (same governed tag keys phase2 already
#     registered -- no new tag keys needed, only 2 new tables using the same
#     abac_column_id/abac_column_org keys).
#   - get_user_context / abac_row_filter_wrapper_oauth must already exist in the catalog (they
#     were created once via databricks/run_oauth_functions_via_sp.sh during the original phase2
#     deploy -- this script does not touch them, since their CREATE FUNCTION body eagerly
#     analyzes current_oauth_custom_identity_claim() and errors on a cluster session with no
#     custom_claim-bearing token).
# =====================================================================

# COMMAND ----------
dbutils.widgets.text("service_principal", "", "Service principal application id")
SERVICE_PRINCIPAL = dbutils.widgets.get("service_principal")
assert SERVICE_PRINCIPAL, "service_principal widget is required"

CATALOG = "abac_onetrust_scale"
MAIN_SCHEMA = "onetrust_sim"

# COMMAND ----------
# Step 1: tags -- all 10 governed tables' id/type/org columns (8 existing re-applied no-op, 2 new
# tables freshly tagged: cmb_v_assessmentquestionresponse_v3.assessmentID/orgID,
# cmb_v_assessmentstagechangetracker_v4.assessmentID/orgID).
from onetrust_synth.governance_sql import (
    build_tags_sql, build_policies_sql, build_seed_principals_sql, build_oauth_wiring_sql,
)

for stmt in build_tags_sql(CATALOG, MAIN_SCHEMA):
    spark.sql(stmt)
print("Tags applied (10 tables).")

# COMMAND ----------
# Step 2: policies bound to the deterministic test wrapper first (build_policies_sql's default
# abac_row_filter_wrapper) -- matches phase2_scale_run.py's own Step 5, kept as an intermediate
# state before Step 4 re-points everything at the OAuth-live wrapper.
for stmt in build_policies_sql(CATALOG, MAIN_SCHEMA, service_principal=SERVICE_PRINCIPAL):
    spark.sql(stmt)
print("Policies created (10 tables, test-wrapper-bound).")

# COMMAND ----------
# Step 3: re-seed test principals (11-12 ESA rows total -- see phase2_scale_run.py's Step 6
# comment for the 11-vs-12 note on the crossjoin seed).
for stmt in build_seed_principals_sql(CATALOG, MAIN_SCHEMA):
    spark.sql(stmt)
seed_count = spark.sql(
    f"SELECT count(*) AS n FROM {CATALOG}.{MAIN_SCHEMA}.ABAC_EntitySubjectAssignment "
    "WHERE tenantHash = 'phase2-test-seed'"
).collect()[0]["n"]
assert seed_count in (11, 12), f"Expected 11 or 12 seeded ESA rows, got {seed_count}"
if seed_count == 11:
    print("WARNING: crossjoin seed 900012 found no shared assessmentID between "
          "cmb_v_assessmentquestionresponse_v3 and cmb_v_assessmentstagechangetracker_v4 -- "
          "the u.assessment.crossjoin.owner@example.com claim will see 0 rows on both tables.")
print(f"Seeded {seed_count} test-principal ESA rows.")

# COMMAND ----------
# Step 4: OAuth wiring -- re-point all 10 policies at abac_row_filter_wrapper_oauth (the live
# get_user_context()-driven wrapper) and (re-)issue grants, including SELECT on the 2 new tables
# to the service principal. Statements 0-1 (the get_user_context/abac_row_filter_wrapper_oauth
# CREATE FUNCTION bodies) are skipped -- see this file's docstring and
# phase2_scale_run.py's Step 7 comment for why.
oauth_wiring_stmts = build_oauth_wiring_sql(CATALOG, MAIN_SCHEMA, SERVICE_PRINCIPAL)
for stmt in oauth_wiring_stmts[2:]:
    spark.sql(stmt)
print("OAuth wiring re-applied: 10 policies re-pointed to abac_row_filter_wrapper_oauth, "
      "grants issued (including SELECT on the 2 new tables).")
display(spark.sql(f"SHOW POLICIES ON SCHEMA {CATALOG}.{MAIN_SCHEMA}"))

# COMMAND ----------
print(f"Deploy complete: catalog={CATALOG}, governed tables=10 "
      "(cmb_v_assessmentquestionresponse_v3 and cmb_v_assessmentstagechangetracker_v4 newly added).")
