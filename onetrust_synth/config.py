"""
Shared constants for the Phase 1 OneTrust synthetic dataset pipeline.
Source of truth: docs/superpowers/specs/2026-07-23-onetrust-synthetic-dataset-design.md
"""
import os

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROFILE_CSV_PATH = os.path.join(REPO_ROOT, "onetrust", "onetrust_sample_data", "onetrust_table_profile_results.csv")
ABAC_PROFILE_CSV_PATH = os.path.join(REPO_ROOT, "onetrust", "onetrust_sample_data", "onetrust_abac_table_profile_results.csv")
SAMPLE_DATA_DIR = os.path.join(REPO_ROOT, "onetrust", "onetrust_sample_data")
ANNOTATED_QUERIES_CSV = os.path.join(REPO_ROOT, "onetrust", "onetrust_sanity_run_annotated.csv")

CATALOG = "abac_onetrust"
MAIN_SCHEMA = "onetrust_sim"
MONITORING_SCHEMA = "monitoring"
MONITORING_TABLES = {"entitygroupconfig"}

# Real profiled row_count at scale_factor=1, per design doc section 3.
MAIN_TABLES = {
    "cmb_assessment": 4984,
    "cmb_controlimplementation": 2573,
    "cmb_inventory": 8750,
    "cmb_riskrelatedobjects": 1100,
    "cmb_template": 5235,
    "cmb_v_assessment_v4": 1591030,
    "cmb_v_inventoryaggregatedrisksummary": 14,
    "entitylink_v3": 1007335,
    "orghierarchy": 183,
    "reportingmoduletoentityreferencemapping_v": 21,
    "entitygroupconfig": 0,
}

# Phase-1 (small) row targets for the 5 ABAC tables, per design doc section 4.
ABAC_TABLE_ROW_TARGETS = {
    "ABAC_Assignment": 1000,
    "ABAC_AssignmentPermission": 10000,
    "ABAC_EntitySubjectAssignment": 100000,
    "UserGroupMembers": 5000,
    "OrgHierarchy": 183,
}

ABAC_PARTITIONED_TABLES = {"ABAC_Assignment", "ABAC_EntitySubjectAssignment"}

# table -> (id_column, static_object_type). static_object_type is None when the
# real object type varies per row (read from a column instead) — see Task 9.
ENTITY_SOURCE_TABLES = {
    "cmb_assessment": ("id", "ASSESSMENT"),
    "cmb_v_assessment_v4": ("id", "ASSESSMENT"),
    "cmb_controlimplementation": ("id", "CONTROL"),
    "cmb_riskrelatedobjects": ("riskId", "RISK"),
    "cmb_template": ("id", "TEMPLATE"),
    "cmb_inventory": ("id", None),
    "cmb_v_inventoryaggregatedrisksummary": ("entityID", None),
}

# Entity types with no corresponding table in our 11 (from the real 20-value
# entityTypeReference list minus the 4 covered above) get standalone synthetic
# entity IDs — see Task 9.
STANDALONE_ENTITIES_PER_TYPE = 100

# Real inventoryType values, confirmed from cmb_v_inventoryaggregatedrisksummary's
# sample data (cmb_inventory's own sample file has a corrupted/mismatched header —
# see sample_csv.py's _KNOWN_BAD_SAMPLE_FILES — so cmb_inventory reuses this
# same-domain vocabulary rather than trusting its own sample). Note the mapping is
# NOT a plain .upper(): "Processing Activities" -> "PROCESSING-ACTIVITIES" (hyphenated)
# in the real entityTypeReference vocabulary, confirmed from the reference table.
INVENTORY_TYPE_TO_OBJECT_TYPE = {
    "Assets": "ASSETS",
    "Vendors": "VENDORS",
    "Processing Activities": "PROCESSING-ACTIVITIES",
}

SUBJECT_REGISTRY_USER_COUNT = 2000
SUBJECT_REGISTRY_GROUP_COUNT = 300

SCALE_FACTOR_DEFAULT = 1.0


def scaled_row_count(table: str, scale_factor: float) -> int:
    return round(MAIN_TABLES[table] * scale_factor)
