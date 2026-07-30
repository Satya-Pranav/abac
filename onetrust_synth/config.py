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
MONITORING_TABLES = {"entitygroupconfig", "dbxtenantschemaversion"}

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

REMAINING_PROFILE_CSV_PATH = os.path.join(REPO_ROOT, "onetrust", "onetrust_remaining_table_profile_results2.csv")
REMAINING_SAMPLE_DATA_DIR = os.path.join(REPO_ROOT, "onetrust", "onetrust_table_samples_remaining")

# 23 of the 40 tables profiled in onetrust_remaining_table_profile_results2.csv: real
# row_count > 0 AND a matching sample CSV exists in REMAINING_SAMPLE_DATA_DIR. 14 tables
# are empty in the real source (excluded — nothing to model) and 3 have real data but no
# sample file (entityattributevalue_v3, cmb_v_riskattributevalue_v3,
# cmb_v_inventorylinkattributemap — excluded for this pass, see design doc section 3).
REMAINING_MAIN_TABLES = {
    "cmb_v_assessmentapprover": 1916,
    "cmb_v_assessmentinventory_v4": 611065,
    "cmb_v_assessmentquestion": 15791,
    "cmb_v_assessmentquestionresponse_v3": 9493225,
    "cmb_v_assessmentrelatedentities": 286,
    "cmb_v_assessmentrespondent": 2822,
    "cmb_v_assessmentstagechangetracker_v4": 3552745,
    "cmb_v_assessmenttag": 18,
    "cmb_v_controlimplementation_v4": 2975,
    "cmb_v_controlimplementationentitylink": 6,
    "cmb_v_inventory_v4": 22610,
    "cmb_v_inventorylastassessment_v3": 580720,
    "cmb_v_inventorylinkv2": 3108,
    "cmb_v_inventorypersonaldataassociation": 38,
    "cmb_v_inventorypersonaldataassociationclassification": 3,
    "cmb_v_risk_v4": 4760,
    "cmb_v_riskapprover": 6,
    "cmb_v_riskcategory": 5,
    "cmb_v_riskowner": 6,
    "entity_v3": 4153100,
    "entityworkflowstagechangetracker_v3": 180880,
    "reportingmoduletorelatedentitiesmapping_v": 19,
    "dbxtenantschemaversion": 1070,
}

# Merged view used only by the scale-2 pipeline — Phase 1 code never reads this constant.
ALL_SCALE2_MAIN_TABLES = {**MAIN_TABLES, **REMAINING_MAIN_TABLES}

# Phase-1 (small) row targets for the 5 ABAC tables, per design doc section 4.
ABAC_TABLE_ROW_TARGETS = {
    "ABAC_Assignment": 1000,
    "ABAC_AssignmentPermission": 10000,
    "ABAC_EntitySubjectAssignment": 100000,
    "UserGroupMembers": 5000,
    "ABAC_OrgHierarchy": 183,
}

# Scale-2 targets. ABAC_EntitySubjectAssignment hits the README's documented ~1B/tenant
# figure directly; the rest scale by x100 (~sqrt(10,000), the ESA growth ratio) rather
# than 1:1 with ESA — they're dimension-like (grant definitions), not fact-like (grant
# records). See design doc section 4.
SCALE2_ABAC_TABLE_ROW_TARGETS = {
    "ABAC_Assignment": 100_000,
    "ABAC_AssignmentPermission": 1_000_000,
    "ABAC_EntitySubjectAssignment": 1_000_000_000,
    "UserGroupMembers": 500_000,
    "ABAC_OrgHierarchy": 183,  # verbatim, unchanged — same reasoning as orghierarchy/cmb_v_inventoryaggregatedrisksummary
}

SCALE2_SUBJECT_REGISTRY_USER_COUNT = 200_000
SCALE2_SUBJECT_REGISTRY_GROUP_COUNT = 30_000
SCALE2_STANDALONE_ENTITIES_PER_TYPE = 10_000

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

# Real inventoryType values, confirmed from both cmb_inventory's and
# cmb_v_inventoryaggregatedrisksummary's sample data (sample_csv.py recovers
# both correctly via positional reconstruction against the real profiled
# column order — see sample_csv.py's module docstring). Note the mapping is
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


def scaled_row_count(table: str, scale_factor: float, table_row_counts: dict | None = None) -> int:
    source = table_row_counts if table_row_counts is not None else MAIN_TABLES
    return round(source[table] * scale_factor)
