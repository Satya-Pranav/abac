# OneTrust Synthetic Dataset — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate a small, referentially-consistent synthetic OneTrust dataset (11 main tables + 5 legacy ABAC metadata tables) in a new `abac_onetrust` Unity Catalog catalog, wire real row-filter policies/tags on top of it, and prove the mechanism works by running the 50 already-extracted compatible queries and 8 basic ABAC functional test cases against it — before committing to the full 600M–1B row Phase 2 generation.

**Architecture:** Pure PySpark (no `dbldatagen` dependency yet — deferred to Phase 2 per the design doc's own risk framing, since Phase 1's largest table is ~100K rows, comfortably in range of plain broadcast-join generation). Column specs are read programmatically from the real profile CSVs at runtime rather than hand-transcribed. Four small "registry" DataFrames (entity/org/subject/assignment) are built first and everything else joins against them via broadcast joins, so IDs are consistent across every table. SQL policy wiring is adapted directly from the existing TPC-DS POC's `sql/04`–`sql/09` (same customer row-filter semantics, same file numbering convention, pointed at the new catalog).

**Tech Stack:** PySpark 3.4+, Delta Lake (write step only, requires Databricks/Unity Catalog — not exercised by local unit tests), pytest, Python 3.9+ standard library (`csv`, `dataclasses`) for the CSV readers.

## Global Constraints

- Source spec: `docs/superpowers/specs/2026-07-23-onetrust-synthetic-dataset-design.md` — every row-count target, table list, and scope decision below is copied verbatim from it.
- Catalog: `abac_onetrust`. Schemas: `onetrust_sim` (10 main tables + 5 ABAC tables), `monitoring` (`entitygroupconfig` only).
- Main-table row targets = `real_profiled_count × scale_factor`, default `scale_factor = 1`: `cmb_assessment`=4984, `cmb_controlimplementation`=2573, `cmb_inventory`=8750, `cmb_riskrelatedobjects`=1100, `cmb_template`=5235, `cmb_v_assessment_v4`=1591030, `cmb_v_inventoryaggregatedrisksummary`=14, `entitylink_v3`=1007335, `orghierarchy`=183, `reportingmoduletoentityreferencemapping_v`=21, `entitygroupconfig`=0.
- ABAC Phase-1 row targets: `ABAC_Assignment`≈1000 (partitioned by `objectType`), `ABAC_AssignmentPermission`≈10000, `ABAC_EntitySubjectAssignment`≈100000 (partitioned by `objectType`), `UserGroupMembers`≈5000, `OrgHierarchy`=183 (reused real data, view over `OrgHierarchyBase`).
- 2 of the 6 struct/map/list columns need real generation (`questionRootMap`, `userIdsAssociatedWithAssessment` — both `cmb_assessment`), confirmed by real query usage; the other 4 (`assessmentSectionReportInformations`, `questionMap` in `cmb_assessment`; `attributes`, `personalDataObjects` in `cmb_inventory`) are low-effort/mostly-null placeholders.
- Entity-type vocabulary is the real 20-value `entityTypeReference` list from `reportingmoduletoentityreferencemapping_v`'s sample data (values are UPPERCASE, e.g. `ASSESSMENT`, `CONTROL`, `RISK`, `ASSETS`, `VENDORS`) — **note:** that sample CSV's header row is misaligned with its data (a profiling-export artifact); the real values are in the first two columns positionally, not under their labeled header names. See Task 3.
- Policy/tag wiring in Phase 1 deliberately covers only `cmb_assessment` (single type `ASSESSMENT`, no-type policy shape) and `cmb_v_inventoryaggregatedrisksummary` (per-row type via `inventoryType`, default/tagged-type policy shape) as governed root tables, plus `cmb_controlimplementation` and `cmb_template` (single types `CONTROL`/`TEMPLATE`) as non-root related-permission tables for test coverage. The other 7 main tables get no row-filter policy in Phase 1 — documented scope-narrowing, not an oversight (see Task 17).
- No `dbldatagen` in this plan — that evaluation is explicitly Phase 2's job.
- Nothing in this plan runs `CREATE CATALOG`/`CREATE POLICY`/writes a Delta table against a live workspace as part of a pytest run — those require an actual Databricks Unity Catalog connection and are executed manually on Databricks, with expected output documented per step (matching the existing repo's `sql/06_validate_row_filter.sql` convention: SQL you run and check, not an automated harness).

---

## Task 1: Package scaffold + shared config

**Files:**
- Create: `onetrust_synth/__init__.py`
- Create: `onetrust_synth/config.py`
- Test: `onetrust_synth/tests/test_config.py`

**Interfaces:**
- Produces: `MAIN_TABLES: dict[str, int]` (table name → scale_factor=1 row target), `ABAC_TABLE_ROW_TARGETS: dict[str, int]` (Phase-1 targets), `CATALOG = "abac_onetrust"`, `MAIN_SCHEMA = "onetrust_sim"`, `MONITORING_SCHEMA = "monitoring"`, `MONITORING_TABLES = {"entitygroupconfig"}`, `ENTITY_SOURCE_TABLES: dict[str, tuple[str, str | None]]` (table → (id_column, static_object_type_or_None_if_per_row)), `PROFILE_CSV_PATH`, `ABAC_PROFILE_CSV_PATH`, `SAMPLE_DATA_DIR`, `scaled_row_count(table: str, scale_factor: float) -> int`.

- [ ] **Step 1: Write the failing test**

```python
# onetrust_synth/tests/test_config.py
from onetrust_synth import config


def test_main_tables_has_11_entries_matching_design_doc():
    assert len(config.MAIN_TABLES) == 11
    assert config.MAIN_TABLES["cmb_assessment"] == 4984
    assert config.MAIN_TABLES["cmb_v_assessment_v4"] == 1591030
    assert config.MAIN_TABLES["orghierarchy"] == 183
    assert config.MAIN_TABLES["entitygroupconfig"] == 0


def test_monitoring_table_is_flagged():
    assert config.MONITORING_TABLES == {"entitygroupconfig"}
    assert "entitygroupconfig" not in (config.MAIN_TABLES.keys() - config.MONITORING_TABLES)


def test_abac_row_targets_phase1():
    assert config.ABAC_TABLE_ROW_TARGETS["ABAC_Assignment"] == 1000
    assert config.ABAC_TABLE_ROW_TARGETS["ABAC_EntitySubjectAssignment"] == 100000
    assert config.ABAC_TABLE_ROW_TARGETS["OrgHierarchy"] == 183


def test_scaled_row_count_applies_multiplier():
    assert config.scaled_row_count("cmb_assessment", 1.0) == 4984
    assert config.scaled_row_count("cmb_assessment", 0.1) == 498


def test_entity_source_tables_cover_expected_five():
    assert set(config.ENTITY_SOURCE_TABLES.keys()) == {
        "cmb_assessment", "cmb_v_assessment_v4", "cmb_controlimplementation",
        "cmb_riskrelatedobjects", "cmb_template", "cmb_inventory",
        "cmb_v_inventoryaggregatedrisksummary",
    }
    # static single-type tables carry a literal object type
    assert config.ENTITY_SOURCE_TABLES["cmb_assessment"] == ("id", "ASSESSMENT")
    assert config.ENTITY_SOURCE_TABLES["cmb_riskrelatedobjects"] == ("riskId", "RISK")
    # per-row-type tables carry None — the type comes from a column, not a literal
    assert config.ENTITY_SOURCE_TABLES["cmb_inventory"] == ("id", None)


def test_inventory_type_mapping_handles_hyphenation():
    # confirmed against real cmb_v_inventoryaggregatedrisksummary sample data — NOT
    # a plain .upper(), "Processing Activities" hyphenates in the real vocabulary
    assert config.INVENTORY_TYPE_TO_OBJECT_TYPE["Processing Activities"] == "PROCESSING-ACTIVITIES"
    assert config.INVENTORY_TYPE_TO_OBJECT_TYPE["Assets"] == "ASSETS"
    assert config.INVENTORY_TYPE_TO_OBJECT_TYPE["Vendors"] == "VENDORS"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_config.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'onetrust_synth'`

- [ ] **Step 3: Write the config module**

```python
# onetrust_synth/__init__.py
```
(empty — marks the package)

```python
# onetrust_synth/config.py
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_config.py -v`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/__init__.py onetrust_synth/config.py onetrust_synth/tests/test_config.py
git commit -m "feat(onetrust_synth): add package scaffold and shared config"
```

---

## Task 2: Profile CSV reader

**Files:**
- Create: `onetrust_synth/profile_csv.py`
- Test: `onetrust_synth/tests/test_profile_csv.py`

**Interfaces:**
- Consumes: `config.PROFILE_CSV_PATH`, `config.ABAC_PROFILE_CSV_PATH` (Task 1)
- Produces: `ColumnProfile` dataclass (`name: str`, `data_type: str`, `ndv: int`, `null_rate: float`, `min_val: str | None`, `max_val: str | None`), `load_table_profile(csv_path: str) -> dict[tuple[str, str], list[ColumnProfile]]` keyed by `(schema, table)`, `get_columns(profile: dict, schema: str, table: str) -> list[ColumnProfile]`.

- [ ] **Step 1: Write the failing test**

```python
# onetrust_synth/tests/test_profile_csv.py
import csv
import os
import tempfile

from onetrust_synth import config
from onetrust_synth.profile_csv import ColumnProfile, load_table_profile, get_columns


def test_parses_real_profile_csv_orghierarchy():
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "orghierarchy")
    names = [c.name for c in cols]
    assert names == [
        "rootOrgId", "rootOrgName", "orgId", "orgName", "parentOrgId",
        "parentOrgName", "eventTime", "recModifiedTime", "isDeleted", "tenantHash",
    ]
    org_id_col = next(c for c in cols if c.name == "orgId")
    assert org_id_col.ndv == 68
    assert org_id_col.null_rate == 0.0


def test_thirteen_distinct_tables_in_real_csv():
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    assert len(profile) == 13


def test_null_rate_computed_from_null_count_over_row_count():
    with tempfile.NamedTemporaryFile(mode="w", suffix=".csv", delete=False, newline="") as f:
        writer = csv.writer(f)
        writer.writerow(["schema", "table", "column_name", "data_type", "row_count", "ndv", "null_count", "non_null_count", "min_val", "max_val", "error"])
        writer.writerow(["s", "t", "col_a", "string", "100", "10", "25.0", "75", "a", "z", ""])
        path = f.name
    try:
        profile = load_table_profile(path)
        cols = get_columns(profile, "s", "t")
        assert cols[0].null_rate == 0.25
    finally:
        os.unlink(path)


def test_missing_ndv_defaults_to_zero_for_unsupported_nested_types():
    # cmb_assessment.assessmentSectionReportInformations has no ndv/null stats
    # (the profiling engine couldn't compute min/max on nested types)
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "cmb_assessment")
    nested_col = next(c for c in cols if c.name == "questionRootMap")
    assert nested_col.ndv == 0
    assert nested_col.null_rate == 0.0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_profile_csv.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'onetrust_synth.profile_csv'`

- [ ] **Step 3: Write the profile reader**

```python
# onetrust_synth/profile_csv.py
import csv
from dataclasses import dataclass


@dataclass
class ColumnProfile:
    name: str
    data_type: str
    ndv: int
    null_rate: float
    min_val: str | None
    max_val: str | None


def _clean_lines(f):
    for line in f:
        yield line.replace("\0", "")


def load_table_profile(csv_path: str) -> dict[tuple[str, str], list[ColumnProfile]]:
    result: dict[tuple[str, str], list[ColumnProfile]] = {}
    with open(csv_path, newline="", encoding="utf-8", errors="replace") as f:
        reader = csv.DictReader(_clean_lines(f))
        for row in reader:
            key = (row["schema"], row["table"])
            row_count = float(row["row_count"] or 0)
            null_count_raw = row.get("null_count") or ""
            ndv_raw = row.get("ndv") or ""
            null_rate = 0.0
            if null_count_raw.strip() and row_count > 0:
                null_rate = float(null_count_raw) / row_count
            ndv = int(float(ndv_raw)) if ndv_raw.strip() else 0
            col = ColumnProfile(
                name=row["column_name"],
                data_type=row["data_type"],
                ndv=ndv,
                null_rate=null_rate,
                min_val=row.get("min_val") or None,
                max_val=row.get("max_val") or None,
            )
            result.setdefault(key, []).append(col)
    return result


def get_columns(profile: dict[tuple[str, str], list[ColumnProfile]], schema: str, table: str) -> list[ColumnProfile]:
    return profile[(schema, table)]
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_profile_csv.py -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/profile_csv.py onetrust_synth/tests/test_profile_csv.py
git commit -m "feat(onetrust_synth): add profile CSV reader"
```

---

## Task 3: Sample CSV reader (categorical value pools)

**Files:**
- Create: `onetrust_synth/sample_csv.py`
- Test: `onetrust_synth/tests/test_sample_csv.py`

**Interfaces:**
- Consumes: `config.SAMPLE_DATA_DIR` (Task 1); `profile_csv.load_table_profile`, `profile_csv.get_columns` (Task 2)
- Produces: `sample_file_path(table: str) -> str`, `load_column_values(table: str, column: str) -> list[str]` (distinct non-null real values for a column, for weighted-categorical seeding), `load_entity_type_reference_values() -> list[tuple[str, str]]` (the (reportingModule, entityTypeReference) pairs), `load_rows(table: str) -> list[dict]` (full rows, for near-verbatim small-table copying in Task 7)

**Confirmed data defect — every `sample_auto_qa_*.csv` file's header row is corrupted.** All 9 of the tenant-schema sample files (`cmb_assessment`, `cmb_controlimplementation`, `cmb_inventory`, `cmb_riskrelatedobjects`, `cmb_template`, `cmb_v_assessment_v4`, `entitylink_v3`, `orghierarchy`, `reportingmoduletoentityreferencemapping_v`) share the byte-identical header line (MD5 `d7ec75a45d96290f05b8db9dacd2ebe8`) — it is `cmb_v_inventoryaggregatedrisksummary`'s own 21-column header, pasted onto every other file's data rows. Only `cmb_v_inventoryaggregatedrisksummary` itself (the true source of that header) and `entitygroupconfig` (a separate export under the `monitoring` schema) are unaffected.

The DATA rows are intact, not corrupted: every affected table's row field-count matches its own real profiled column count exactly (verified: `cmb_assessment`=70, `cmb_controlimplementation`=58, `cmb_riskrelatedobjects`=20, `cmb_template`=24, `cmb_v_assessment_v4`=90, `entitylink_v3`=30, `orghierarchy`=10, `cmb_inventory`=19, `reportingmoduletoentityreferencemapping_v`=2 — all matching `onetrust_table_profile_results.csv`), and a positional spot-check confirms the real column *order* from that profile CSV lines up with the data (`cmb_assessment`'s `id` column, at profiled position 14, recovers a genuine UUID `035e1c48-5e60-4640-ac18-4557cd49828f`; `isDeleted` at position 68 recovers `'True'`). So: **never trust a sample file's own header row.** `load_rows` must always reconstruct each row by zipping the raw CSV fields positionally against the real column order from `profile_csv.get_columns()` for that (schema, table) — this uniformly and correctly recovers all 11 tables with one mechanism, including `cmb_inventory` (previously miscategorized as unrecoverable in an earlier draft of this task — it isn't; recovered real `inventoryType` values are `{"Assets", "Vendors", "Processing Activities"}`, exactly matching `config.INVENTORY_TYPE_TO_OBJECT_TYPE`'s keys) and `reportingmoduletoentityreferencemapping_v` (whose real schema is exactly the 2 columns `reportingModule`, `entityTypeReference`, so the general mechanism subsumes what previously needed special-case positional handling).

- [ ] **Step 1: Write the failing test**

```python
# onetrust_synth/tests/test_sample_csv.py
from onetrust_synth.sample_csv import (
    sample_file_path, load_column_values, load_entity_type_reference_values, load_rows,
)


def test_sample_file_path_resolves_known_table():
    path = sample_file_path("cmb_assessment")
    assert path.endswith("sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_assessment.csv")


def test_load_column_values_returns_real_distinct_values():
    values = load_column_values("cmb_assessment", "status")
    assert set(values) == {"Active", "Archived"}


def test_load_entity_type_reference_values_recovers_real_pairs():
    pairs = load_entity_type_reference_values()
    assert len(pairs) == 21
    assert ("ASSESSMENT", "ASSESSMENT") in pairs
    assert ("AI_GOVERNANCE", "AIAGENTS") in pairs
    distinct_types = {t for _, t in pairs}
    assert len(distinct_types) == 20
    assert "ASSETS" in distinct_types
    assert "VENDORS" in distinct_types


def test_load_rows_recovers_real_columns_for_orghierarchy_despite_corrupted_header():
    # orghierarchy's own sample-file header is corrupted (it's actually
    # cmb_v_inventoryaggregatedrisksummary's header, pasted on by the export
    # tool — confirmed via MD5, identical across 9 of the 11 sample files).
    # load_rows must recover the REAL columns (rootOrgId/orgId/parentOrgId,
    # from onetrust_table_profile_results.csv), not the corrupted header's
    # field names (inventoryID/entityID/orgID/parentOrgID).
    rows = load_rows("orghierarchy")
    assert len(rows) == 183
    assert set(rows[0].keys()) == {
        "rootOrgId", "rootOrgName", "orgId", "orgName", "parentOrgId",
        "parentOrgName", "eventTime", "recModifiedTime", "isDeleted", "tenantHash",
    }
    assert rows[0]["orgId"]
    assert rows[0]["isDeleted"] in ("True", "False")


def test_load_rows_recovers_real_columns_for_cmb_inventory():
    # Previously miscategorized as unrecoverable — it isn't. cmb_inventory's
    # data rows have exactly 19 fields, matching its own real profiled column
    # count, and recover correctly via the same positional mechanism.
    rows = load_rows("cmb_inventory")
    assert len(rows) == 500
    inventory_types = {r["inventoryType"] for r in rows if r.get("inventoryType")}
    assert inventory_types <= {"Assets", "Vendors", "Processing Activities"}
    assert len(inventory_types) > 0


def test_load_column_values_works_for_every_affected_table_not_just_two():
    # Regression guard: an earlier version of this reader only special-cased
    # cmb_inventory and reportingmoduletoentityreferencemapping_v, silently
    # returning wrong/empty values for the other 7 corrupted-header tables.
    # cmb_assessment.id must recover real UUID-shaped values.
    ids = load_column_values("cmb_assessment", "id")
    assert len(ids) > 0
    assert all(len(i) == 36 and i.count("-") == 4 for i in ids)  # UUID shape
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_sample_csv.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'onetrust_synth.sample_csv'`

- [ ] **Step 3: Write the sample CSV reader**

```python
# onetrust_synth/sample_csv.py
"""
Every sample_auto_qa_*.csv file's header row is corrupted: it is literally
cmb_v_inventoryaggregatedrisksummary's own 21-column header, pasted onto
every other table's data rows by the export tool (confirmed: identical MD5
across 9 of the 11 sample files). The data itself is intact — each affected
table's row field-count matches ITS OWN real profiled column count exactly,
and the real column ORDER (from onetrust_table_profile_results.csv) lines up
positionally with the data (spot-checked: cmb_assessment's `id` column
recovers a real UUID). So this module never trusts a sample file's own
header — it always reconstructs each row using the real column order from
profile_csv.get_columns(), zipped positionally against the raw CSV fields.
This uniformly recovers all 11 tables with one mechanism.
"""
import csv
import os

from onetrust_synth import config
from onetrust_synth.profile_csv import load_table_profile, get_columns

# Maps our table names to the real sample-file basenames (they don't follow a
# single naming rule: some carry the schema hash, one lives under a different
# schema prefix).
_SAMPLE_FILES = {
    "cmb_assessment": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_assessment.csv",
    "cmb_controlimplementation": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_controlimplementation.csv",
    "cmb_inventory": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_inventory.csv",
    "cmb_riskrelatedobjects": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_riskrelatedobjects.csv",
    "cmb_template": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_template.csv",
    "cmb_v_assessment_v4": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_assessment_v4.csv",
    "cmb_v_inventoryaggregatedrisksummary": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_inventoryaggregatedrisksummary.csv",
    "entitylink_v3": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_entitylink_v3.csv",
    "orghierarchy": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_orghierarchy.csv",
    "reportingmoduletoentityreferencemapping_v": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_reportingmoduletoentityreferencemapping_v.csv",
    "entitygroupconfig": "sample_monitoring_entitygroupconfig.csv",
}

_TARGET_TENANT_SCHEMA = "auto_qa_e40yx52dkbjpcqazimno9yvh4k"


def sample_file_path(table: str) -> str:
    return os.path.join(config.SAMPLE_DATA_DIR, _SAMPLE_FILES[table])


def _profile_schema_for(table: str) -> str:
    return config.MONITORING_SCHEMA if table in config.MONITORING_TABLES else _TARGET_TENANT_SCHEMA


def load_rows(table: str) -> list[dict]:
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    real_columns = [c.name for c in get_columns(profile, _profile_schema_for(table), table)]

    with open(sample_file_path(table), newline="", encoding="utf-8", errors="replace") as f:
        reader = csv.reader(f)
        next(reader, None)  # skip the file's own (corrupted) header row — never trust it
        return [dict(zip(real_columns, raw)) for raw in reader]


def load_column_values(table: str, column: str) -> list[str]:
    rows = load_rows(table)
    values = {r[column] for r in rows if r.get(column) not in (None, "")}
    return sorted(values)


def load_entity_type_reference_values() -> list[tuple[str, str]]:
    """
    reportingmoduletoentityreferencemapping_v's real schema is exactly the 2
    columns (reportingModule, entityTypeReference) — load_rows already
    recovers these correctly via the general positional-recovery mechanism
    above, so this just re-shapes them as (key, value) pairs.
    """
    rows = load_rows("reportingmoduletoentityreferencemapping_v")
    return [
        (r["reportingModule"], r["entityTypeReference"])
        for r in rows
        if r.get("reportingModule") and r.get("entityTypeReference")
    ]
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_sample_csv.py -v`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/sample_csv.py onetrust_synth/tests/test_sample_csv.py
git commit -m "feat(onetrust_synth): add sample CSV reader with misaligned-header handling"
```

---

## Task 4: Generic PySpark column generator

**Files:**
- Create: `onetrust_synth/generator.py`
- Test: `onetrust_synth/tests/test_generator.py`
- Create: `onetrust_synth/tests/conftest.py`

**Interfaces:**
- Consumes: `profile_csv.ColumnProfile` (Task 2)
- Produces: `deterministic_index(row_id_col: Column, salt: str, n: int) -> Column`, `add_categorical_column(df, col_name, values, null_rate=0.0, salt=None, row_id_col="_row_id") -> DataFrame` (hashes off `row_id_col`, not hardcoded to `_row_id`, so it can be reused post-hoc against any existing unique column — e.g. `id` — after `_row_id` has already been dropped), `add_id_column(df, col_name, prefix="") -> DataFrame` (deterministic unique string ids), `base_row_id_df(spark, n: int) -> DataFrame` (a DataFrame with one `_row_id` bigint column, 0..n-1)

- [ ] **Step 1: Write the failing test**

```python
# onetrust_synth/tests/conftest.py
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
```

```python
# onetrust_synth/tests/test_generator.py
from onetrust_synth.generator import base_row_id_df, add_categorical_column, add_id_column


def test_base_row_id_df_has_expected_row_count(spark):
    df = base_row_id_df(spark, 500)
    assert df.count() == 500
    assert df.columns == ["_row_id"]


def test_add_categorical_column_only_uses_given_values(spark):
    df = base_row_id_df(spark, 200)
    df = add_categorical_column(df, "status", ["Active", "Archived"], salt="status")
    seen = {r["status"] for r in df.select("status").collect()}
    assert seen <= {"Active", "Archived"}
    assert len(seen) == 2  # with 200 rows and 2 values, both should appear


def test_add_categorical_column_respects_null_rate(spark):
    df = base_row_id_df(spark, 10000)
    df = add_categorical_column(df, "maybe_null", ["A", "B"], null_rate=0.5, salt="maybe_null")
    null_count = df.filter(df.maybe_null.isNull()).count()
    # deterministic hash-based nulling won't be exactly 50% but should be close
    assert 4000 < null_count < 6000


def test_add_categorical_column_is_deterministic_across_runs(spark):
    df1 = add_categorical_column(base_row_id_df(spark, 100), "x", ["A", "B", "C"], salt="x")
    df2 = add_categorical_column(base_row_id_df(spark, 100), "x", ["A", "B", "C"], salt="x")
    rows1 = [r["x"] for r in df1.orderBy("_row_id").collect()]
    rows2 = [r["x"] for r in df2.orderBy("_row_id").collect()]
    assert rows1 == rows2


def test_add_id_column_produces_unique_values(spark):
    df = base_row_id_df(spark, 1000)
    df = add_id_column(df, "id", prefix="assess_")
    ids = [r["id"] for r in df.select("id").collect()]
    assert len(ids) == len(set(ids))
    assert all(i.startswith("assess_") for i in ids)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_generator.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'onetrust_synth.generator'`

- [ ] **Step 3: Write the generator module**

```python
# onetrust_synth/generator.py
"""
Deterministic, hash-based PySpark synthetic-column generation. Uses xxhash64 of
(_row_id, salt) rather than F.rand() for value SELECTION, so results are stable
across Spark re-evaluation/re-partitioning (a well-known F.rand() gotcha) — only
null-injection uses a seeded F.rand(), which is fine since it doesn't need to be
correlated with the selected value.
"""
from pyspark.sql import functions as F
from pyspark.sql import DataFrame, SparkSession


def base_row_id_df(spark: SparkSession, n: int) -> DataFrame:
    return spark.range(n).withColumnRenamed("id", "_row_id")


def deterministic_index(row_id_col, salt: str, n: int):
    return F.pmod(F.xxhash64(row_id_col, F.lit(salt)), F.lit(n))


def add_categorical_column(df: DataFrame, col_name: str, values: list, null_rate: float = 0.0, salt: str = None, row_id_col: str = "_row_id") -> DataFrame:
    salt = salt or col_name
    values_array = F.array(*[F.lit(v) for v in values])
    idx = deterministic_index(F.col(row_id_col), salt, len(values))
    base = F.element_at(values_array, (idx + F.lit(1)).cast("int"))  # element_at's index arg requires INT, not the BIGINT pmod()/xxhash64() produce
    if null_rate > 0:
        null_marker = F.pmod(F.xxhash64(F.col(row_id_col), F.lit(salt + "_null")), F.lit(10000))
        threshold = F.lit(int(null_rate * 10000))
        return df.withColumn(col_name, F.when(null_marker < threshold, F.lit(None)).otherwise(base))
    return df.withColumn(col_name, base)


def add_id_column(df: DataFrame, col_name: str, prefix: str = "") -> DataFrame:
    return df.withColumn(col_name, F.concat(F.lit(prefix), F.col("_row_id").cast("string")))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_generator.py -v`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/generator.py onetrust_synth/tests/test_generator.py onetrust_synth/tests/conftest.py
git commit -m "feat(onetrust_synth): add deterministic hash-based PySpark column generator"
```

---

## Task 5: Table builder driven by profile CSV column specs

**Files:**
- Create: `onetrust_synth/main_tables.py`
- Test: `onetrust_synth/tests/test_main_tables.py`

**Interfaces:**
- Consumes: `generator.base_row_id_df`, `generator.add_categorical_column`, `generator.add_id_column`, `generator.deterministic_index` (Task 4); `profile_csv.ColumnProfile`, `profile_csv.get_columns` (Task 2); `sample_csv.load_column_values` (Task 3)
- Produces: `build_generic_table(spark, table: str, row_count: int, columns: list[ColumnProfile], sample_lookup) -> DataFrame` — builds one column per profiled `ColumnProfile`: an `id`-like string column (name containing "id"/"Id"/"ID" and ndv≈row_count) gets a unique id; low-cardinality string columns (`ndv <= 50`) get `add_categorical_column` seeded from real sample values when available, else a synthetic pool sized toward the column's own `ndv` (not a fixed small placeholder set — a high-cardinality string column with no real samples must not collapse to a handful of values); numeric columns (`int`/`bigint`/`double`/`decimal`) and `timestamp`/`date` columns get a deterministic value **with the column's real `null_rate` applied** (a column that's 100% null in production must come out 100% null here, not fully dense); everything else (including nested types) is left for Task 6/7 to overwrite.

- [ ] **Step 1: Write the failing test**

```python
# onetrust_synth/tests/test_main_tables.py
from onetrust_synth import config
from onetrust_synth.profile_csv import load_table_profile, get_columns
from onetrust_synth.main_tables import build_generic_table


def test_build_generic_table_matches_row_count(spark):
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "cmb_template")
    df = build_generic_table(spark, "cmb_template", 200, cols, sample_lookup=lambda col: [])
    assert df.count() == 200


def test_build_generic_table_produces_all_profiled_columns(spark):
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "cmb_controlimplementation")
    df = build_generic_table(spark, "cmb_controlimplementation", 50, cols, sample_lookup=lambda col: [])
    expected_cols = {c.name for c in cols}
    assert expected_cols.issubset(set(df.columns))


def test_low_cardinality_column_uses_real_sample_values_when_available(spark):
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "cmb_assessment")

    def sample_lookup(col_name):
        if col_name == "status":
            return ["Active", "Archived"]
        return []

    df = build_generic_table(spark, "cmb_assessment", 300, cols, sample_lookup=sample_lookup)
    seen = {r["status"] for r in df.select("status").collect() if r["status"] is not None}
    assert seen <= {"Active", "Archived"}


def test_id_like_column_is_unique(spark):
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "cmb_riskrelatedobjects")
    df = build_generic_table(spark, "cmb_riskrelatedobjects", 500, cols, sample_lookup=lambda col: [])
    ids = [r["riskId"] for r in df.select("riskId").collect()]
    assert len(ids) == len(set(ids))


def test_numeric_and_temporal_columns_respect_real_null_rate(spark):
    # cmb_controlimplementation.deadline is real null_rate=1.0 (always null in
    # production); number (bigint) is real null_rate≈0.998. A generator that
    # ignores null_rate for numeric/temporal columns silently fabricates dense
    # data the real table doesn't have — caught by a prior task review.
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "cmb_controlimplementation")
    df = build_generic_table(spark, "cmb_controlimplementation", 500, cols, sample_lookup=lambda col: [])
    deadline_col = next(c for c in cols if c.name == "deadline")
    assert deadline_col.null_rate == 1.0
    non_null_deadlines = df.filter(df.deadline.isNotNull()).count()
    assert non_null_deadlines == 0

    number_col = next(c for c in cols if c.name == "number")
    assert number_col.null_rate > 0.9
    non_null_numbers = df.filter(df.number.isNotNull()).count()
    assert non_null_numbers < 25  # ~0.2% of 500 should be non-null, not all 500


def test_high_cardinality_string_column_without_samples_does_not_collapse(spark):
    # cmb_assessment.template has real ndv=2558 with no calibrated sample
    # values supplied here (sample_lookup returns []). Deliberately NOT
    # "templateID": that column's name ends in the id-like suffix, so at this
    # row_count it routes through _is_id_like's unique-id path instead of the
    # catch-all placeholder-pool path this test targets — a prior review
    # round caught a test that looked like it covered the fix but silently
    # exercised the wrong code path instead. "template" has the identical
    # real ndv (2558) with no id-like name, so it genuinely reaches
    # _placeholder_values_for.
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    cols = get_columns(profile, "auto_qa_e40yx52dkbjpcqazimno9yvh4k", "cmb_assessment")
    df = build_generic_table(spark, "cmb_assessment", 500, cols, sample_lookup=lambda col: [])
    distinct_templates = df.select("template").distinct().count()
    assert distinct_templates > 50  # nowhere near the real ndv=2558, but far above a 10-value collapse
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_main_tables.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'onetrust_synth.main_tables'`

- [ ] **Step 3: Write the generic table builder**

```python
# onetrust_synth/main_tables.py
from pyspark.sql import functions as F
from pyspark.sql import DataFrame, SparkSession

from onetrust_synth.generator import base_row_id_df, add_categorical_column, add_id_column, deterministic_index
from onetrust_synth.profile_csv import ColumnProfile

_ID_LIKE_SUFFIXES = ("id", "Id", "ID")
_LOW_CARDINALITY_MAX = 50
_PLACEHOLDER_POOL_CAP = 200  # cap on a synthetic value pool for a high-cardinality column with no real samples
_NUMERIC_TYPES = ("int", "bigint", "double", "decimal")  # tuple: str.startswith() requires a tuple, not a set
_TEMPORAL_TYPES = {"timestamp", "date"}


def _is_id_like(col: ColumnProfile, row_count: int) -> bool:
    name_hits = col.name.endswith(_ID_LIKE_SUFFIXES) or col.name == "id"
    high_cardinality = row_count > 0 and col.ndv >= row_count * 0.9
    return name_hits and high_cardinality


def _placeholder_values_for(table: str, col: ColumnProfile) -> list:
    # Sized toward the column's real cardinality (capped for practicality)
    # instead of a fixed 10-value pool — a fixed small pool collapses every
    # high-cardinality column with no real samples to the same handful of
    # values regardless of how varied the real data actually is.
    pool_size = min(col.ndv, _PLACEHOLDER_POOL_CAP) if col.ndv else 10
    return [f"{table}.{col.name}_{i}" for i in range(max(pool_size, 1))]


def _with_null_injection(df: DataFrame, col_name: str, value, null_rate: float, salt: str) -> DataFrame:
    if null_rate > 0:
        null_marker = F.pmod(F.xxhash64(F.col("_row_id"), F.lit(salt + "_null")), F.lit(10000))
        threshold = F.lit(int(null_rate * 10000))
        return df.withColumn(col_name, F.when(null_marker < threshold, F.lit(None)).otherwise(value))
    return df.withColumn(col_name, value)


def build_generic_table(spark: SparkSession, table: str, row_count: int, columns: list[ColumnProfile], sample_lookup) -> DataFrame:
    if row_count == 0:
        schema_fields = ", ".join(f"`{c.name}` STRING" for c in columns)
        return spark.createDataFrame([], schema=schema_fields)

    df = base_row_id_df(spark, row_count)

    for col in columns:
        dtype = col.data_type.lower()
        if _is_id_like(col, row_count):
            df = add_id_column(df, col.name, prefix=f"{table}_")
        elif dtype == "boolean":
            df = add_categorical_column(df, col.name, [True, False], null_rate=col.null_rate, salt=f"{table}.{col.name}")
        elif dtype.startswith(_NUMERIC_TYPES):
            idx = deterministic_index(F.col("_row_id"), f"{table}.{col.name}", 1000)
            value = idx.cast("double" if "double" in dtype or "decimal" in dtype else "long")
            df = _with_null_injection(df, col.name, value, col.null_rate, f"{table}.{col.name}")
        elif dtype in _TEMPORAL_TYPES:
            idx = deterministic_index(F.col("_row_id"), f"{table}.{col.name}", 90).cast("int")
            base_date = F.to_date(F.lit("2026-03-17"))
            d = F.date_add(base_date, idx)
            value = F.to_timestamp(d) if dtype == "timestamp" else d
            df = _with_null_injection(df, col.name, value, col.null_rate, f"{table}.{col.name}")
        elif dtype.startswith(("map", "list", "struct", "array")):
            continue  # nested types handled by Task 7's overrides, not here
        else:
            values = sample_lookup(col.name)
            if not values:
                values = _placeholder_values_for(table, col)
            elif col.ndv and col.ndv <= _LOW_CARDINALITY_MAX:
                values = values[: max(col.ndv, 1)] or _placeholder_values_for(table, col)
            df = add_categorical_column(df, col.name, values, null_rate=col.null_rate, salt=f"{table}.{col.name}")

    return df.drop("_row_id")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_main_tables.py -v`
Expected: PASS (6 tests)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/main_tables.py onetrust_synth/tests/test_main_tables.py
git commit -m "feat(onetrust_synth): add profile-driven generic table builder"
```

---

## Task 6: Nested struct/map/list columns

**Files:**
- Create: `onetrust_synth/nested_columns.py`
- Test: `onetrust_synth/tests/test_nested_columns.py`

**Interfaces:**
- Consumes: output `DataFrame` from `main_tables.build_generic_table` (Task 5)
- Produces: `attach_cmb_assessment_nested_columns(df: DataFrame) -> DataFrame` (adds well-typed, real `questionRootMap` + `userIdsAssociatedWithAssessment` — the latter's values must be UUID-shaped strings, deterministically derived not randomly generated, matching design doc section 5.3 — plus mostly-null placeholders for `assessmentSectionReportInformations`/`questionMap`), `attach_cmb_inventory_nested_columns(df: DataFrame) -> DataFrame` (mostly-null placeholders for `attributes`/`personalDataObjects`)

- [ ] **Step 1: Write the failing test**

```python
# onetrust_synth/tests/test_nested_columns.py
from onetrust_synth.generator import base_row_id_df, add_id_column
from onetrust_synth.nested_columns import attach_cmb_assessment_nested_columns, attach_cmb_inventory_nested_columns


def test_question_root_map_is_a_well_typed_map(spark):
    df = add_id_column(base_row_id_df(spark, 20), "id", prefix="assess_")
    df = attach_cmb_assessment_nested_columns(df)
    row = df.select("questionRootMap").first()
    assert row["questionRootMap"] is not None
    # a MAP<STRING, STRUCT<...>> value: dict of key -> Row
    (key, value), = list(row["questionRootMap"].items())[:1]
    assert isinstance(key, str)
    assert value["questionType"] is not None
    assert value["responseType"] is not None
    assert isinstance(value["responses"], list)


def test_user_ids_associated_with_assessment_is_array_of_strings(spark):
    df = add_id_column(base_row_id_df(spark, 20), "id", prefix="assess_")
    df = attach_cmb_assessment_nested_columns(df)
    row = df.select("userIdsAssociatedWithAssessment").first()
    assert isinstance(row["userIdsAssociatedWithAssessment"], list)
    assert all(isinstance(x, str) for x in row["userIdsAssociatedWithAssessment"])


def test_unreferenced_nested_columns_are_mostly_null_placeholders(spark):
    df = add_id_column(base_row_id_df(spark, 1000), "id", prefix="assess_")
    df = attach_cmb_assessment_nested_columns(df)
    null_rate = df.filter(df.questionMap.isNull()).count() / 1000
    assert null_rate > 0.9  # "mostly null" per design doc section 5.3


def test_cmb_inventory_nested_columns_present_and_mostly_null(spark):
    df = add_id_column(base_row_id_df(spark, 1000), "id", prefix="inv_")
    df = attach_cmb_inventory_nested_columns(df)
    assert "attributes" in df.columns
    assert "personalDataObjects" in df.columns
    null_rate = df.filter(df.attributes.isNull()).count() / 1000
    assert null_rate > 0.9


def test_user_ids_associated_with_assessment_are_uuid_shaped(spark):
    # Design doc section 5.3 explicitly requires "a real LIST<STRING> of
    # UUID-shaped values per row" for this column — a prior review caught an
    # earlier version generating "user_0".."user_1999" instead. Deterministic
    # (not a real random UUID, which would break this project's core
    # reproducibility guarantee — see generator.py), but must look like a
    # UUID: 36 chars, 4 hyphens at the standard positions.
    df = add_id_column(base_row_id_df(spark, 200), "id", prefix="assess_")
    df = attach_cmb_assessment_nested_columns(df)
    rows = df.select("userIdsAssociatedWithAssessment").collect()
    all_ids = [uid for r in rows for uid in r["userIdsAssociatedWithAssessment"]]
    assert len(all_ids) > 0
    for uid in all_ids:
        assert len(uid) == 36
        assert uid[8] == "-" and uid[13] == "-" and uid[18] == "-" and uid[23] == "-"


def test_question_root_map_is_queryable_via_element_at_at_sql_level(spark):
    # The whole point of NOT null-placeholder-ing this column is that real
    # compatible queries call element_at(questionRootMap, '<uuid>') in a
    # SELECT list. A test that only inspects values already collected into
    # the Python driver doesn't verify this — it must be checked as an
    # actual Spark SQL expression, the same way the real queries use it.
    from pyspark.sql import functions as F

    df = add_id_column(base_row_id_df(spark, 500), "id", prefix="assess_")
    df = attach_cmb_assessment_nested_columns(df)
    for key in ["a2d09d79-b6e2-42d7-a04d-a5726a062738", "d82a01e9-276b-4499-8b47-7d5068536f4f", "f3c1a0aa-1234-4a1b-9c3d-9a1b2c3d4e5f"]:
        matched = df.filter(F.element_at(F.col("questionRootMap"), F.lit(key)).isNotNull()).count()
        assert matched > 0, f"element_at never resolved for key {key}"


def test_user_ids_associated_with_assessment_is_array_contains_queryable_at_sql_level(spark):
    from pyspark.sql import functions as F

    df = add_id_column(base_row_id_df(spark, 500), "id", prefix="assess_")
    df = attach_cmb_assessment_nested_columns(df)
    any_id = df.select(F.element_at(F.col("userIdsAssociatedWithAssessment"), 1).alias("uid")).first()["uid"]
    matched = df.filter(F.array_contains(F.col("userIdsAssociatedWithAssessment"), any_id)).count()
    assert matched > 0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_nested_columns.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'onetrust_synth.nested_columns'`

- [ ] **Step 3: Write the nested-column module**

```python
# onetrust_synth/nested_columns.py
"""
Handles the 6 struct/map/list columns with no flat profiled stats.
questionRootMap and userIdsAssociatedWithAssessment (both cmb_assessment) are
referenced by real compatible queries (via element_at()/array_contains()) and
get genuine, well-typed generated data. The other 4 are confirmed unreferenced
by any compatible query and get mostly-null placeholders — see design doc
section 5.3.
"""
from pyspark.sql import functions as F
from pyspark.sql import DataFrame

_QUESTION_KEYS = [
    "a2d09d79-b6e2-42d7-a04d-a5726a062738",
    "d82a01e9-276b-4499-8b47-7d5068536f4f",
    "f3c1a0aa-1234-4a1b-9c3d-9a1b2c3d4e5f",
]
_QUESTION_TYPES = ["SINGLE_CHOICE", "MULTI_CHOICE", "TEXT"]
_RESPONSE_TYPES = ["TEXT", "OPTION"]
_QUESTION_STATES = ["ANSWERED", "UNANSWERED", "SKIPPED"]
_QUESTION_DETAILS = [
    "auto-generated question detail A",
    "auto-generated question detail B",
    "auto-generated question detail C",
]


def _null_placeholder(df: DataFrame, col_name: str, spark_type: str) -> DataFrame:
    return df.withColumn(col_name, F.lit(None).cast(spark_type))


def _pick(id_col, salt: str, values: list):
    idx = F.pmod(F.xxhash64(id_col, F.lit(salt)), F.lit(len(values)))
    return F.element_at(F.array(*[F.lit(v) for v in values]), (idx + F.lit(1)).cast("int"))


def _deterministic_uuid_shaped(id_col, salt: str):
    # Deterministic (hash-based, not a real random UUID — a random value
    # would break this project's reproducibility guarantee, see
    # generator.py), but formatted to LOOK like one: 8-4-4-4-12 hex groups,
    # matching the design doc's "UUID-shaped values" requirement.
    h = F.md5(F.concat(id_col, F.lit(salt)))
    return F.concat(
        F.substring(h, 1, 8), F.lit("-"),
        F.substring(h, 9, 4), F.lit("-"),
        F.substring(h, 13, 4), F.lit("-"),
        F.substring(h, 17, 4), F.lit("-"),
        F.substring(h, 21, 12),
    )


def attach_cmb_assessment_nested_columns(df: DataFrame) -> DataFrame:
    key = _pick(F.col("id"), "questionRootMap", _QUESTION_KEYS)
    qtype = _pick(F.col("id"), "qtype", _QUESTION_TYPES)
    rtype = _pick(F.col("id"), "rtype", _RESPONSE_TYPES)
    qstate = _pick(F.col("id"), "qstate", _QUESTION_STATES)
    qdetail = _pick(F.col("id"), "qdetail", _QUESTION_DETAILS)
    response_value = _pick(F.col("id"), "resp_value", ["response A", "response B", "response C"])
    response_key = _pick(F.col("id"), "resp_key", ["resp_key_1", "resp_key_2", "resp_key_3"])

    response_struct = F.struct(response_value.alias("value"), response_key.alias("valueKey"))
    value_struct = F.struct(
        qtype.alias("questionType"),
        F.lit("STRING").alias("dataType"),
        qstate.alias("state"),
        F.lit(False).alias("maturityScaleAllowed"),
        qdetail.alias("questionDetailedInfo"),
        F.array(response_struct).alias("responses"),
        rtype.alias("responseType"),
    )
    df = df.withColumn("questionRootMap", F.create_map(key, value_struct))

    user_id_1 = _deterministic_uuid_shaped(F.col("id"), "uid1")
    user_id_2 = _deterministic_uuid_shaped(F.col("id"), "uid2")
    df = df.withColumn("userIdsAssociatedWithAssessment", F.array(user_id_1, user_id_2))

    struct_type = "struct<id:struct<id:string>,name:struct<respondents:array<struct<value:string,valueKey:string>>>>"
    df = _null_placeholder(df, "assessmentSectionReportInformations", f"array<{struct_type}>")
    df = _null_placeholder(df, "questionMap", "map<string,struct<key:string,value:string>>")
    return df


def attach_cmb_inventory_nested_columns(df: DataFrame) -> DataFrame:
    df = _null_placeholder(df, "attributes", "map<string,array<struct<value:string,valueKey:string>>>")
    df = _null_placeholder(df, "personalDataObjects", "array<struct<dataElement:string,dataCategory:string>>")
    return df
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_nested_columns.py -v`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/nested_columns.py onetrust_synth/tests/test_nested_columns.py
git commit -m "feat(onetrust_synth): generate real questionRootMap/userIdsAssociatedWithAssessment data"
```

---

## Task 7: Small-table near-verbatim builders (orghierarchy, cmb_v_inventoryaggregatedrisksummary)

**Files:**
- Create: `onetrust_synth/verbatim_tables.py`
- Test: `onetrust_synth/tests/test_verbatim_tables.py`

**Interfaces:**
- Consumes: `sample_csv.load_rows` (Task 3); `profile_csv.load_table_profile`, `profile_csv.get_columns` (Task 2)
- Produces: `build_orghierarchy_df(spark) -> DataFrame` (all 183 real rows, verbatim — this becomes `org_registry` too, see Task 9), `build_cmb_v_inventoryaggregatedrisksummary_df(spark) -> DataFrame` (all 14 real rows, verbatim). Both cast every column to its real profiled Spark type (numeric/temporal/boolean), not left as the raw CSV string — `load_rows` only ever returns strings, and leaving numeric columns string-typed silently breaks `ORDER BY` (lexicographic, not numeric).

- [ ] **Step 1: Write the failing test**

```python
# onetrust_synth/tests/test_verbatim_tables.py
from onetrust_synth.verbatim_tables import build_orghierarchy_df, build_cmb_v_inventoryaggregatedrisksummary_df


def test_orghierarchy_has_183_real_rows(spark):
    df = build_orghierarchy_df(spark)
    assert df.count() == 183
    assert df.select("orgId").distinct().count() == 68


def test_orghierarchy_preserves_ancestor_closure_shape(spark):
    df = build_orghierarchy_df(spark)
    # an org with multiple parent rows (ancestor closure), not single-level adjacency
    counts = df.groupBy("orgId").count().collect()
    assert any(r["count"] > 1 for r in counts)


def test_inventory_risk_summary_has_14_real_rows(spark):
    df = build_cmb_v_inventoryaggregatedrisksummary_df(spark)
    assert df.count() == 14
    assert "inventoryType" in df.columns
    types = {r["inventoryType"] for r in df.select("inventoryType").collect()}
    # verified against the real sample file: {'Assets', 'Processing Activities', 'Vendors'}
    assert types <= {"Assets", "Vendors", "Processing Activities"}


def test_inventory_risk_summary_numeric_columns_are_real_typed_not_string(spark):
    # A prior task review caught that every column coming straight out of a CSV
    # is string-typed by default, which silently breaks ORDER BY on numeric
    # columns (lexicographic '10' < '2' instead of numeric 2 < 10). This table
    # is the single most-queried one in the Phase-1 compatible-query set (39 of
    # 50 — design doc section 3), so its numeric/temporal columns must be cast
    # to their real profiled types.
    df = build_cmb_v_inventoryaggregatedrisksummary_df(spark)
    schema = {f.name: f.dataType.typeName() for f in df.schema.fields}
    assert schema["inherentRiskScore"] == "double"
    assert schema["residualRiskScore"] == "double"
    assert schema["targetRiskScore"] == "double"
    assert schema["inventoryTypeID"] == "integer"
    assert schema["inventoryNumber"] == "long"
    ordered = [r["inventoryNumber"] for r in df.orderBy("inventoryNumber").select("inventoryNumber").collect()]
    assert ordered == sorted(ordered)  # numeric order, not lexicographic


def test_orghierarchy_temporal_and_boolean_columns_are_real_typed(spark):
    df = build_orghierarchy_df(spark)
    schema = {f.name: f.dataType.typeName() for f in df.schema.fields}
    assert schema["eventTime"] == "timestamp"
    assert schema["recModifiedTime"] == "timestamp"
    assert schema["isDeleted"] == "boolean"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_verbatim_tables.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'onetrust_synth.verbatim_tables'`

- [ ] **Step 3: Write the verbatim builders**

```python
# onetrust_synth/verbatim_tables.py
"""
orghierarchy and cmb_v_inventoryaggregatedrisksummary are small enough (183 and
14 rows) that the design calls for using the real sample data near-verbatim
instead of synthesizing — see design doc section 3. Every value coming out of
load_rows() is a Python string (CSV has no native types), so numeric/temporal/
boolean columns are explicitly cast to their real profiled type — a prior task
review caught that leaving everything string-typed silently breaks ORDER BY on
numeric columns (lexicographic instead of numeric order), on the table that's
the single most-queried one in the Phase-1 compatible-query set.
"""
from pyspark.sql import functions as F
from pyspark.sql import SparkSession, DataFrame

from onetrust_synth import config
from onetrust_synth.sample_csv import load_rows
from onetrust_synth.profile_csv import load_table_profile, get_columns

_TARGET_TENANT_SCHEMA = "auto_qa_e40yx52dkbjpcqazimno9yvh4k"


def _spark_cast_type(profiled_dtype: str) -> str | None:
    dtype = profiled_dtype.lower()
    if dtype.startswith("bigint"):
        return "bigint"
    if dtype.startswith("int"):
        return "int"
    if dtype.startswith(("double", "decimal")):
        return "double"
    if dtype == "boolean":
        return "boolean"
    if dtype == "timestamp":
        return "timestamp"
    if dtype == "date":
        return "date"
    return None  # string and nested types: leave as the CSV's native string


def _cast_to_real_types(df: DataFrame, table: str) -> DataFrame:
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    for col in get_columns(profile, _TARGET_TENANT_SCHEMA, table):
        cast_type = _spark_cast_type(col.data_type)
        if cast_type and col.name in df.columns:
            df = df.withColumn(col.name, F.col(col.name).cast(cast_type))
    return df


def build_orghierarchy_df(spark: SparkSession) -> DataFrame:
    rows = load_rows("orghierarchy")
    df = spark.createDataFrame(rows)
    return _cast_to_real_types(df, "orghierarchy")


def build_cmb_v_inventoryaggregatedrisksummary_df(spark: SparkSession) -> DataFrame:
    rows = load_rows("cmb_v_inventoryaggregatedrisksummary")
    df = spark.createDataFrame(rows)
    return _cast_to_real_types(df, "cmb_v_inventoryaggregatedrisksummary")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_verbatim_tables.py -v`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/verbatim_tables.py onetrust_synth/tests/test_verbatim_tables.py
git commit -m "feat(onetrust_synth): build orghierarchy and inventory-risk-summary from real sample rows"
```

---

## Task 8: Delta write helper

**Files:**
- Create: `onetrust_synth/write.py`

**Interfaces:**
- Consumes: none new
- Produces: `write_delta_table(df, catalog: str, schema: str, table: str, partition_by: list[str] | None = None) -> None`

This is a thin I/O function that requires an actual Databricks/Unity Catalog SparkSession to run — it cannot be unit tested locally (there is no local Unity Catalog). No test file; correctness is verified when Task 10 actually runs it on Databricks.

- [ ] **Step 1: Write the module**

```python
# onetrust_synth/write.py
from pyspark.sql import DataFrame


def write_delta_table(df: DataFrame, catalog: str, schema: str, table: str, partition_by: list[str] | None = None) -> None:
    full_name = f"{catalog}.{schema}.{table}"
    writer = df.write.format("delta").mode("overwrite").option("overwriteSchema", "true")
    if partition_by:
        writer = writer.partitionBy(*partition_by)
    writer.saveAsTable(full_name)
```

- [ ] **Step 2: Commit**

```bash
git add onetrust_synth/write.py
git commit -m "feat(onetrust_synth): add Delta table write helper"
```

---

## Task 9: Main-table orchestration script

**Files:**
- Create: `onetrust_synth/generate_main_tables.py`

**Interfaces:**
- Consumes: everything from Tasks 1–8
- Produces: `build_all_main_tables(spark, scale_factor: float = 1.0) -> dict[str, DataFrame]` (table name → built DataFrame, columns dropped down to exactly the profiled set plus nested overrides), a `main()` entry point that builds and writes all 11 tables to `abac_onetrust.onetrust_sim` / `abac_onetrust.monitoring`.

This is the first task that touches all 11 tables together — its own test verifies the in-memory assembly (no Delta write, since that needs Databricks), catching wiring bugs (wrong catalog for `entitygroupconfig`, wrong row counts) before ever running on a cluster.

- [ ] **Step 1: Write the failing test**

```python
# onetrust_synth/tests/test_generate_main_tables.py
from onetrust_synth import config
from onetrust_synth.generate_main_tables import build_all_main_tables


def test_builds_all_11_tables(spark):
    tables = build_all_main_tables(spark, scale_factor=0.1)  # small for a fast test
    assert set(tables.keys()) == set(config.MAIN_TABLES.keys())


def test_row_counts_scale_with_factor(spark):
    tables = build_all_main_tables(spark, scale_factor=0.1)
    # cmb_assessment real count 4984 * 0.1 = 498 (rounded)
    assert tables["cmb_assessment"].count() == 498


def test_entitygroupconfig_is_empty_but_has_correct_schema(spark):
    tables = build_all_main_tables(spark, scale_factor=1.0)
    assert tables["entitygroupconfig"].count() == 0
    assert set(tables["entitygroupconfig"].columns) >= {"entityType", "numberOfGroups", "groupThreshold"}


def test_orghierarchy_ignores_scale_factor_uses_real_data(spark):
    tables = build_all_main_tables(spark, scale_factor=0.1)
    assert tables["orghierarchy"].count() == 183  # real data, not scaled


def test_cmb_assessment_has_nested_columns_attached(spark):
    tables = build_all_main_tables(spark, scale_factor=0.1)
    cols = tables["cmb_assessment"].columns
    assert "questionRootMap" in cols
    assert "userIdsAssociatedWithAssessment" in cols


def test_cmb_inventory_inventory_type_uses_real_vocabulary_not_corrupted_sample(spark):
    # cmb_inventory's own sample file is known-bad (see sample_csv.py); this locks
    # in that the generator overrides with the real, verified values instead of
    # whatever build_generic_table's generic placeholder fallback would produce.
    tables = build_all_main_tables(spark, scale_factor=1.0)
    seen = {r["inventoryType"] for r in tables["cmb_inventory"].select("inventoryType").collect() if r["inventoryType"] is not None}
    assert seen <= set(config.INVENTORY_TYPE_TO_OBJECT_TYPE.keys())
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_generate_main_tables.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'onetrust_synth.generate_main_tables'`

- [ ] **Step 3: Write the orchestration script**

```python
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

    for table_name, real_count in config.MAIN_TABLES.items():
        if table_name in tables:
            continue
        schema_key = "monitoring" if table_name in config.MONITORING_TABLES else _TARGET_SCHEMA_HASH
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
            # cmb_inventory's own sample file is known-bad (see sample_csv.py), so
            # inventoryType falls back to build_generic_table's generic placeholder
            # values above — override it with the real, same-domain vocabulary
            # confirmed from cmb_v_inventoryaggregatedrisksummary's (trustworthy)
            # sample data instead.
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_generate_main_tables.py -v`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/generate_main_tables.py onetrust_synth/tests/test_generate_main_tables.py
git commit -m "feat(onetrust_synth): orchestrate building all 11 main tables"
```

---

## Task 10: Registries — org_registry and subject_registry

**Files:**
- Create: `onetrust_synth/registries.py`
- Test: `onetrust_synth/tests/test_registries.py`

**Interfaces:**
- Consumes: `verbatim_tables.build_orghierarchy_df` (Task 7), `config.SUBJECT_REGISTRY_USER_COUNT`/`SUBJECT_REGISTRY_GROUP_COUNT` (Task 1), `generator.base_row_id_df`/`add_id_column` (Task 4)
- Produces: `build_org_registry(spark) -> DataFrame` (alias of `orghierarchy`, columns `orgId`, `parentOrgId`), `build_subject_registry(spark) -> DataFrame` (columns `subjectId: string`, `subjectType: string` — `USER_ID`/`USER_GROUP`)

- [ ] **Step 1: Write the failing test**

```python
# onetrust_synth/tests/test_registries.py
from onetrust_synth import config
from onetrust_synth.registries import build_org_registry, build_subject_registry


def test_org_registry_reuses_real_orghierarchy(spark):
    reg = build_org_registry(spark)
    assert reg.count() == 183
    assert set(reg.columns) >= {"orgId", "parentOrgId"}


def test_subject_registry_has_users_and_groups(spark):
    reg = build_subject_registry(spark)
    total = config.SUBJECT_REGISTRY_USER_COUNT + config.SUBJECT_REGISTRY_GROUP_COUNT
    assert reg.count() == total
    types = {r["subjectType"] for r in reg.select("subjectType").distinct().collect()}
    assert types == {"USER_ID", "USER_GROUP"}
    user_count = reg.filter(reg.subjectType == "USER_ID").count()
    assert user_count == config.SUBJECT_REGISTRY_USER_COUNT


def test_subject_registry_ids_are_unique(spark):
    reg = build_subject_registry(spark)
    ids = [r["subjectId"] for r in reg.select("subjectId").collect()]
    assert len(ids) == len(set(ids))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_registries.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'onetrust_synth.registries'`

- [ ] **Step 3: Write the org/subject registry builders**

```python
# onetrust_synth/registries.py
from pyspark.sql import functions as F
from pyspark.sql import SparkSession, DataFrame

from onetrust_synth import config
from onetrust_synth.verbatim_tables import build_orghierarchy_df
from onetrust_synth.generator import base_row_id_df, add_id_column


def build_org_registry(spark: SparkSession) -> DataFrame:
    return build_orghierarchy_df(spark).select("orgId", "parentOrgId")


def build_subject_registry(spark: SparkSession) -> DataFrame:
    users = add_id_column(base_row_id_df(spark, config.SUBJECT_REGISTRY_USER_COUNT), "subjectId", prefix="user_")
    users = users.withColumn("subjectType", F.lit("USER_ID")).drop("_row_id")

    groups = add_id_column(base_row_id_df(spark, config.SUBJECT_REGISTRY_GROUP_COUNT), "subjectId", prefix="group_")
    groups = groups.withColumn("subjectType", F.lit("USER_GROUP")).drop("_row_id")

    return users.unionByName(groups)
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_registries.py -v`
Expected: PASS (3 tests)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/registries.py onetrust_synth/tests/test_registries.py
git commit -m "feat(onetrust_synth): add org_registry and subject_registry builders"
```

---

## Task 11: entity_registry

**Files:**
- Modify: `onetrust_synth/registries.py`
- Modify: `onetrust_synth/tests/test_registries.py`

**Interfaces:**
- Consumes: `config.ENTITY_SOURCE_TABLES`, `config.STANDALONE_ENTITIES_PER_TYPE` (Task 1), `sample_csv.load_entity_type_reference_values` (Task 3), the `dict[str, DataFrame]` produced by `generate_main_tables.build_all_main_tables` (Task 9)
- Produces: `build_entity_registry(spark, main_tables: dict[str, DataFrame]) -> DataFrame` (columns `entityId: string`, `objectType: string`, `orgId: string | None`)

`cmb_inventory` and `cmb_v_inventoryaggregatedrisksummary` have a per-row type (their `ENTITY_SOURCE_TABLES` entry carries `None` instead of a literal): `cmb_inventory`'s type comes from its own `inventoryType` column uppercased (`Assets`→`ASSETS`, `Vendors`→`VENDORS`), matching the real `entityTypeReference` vocabulary; `cmb_v_inventoryaggregatedrisksummary` likewise from its `inventoryType` column.

- [ ] **Step 1: Write the failing test**

```python
# append to onetrust_synth/tests/test_registries.py
from onetrust_synth.registries import build_entity_registry
from onetrust_synth.generate_main_tables import build_all_main_tables


def test_entity_registry_covers_all_source_tables(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    reg = build_entity_registry(spark, main_tables)
    types = {r["objectType"] for r in reg.select("objectType").distinct().collect()}
    assert "ASSESSMENT" in types
    assert "CONTROL" in types
    assert "RISK" in types
    assert "TEMPLATE" in types


def test_entity_registry_inventory_type_comes_from_row_data(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    reg = build_entity_registry(spark, main_tables)
    inv_types = {
        r["objectType"] for r in reg.select("objectType").distinct().collect()
        if r["objectType"] in set(config.INVENTORY_TYPE_TO_OBJECT_TYPE.values())
    }
    assert len(inv_types) > 0


def test_entity_registry_inventory_type_mapping_hyphenates_correctly(spark):
    # regression guard for the F.upper()-is-wrong bug: "Processing Activities" must
    # map to "PROCESSING-ACTIVITIES" (hyphenated), not "PROCESSING ACTIVITIES"
    main_tables = build_all_main_tables(spark, scale_factor=1.0)
    reg = build_entity_registry(spark, main_tables)
    types = {r["objectType"] for r in reg.select("objectType").distinct().collect()}
    assert "PROCESSING-ACTIVITIES" in types
    assert "PROCESSING ACTIVITIES" not in types


def test_entity_registry_includes_standalone_entities_for_uncovered_types(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    reg = build_entity_registry(spark, main_tables)
    # WORKPAPER has no source table among our 11 — must still appear via standalone entities
    types = {r["objectType"] for r in reg.select("objectType").distinct().collect()}
    assert "WORKPAPER" in types
    count = reg.filter(reg.objectType == "WORKPAPER").count()
    assert count == config.STANDALONE_ENTITIES_PER_TYPE


def test_entity_registry_entity_ids_are_unique_within_type(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    reg = build_entity_registry(spark, main_tables)
    dup_check = reg.groupBy("entityId", "objectType").count().filter("count > 1").count()
    assert dup_check == 0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_registries.py -v -k entity_registry`
Expected: FAIL with `ImportError: cannot import name 'build_entity_registry'`

- [ ] **Step 3: Add entity_registry to registries.py**

```python
# append to onetrust_synth/registries.py
from onetrust_synth.sample_csv import load_entity_type_reference_values
from onetrust_synth.generator import base_row_id_df as _base_row_id_df, add_id_column as _add_id_column


def _inventory_type_to_object_type_column():
    """
    inventoryType -> objectType is NOT a plain .upper() — "Processing Activities"
    hyphenates to "PROCESSING-ACTIVITIES" in the real entityTypeReference
    vocabulary (config.INVENTORY_TYPE_TO_OBJECT_TYPE, verified against real sample
    data). Falls back to .upper() for any unmapped value rather than erroring.
    """
    mapping = config.INVENTORY_TYPE_TO_OBJECT_TYPE
    expr = F.upper(F.col("inventoryType"))
    for raw, mapped in mapping.items():
        expr = F.when(F.col("inventoryType") == raw, F.lit(mapped)).otherwise(expr)
    return expr


def build_entity_registry(spark: SparkSession, main_tables: dict) -> DataFrame:
    pieces = []

    for table_name, (id_col, static_type) in config.ENTITY_SOURCE_TABLES.items():
        df = main_tables[table_name]
        if static_type is not None:
            piece = df.select(F.col(id_col).alias("entityId")).withColumn("objectType", F.lit(static_type))
        else:
            piece = df.select(
                F.col(id_col).alias("entityId"),
                _inventory_type_to_object_type_column().alias("objectType"),
            )
        pieces.append(piece.withColumn("orgId", F.lit(None).cast("string")))

    harvested = pieces[0]
    for p in pieces[1:]:
        harvested = harvested.unionByName(p)

    covered_types = {t for _, (_, t) in config.ENTITY_SOURCE_TABLES.items() if t is not None}
    covered_types |= set(config.INVENTORY_TYPE_TO_OBJECT_TYPE.values())  # the per-row inventory types
    all_types = {t for _, t in load_entity_type_reference_values()}
    uncovered_types = sorted(all_types - covered_types)

    standalone_pieces = []
    for object_type in uncovered_types:
        df = _add_id_column(
            _base_row_id_df(spark, config.STANDALONE_ENTITIES_PER_TYPE),
            "entityId",
            prefix=f"{object_type.lower()}_",
        )
        df = df.withColumn("objectType", F.lit(object_type)).withColumn("orgId", F.lit(None).cast("string")).drop("_row_id")
        standalone_pieces.append(df)

    result = harvested
    for p in standalone_pieces:
        result = result.unionByName(p)
    return result
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_registries.py -v`
Expected: PASS (7 tests total)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/registries.py onetrust_synth/tests/test_registries.py
git commit -m "feat(onetrust_synth): add entity_registry harvested from main tables + standalone types"
```

---

## Task 12: ABAC table column specs (RTF DDL + profile CSV reconciliation)

**Files:**
- Create: `onetrust_synth/abac_schema.py`
- Test: `onetrust_synth/tests/test_abac_schema.py`

**Interfaces:**
- Produces: `ABAC_ASSIGNMENT_COLUMNS: list[str]`, `ABAC_ASSIGNMENT_PERMISSION_COLUMNS: list[str]`, `ABAC_ENTITY_SUBJECT_ASSIGNMENT_COLUMNS: list[str]`, `USER_GROUP_MEMBERS_COLUMNS: list[str]`, `ORG_HIERARCHY_BASE_COLUMNS: list[str]` — each the authoritative column-name list per design doc section 4 (RTF DDL as base, `createdBy`/`updatedBy` added where the profile CSV shows them as currently deployed).

- [ ] **Step 1: Write the failing test**

```python
# onetrust_synth/tests/test_abac_schema.py
from onetrust_synth.abac_schema import (
    ABAC_ASSIGNMENT_COLUMNS, ABAC_ASSIGNMENT_PERMISSION_COLUMNS,
    ABAC_ENTITY_SUBJECT_ASSIGNMENT_COLUMNS, USER_GROUP_MEMBERS_COLUMNS,
    ORG_HIERARCHY_BASE_COLUMNS,
)


def test_abac_assignment_matches_rtf_plus_deployed_audit_columns():
    assert ABAC_ASSIGNMENT_COLUMNS == [
        "id", "guid", "staticIdentifier", "name", "objectType", "sourceType",
        "isActive", "createdBy", "createDT", "updatedBy", "updateDT",
        "eventTime", "recModifiedTime", "tenantHash", "isDeleted",
    ]


def test_entity_subject_assignment_matches_rtf():
    assert ABAC_ENTITY_SUBJECT_ASSIGNMENT_COLUMNS == [
        "assignmentId", "policyId", "entityId", "entityOrganizationId",
        "subjectId", "subjectType", "objectType", "updateDT", "eventTime",
        "recModifiedTime", "tenantHash", "isDeleted",
    ]


def test_user_group_members_matches_rtf_exactly_no_profile_csv_entry():
    assert USER_GROUP_MEMBERS_COLUMNS == [
        "memberId", "groupId", "eventTime", "recModifiedTime", "isDeleted", "tenantHash",
    ]


def test_org_hierarchy_base_matches_rtf():
    assert ORG_HIERARCHY_BASE_COLUMNS == [
        "rootOrgId", "rootOrgName", "orgId", "orgName", "parentOrgId",
        "parentOrgName", "eventTime", "recModifiedTime", "isDeleted", "tenantHash",
    ]
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_abac_schema.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'onetrust_synth.abac_schema'`

- [ ] **Step 3: Write the ABAC schema module**

```python
# onetrust_synth/abac_schema.py
"""
Authoritative column lists for the 5 legacy ABAC tables, per design doc section 4:
RTF DDL (abac_docs/customer_data/*.rtf) is the base; createdBy/updatedBy are added
to ABAC_Assignment and ABAC_AssignmentPermission because
onetrust_abac_table_profile_results.csv shows them as currently deployed, even
though the RTF template doesn't list them. UserGroupMembers and OrgHierarchyBase
have no profile CSV entry at all — RTF DDL is the only source for those two.
"""

ABAC_ASSIGNMENT_COLUMNS = [
    "id", "guid", "staticIdentifier", "name", "objectType", "sourceType",
    "isActive", "createdBy", "createDT", "updatedBy", "updateDT",
    "eventTime", "recModifiedTime", "tenantHash", "isDeleted",
]

ABAC_ASSIGNMENT_PERMISSION_COLUMNS = [
    "assignmentId", "name", "createdBy", "createDT", "updatedBy", "updateDT",
    "eventTime", "recModifiedTime", "tenantHash", "isDeleted",
]

ABAC_ENTITY_SUBJECT_ASSIGNMENT_COLUMNS = [
    "assignmentId", "policyId", "entityId", "entityOrganizationId",
    "subjectId", "subjectType", "objectType", "updateDT", "eventTime",
    "recModifiedTime", "tenantHash", "isDeleted",
]

USER_GROUP_MEMBERS_COLUMNS = [
    "memberId", "groupId", "eventTime", "recModifiedTime", "isDeleted", "tenantHash",
]

ORG_HIERARCHY_BASE_COLUMNS = [
    "rootOrgId", "rootOrgName", "orgId", "orgName", "parentOrgId",
    "parentOrgName", "eventTime", "recModifiedTime", "isDeleted", "tenantHash",
]
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_abac_schema.py -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/abac_schema.py onetrust_synth/tests/test_abac_schema.py
git commit -m "feat(onetrust_synth): add authoritative ABAC table column specs"
```

---

## Task 13: ABAC_Assignment + ABAC_AssignmentPermission generators

**Files:**
- Create: `onetrust_synth/abac_tables.py`
- Test: `onetrust_synth/tests/test_abac_tables.py`

**Interfaces:**
- Consumes: `abac_schema.ABAC_ASSIGNMENT_COLUMNS`/`ABAC_ASSIGNMENT_PERMISSION_COLUMNS` (Task 12), `sample_csv.load_entity_type_reference_values` (Task 3), `generator.*` (Task 4)
- Produces: `build_abac_assignment(spark, row_count: int) -> DataFrame`, `build_abac_assignment_permission(spark, assignment_df: DataFrame, row_count: int) -> DataFrame`

- [ ] **Step 1: Write the failing test**

```python
# onetrust_synth/tests/test_abac_tables.py
from onetrust_synth import config
from onetrust_synth.abac_schema import ABAC_ASSIGNMENT_COLUMNS, ABAC_ASSIGNMENT_PERMISSION_COLUMNS
from onetrust_synth.abac_tables import build_abac_assignment, build_abac_assignment_permission


def test_abac_assignment_has_all_columns_and_row_count(spark):
    df = build_abac_assignment(spark, 500)
    assert df.count() == 500
    assert set(ABAC_ASSIGNMENT_COLUMNS) == set(df.columns)


def test_abac_assignment_object_type_from_real_vocabulary(spark):
    df = build_abac_assignment(spark, 500)
    types = {r["objectType"] for r in df.select("objectType").distinct().collect()}
    assert "ASSESSMENT" in types


def test_abac_assignment_id_is_unique_long(spark):
    df = build_abac_assignment(spark, 500)
    ids = [r["id"] for r in df.select("id").collect()]
    assert len(ids) == len(set(ids))
    assert df.schema["id"].dataType.typeName() == "long"


def test_abac_assignment_permission_references_real_assignment_ids(spark):
    assignments = build_abac_assignment(spark, 200)
    perms = build_abac_assignment_permission(spark, assignments, 2000)
    assert perms.count() == 2000
    assert set(ABAC_ASSIGNMENT_PERMISSION_COLUMNS) == set(perms.columns)
    valid_ids = {r["id"] for r in assignments.select("id").collect()}
    perm_ids = {r["assignmentId"] for r in perms.select("assignmentId").collect()}
    assert perm_ids <= valid_ids  # every permission references a real assignment
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_abac_tables.py -v`
Expected: FAIL with `ModuleNotFoundError: No module named 'onetrust_synth.abac_tables'`

- [ ] **Step 3: Write the Assignment/AssignmentPermission generators**

```python
# onetrust_synth/abac_tables.py
from pyspark.sql import functions as F
from pyspark.sql import SparkSession, DataFrame, Window

from onetrust_synth.generator import base_row_id_df, add_categorical_column, deterministic_index
from onetrust_synth.sample_csv import load_entity_type_reference_values

_STATIC_IDENTIFIERS = ["owner", "viewer", "internal-owner"]
_ASSIGNMENT_PERMISSION_SUFFIXES = ["basic.view", "advanced.view"]


def _entity_type_pool() -> list:
    return sorted({t for _, t in load_entity_type_reference_values()})


def build_abac_assignment(spark: SparkSession, row_count: int) -> DataFrame:
    df = base_row_id_df(spark, row_count)
    df = df.withColumn("id", F.col("_row_id"))  # long, unique by construction
    df = df.withColumn("guid", F.expr("uuid()"))
    df = add_categorical_column(df, "staticIdentifier", _STATIC_IDENTIFIERS, salt="assignment.staticIdentifier")
    df = df.withColumn("name", F.initcap(F.col("staticIdentifier")))
    df = add_categorical_column(df, "objectType", _entity_type_pool(), salt="assignment.objectType")
    df = df.withColumn("sourceType", F.lit("SYSTEM"))
    df = add_categorical_column(df, "isActive", [True, False], null_rate=0.0, salt="assignment.isActive")
    df = df.withColumn("createdBy", F.lit("synthetic-generator"))
    df = df.withColumn("createDT", F.to_timestamp(F.lit("2026-03-17 00:00:00")))
    df = df.withColumn("updatedBy", F.lit("synthetic-generator"))
    df = df.withColumn("updateDT", F.to_timestamp(F.lit("2026-04-01 00:00:00")))
    df = df.withColumn("eventTime", F.col("updateDT"))
    df = df.withColumn("recModifiedTime", F.col("updateDT"))
    df = df.withColumn("tenantHash", F.lit("e40yx52dkbjpcqazimno9yvh4k"))
    # isDeleted should be rare (~5%), not an even split — pmod < 1 out of 20 buckets
    del_marker = F.pmod(F.xxhash64(F.col("_row_id"), F.lit("assignment.isDeleted_rare")), F.lit(20))
    df = df.withColumn("isDeleted", del_marker < 1)
    return df.drop("_row_id")


def build_abac_assignment_permission(spark: SparkSession, assignment_df: DataFrame, row_count: int) -> DataFrame:
    assignment_ids = assignment_df.select("id", "objectType").withColumnRenamed("id", "assignmentId")
    df = base_row_id_df(spark, row_count)

    idx = deterministic_index(F.col("_row_id"), "perm.assignment_pick", assignment_ids.count())
    indexed_assignments = assignment_ids.withColumn(
        "_pick_idx", F.row_number().over(Window.orderBy("assignmentId")) - 1
    )
    df = df.withColumn("_pick_idx", idx).join(indexed_assignments, on="_pick_idx", how="inner").drop("_pick_idx")

    df = add_categorical_column(df, "_suffix", _ASSIGNMENT_PERMISSION_SUFFIXES, salt="perm.suffix")
    df = df.withColumn("name", F.concat(F.lower(F.col("objectType")), F.lit(".fields."), F.col("_suffix"))).drop("_suffix", "objectType")
    df = df.withColumn("createdBy", F.lit("synthetic-generator"))
    df = df.withColumn("createDT", F.to_timestamp(F.lit("2026-03-17 00:00:00")))
    df = df.withColumn("updatedBy", F.lit("synthetic-generator"))
    df = df.withColumn("updateDT", F.to_timestamp(F.lit("2026-04-01 00:00:00")))
    df = df.withColumn("eventTime", F.col("updateDT"))
    df = df.withColumn("recModifiedTime", F.col("updateDT"))
    df = df.withColumn("tenantHash", F.lit("e40yx52dkbjpcqazimno9yvh4k"))
    df = df.withColumn("isDeleted", F.lit(False))
    return df.drop("_row_id")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_abac_tables.py -v`
Expected: PASS (4 tests)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/abac_tables.py onetrust_synth/tests/test_abac_tables.py
git commit -m "feat(onetrust_synth): generate ABAC_Assignment and ABAC_AssignmentPermission"
```

---

## Task 14: ABAC_EntitySubjectAssignment (the big one — FK-consistent via registries)

**Files:**
- Modify: `onetrust_synth/abac_tables.py`
- Modify: `onetrust_synth/tests/test_abac_tables.py`

**Interfaces:**
- Consumes: `abac_schema.ABAC_ENTITY_SUBJECT_ASSIGNMENT_COLUMNS` (Task 12), `entity_registry`/`org_registry`/`subject_registry` DataFrames (Tasks 10–11), `ABAC_Assignment` DataFrame (Task 13)
- Produces: `build_abac_entity_subject_assignment(spark, assignment_df, entity_registry, org_registry, subject_registry, row_count: int) -> DataFrame` — every row's `entityId`/`objectType` pair comes from `entity_registry` (so it's a real governed entity, not a random UUID), `assignmentId` from `assignment_df` filtered to the matching `objectType`, `subjectId`/`subjectType` from `subject_registry`, `entityOrganizationId` from `org_registry`.

- [ ] **Step 1: Write the failing test**

```python
# append to onetrust_synth/tests/test_abac_tables.py
from onetrust_synth.abac_schema import ABAC_ENTITY_SUBJECT_ASSIGNMENT_COLUMNS
from onetrust_synth.abac_tables import build_abac_entity_subject_assignment
from onetrust_synth.registries import build_org_registry, build_subject_registry, build_entity_registry
from onetrust_synth.generate_main_tables import build_all_main_tables


def test_esa_row_count_and_columns(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    entity_reg = build_entity_registry(spark, main_tables)
    org_reg = build_org_registry(spark)
    subj_reg = build_subject_registry(spark)
    assignments = build_abac_assignment(spark, 200)

    esa = build_abac_entity_subject_assignment(spark, assignments, entity_reg, org_reg, subj_reg, 5000)
    assert esa.count() == 5000
    assert set(ABAC_ENTITY_SUBJECT_ASSIGNMENT_COLUMNS) == set(esa.columns)


def test_esa_entity_id_and_object_type_are_from_entity_registry(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    entity_reg = build_entity_registry(spark, main_tables)
    org_reg = build_org_registry(spark)
    subj_reg = build_subject_registry(spark)
    assignments = build_abac_assignment(spark, 200)

    esa = build_abac_entity_subject_assignment(spark, assignments, entity_reg, org_reg, subj_reg, 3000)
    valid_pairs = {(r["entityId"], r["objectType"]) for r in entity_reg.select("entityId", "objectType").collect()}
    esa_pairs = {(r["entityId"], r["objectType"]) for r in esa.select("entityId", "objectType").collect()}
    assert esa_pairs <= valid_pairs


def test_esa_subject_type_matches_subject_registry(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    entity_reg = build_entity_registry(spark, main_tables)
    org_reg = build_org_registry(spark)
    subj_reg = build_subject_registry(spark)
    assignments = build_abac_assignment(spark, 200)

    esa = build_abac_entity_subject_assignment(spark, assignments, entity_reg, org_reg, subj_reg, 3000)
    types = {r["subjectType"] for r in esa.select("subjectType").distinct().collect()}
    assert types <= {"USER_ID", "USER_GROUP"}


def test_esa_assignment_id_object_type_matches_the_assignment(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    entity_reg = build_entity_registry(spark, main_tables)
    org_reg = build_org_registry(spark)
    subj_reg = build_subject_registry(spark)
    assignments = build_abac_assignment(spark, 200)

    esa = build_abac_entity_subject_assignment(spark, assignments, entity_reg, org_reg, subj_reg, 3000)
    joined = esa.join(
        assignments.select(F.col("id").alias("assignmentId"), F.col("objectType").alias("a_objectType")),
        on="assignmentId",
    )
    mismatches = joined.filter(F.col("objectType") != F.col("a_objectType")).count()
    assert mismatches == 0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_abac_tables.py -v -k esa`
Expected: FAIL with `ImportError: cannot import name 'build_abac_entity_subject_assignment'`

- [ ] **Step 3: Add ESA generator to abac_tables.py**

```python
# append to onetrust_synth/abac_tables.py
# (Window already imported at the top of this file — see Task 13)


def build_abac_entity_subject_assignment(
    spark: SparkSession, assignment_df: DataFrame, entity_registry: DataFrame,
    org_registry: DataFrame, subject_registry: DataFrame, row_count: int,
) -> DataFrame:
    entity_reg_indexed = entity_registry.withColumn(
        "_e_idx", F.row_number().over(Window.orderBy("entityId")) - 1
    )
    n_entities = entity_registry.count()

    subj_reg_indexed = subject_registry.withColumn(
        "_s_idx", F.row_number().over(Window.orderBy("subjectId")) - 1
    )
    n_subjects = subject_registry.count()

    org_ids = [r["orgId"] for r in org_registry.select("orgId").distinct().collect()]

    df = base_row_id_df(spark, row_count)
    df = df.withColumn("_e_idx", deterministic_index(F.col("_row_id"), "esa.entity", n_entities))
    df = df.join(entity_reg_indexed, on="_e_idx", how="inner").drop("_e_idx")

    df = df.withColumn("_s_idx", deterministic_index(F.col("_row_id"), "esa.subject", n_subjects))
    df = df.join(subj_reg_indexed.withColumnRenamed("subjectId", "_subjectId").withColumnRenamed("subjectType", "_subjectType"), on="_s_idx", how="inner").drop("_s_idx")
    df = df.withColumnRenamed("_subjectId", "subjectId").withColumnRenamed("_subjectType", "subjectType")

    # pick an assignment whose objectType matches this row's entity objectType
    assignment_by_type = assignment_df.select(F.col("id").alias("assignmentId"), "objectType")
    df = df.join(assignment_by_type, on="objectType", how="inner")
    # a broadcast join on objectType can multiply rows if several assignments share
    # a type; pick one deterministically per source row instead of keeping all matches
    df = df.withColumn(
        "_pick",
        F.row_number().over(Window.partitionBy("_row_id").orderBy(F.xxhash64(F.col("_row_id"), F.col("assignmentId")))),
    )
    df = df.filter(F.col("_pick") == 1).drop("_pick")

    org_array = F.array(*[F.lit(o) for o in org_ids]) if org_ids else F.array(F.lit(None).cast("string"))
    org_idx = deterministic_index(F.col("_row_id"), "esa.org", max(len(org_ids), 1))
    df = df.withColumn("entityOrganizationId", F.element_at(org_array, (org_idx + F.lit(1)).cast("int")))

    df = df.withColumn("policyId", F.lit(None).cast("long"))
    df = df.withColumn("updateDT", F.to_timestamp(F.lit("2026-04-01 00:00:00")))
    df = df.withColumn("eventTime", F.col("updateDT"))
    df = df.withColumn("recModifiedTime", F.col("updateDT"))
    df = df.withColumn("tenantHash", F.lit("e40yx52dkbjpcqazimno9yvh4k"))
    del_marker = F.pmod(F.xxhash64(F.col("_row_id"), F.lit("esa.isDeleted_rare")), F.lit(20))
    df = df.withColumn("isDeleted", del_marker < 1)

    return df.drop("_row_id", "orgId")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_abac_tables.py -v`
Expected: PASS (8 tests total)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/abac_tables.py onetrust_synth/tests/test_abac_tables.py
git commit -m "feat(onetrust_synth): generate FK-consistent ABAC_EntitySubjectAssignment"
```

---

## Task 15: UserGroupMembers + OrgHierarchyBase/OrgHierarchy

**Files:**
- Modify: `onetrust_synth/abac_tables.py`
- Modify: `onetrust_synth/tests/test_abac_tables.py`

**Interfaces:**
- Consumes: `abac_schema.USER_GROUP_MEMBERS_COLUMNS`/`ORG_HIERARCHY_BASE_COLUMNS` (Task 12), `subject_registry` (Task 10), `verbatim_tables.build_orghierarchy_df` (Task 7)
- Produces: `build_user_group_members(spark, subject_registry: DataFrame, row_count: int) -> DataFrame`, `build_org_hierarchy_base(spark) -> DataFrame`, `build_org_hierarchy_view_sql() -> str` (the `CREATE OR REPLACE VIEW` statement text, consumed by Task 17's SQL)

- [ ] **Step 1: Write the failing test**

```python
# append to onetrust_synth/tests/test_abac_tables.py
from onetrust_synth.abac_schema import USER_GROUP_MEMBERS_COLUMNS, ORG_HIERARCHY_BASE_COLUMNS
from onetrust_synth.abac_tables import build_user_group_members, build_org_hierarchy_base
from onetrust_synth.registries import build_subject_registry


def test_user_group_members_row_count_and_columns(spark):
    subj_reg = build_subject_registry(spark)
    ugm = build_user_group_members(spark, subj_reg, 5000)
    # build_user_group_members dedupes (memberId, groupId) pairs, so with 2000 users x
    # 300 groups sampled 5000 times, a small number of hash collisions are expected —
    # count comes in slightly under 5000, not exactly 5000.
    assert 4800 <= ugm.count() <= 5000
    assert set(USER_GROUP_MEMBERS_COLUMNS) == set(ugm.columns)


def test_user_group_members_references_real_subjects(spark):
    subj_reg = build_subject_registry(spark)
    ugm = build_user_group_members(spark, subj_reg, 3000)
    valid_members = {r["subjectId"] for r in subj_reg.filter(subj_reg.subjectType == "USER_ID").select("subjectId").collect()}
    valid_groups = {r["subjectId"] for r in subj_reg.filter(subj_reg.subjectType == "USER_GROUP").select("subjectId").collect()}
    member_ids = {r["memberId"] for r in ugm.select("memberId").collect()}
    group_ids = {r["groupId"] for r in ugm.select("groupId").collect()}
    assert member_ids <= valid_members
    assert group_ids <= valid_groups


def test_org_hierarchy_base_matches_real_data(spark):
    base = build_org_hierarchy_base(spark)
    assert base.count() == 183
    assert set(ORG_HIERARCHY_BASE_COLUMNS) == set(base.columns)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_abac_tables.py -v -k "group_members or hierarchy_base"`
Expected: FAIL with `ImportError`

- [ ] **Step 3: Add UserGroupMembers/OrgHierarchyBase generators**

```python
# append to onetrust_synth/abac_tables.py
from onetrust_synth.verbatim_tables import build_orghierarchy_df


def build_user_group_members(spark: SparkSession, subject_registry: DataFrame, row_count: int) -> DataFrame:
    users = subject_registry.filter(subject_registry.subjectType == "USER_ID").select(
        F.col("subjectId").alias("memberId")
    ).withColumn("_u_idx", F.row_number().over(Window.orderBy("memberId")) - 1)
    n_users = users.count()

    groups = subject_registry.filter(subject_registry.subjectType == "USER_GROUP").select(
        F.col("subjectId").alias("groupId")
    ).withColumn("_g_idx", F.row_number().over(Window.orderBy("groupId")) - 1)
    n_groups = groups.count()

    df = base_row_id_df(spark, row_count)
    df = df.withColumn("_u_idx", deterministic_index(F.col("_row_id"), "ugm.member", n_users))
    df = df.join(users, on="_u_idx", how="inner").drop("_u_idx")
    df = df.withColumn("_g_idx", deterministic_index(F.col("_row_id"), "ugm.group", n_groups))
    df = df.join(groups, on="_g_idx", how="inner").drop("_g_idx")

    df = df.withColumn("eventTime", F.to_timestamp(F.lit("2026-04-01 00:00:00")))
    df = df.withColumn("recModifiedTime", F.col("eventTime"))
    df = df.withColumn("isDeleted", F.lit(False))
    df = df.withColumn("tenantHash", F.lit("e40yx52dkbjpcqazimno9yvh4k"))
    return df.drop("_row_id").dropDuplicates(["memberId", "groupId"])


def build_org_hierarchy_base(spark: SparkSession) -> DataFrame:
    return build_orghierarchy_df(spark)


def build_org_hierarchy_view_sql() -> str:
    from onetrust_synth import config
    return (
        f"CREATE OR REPLACE VIEW {config.CATALOG}.{config.MAIN_SCHEMA}.OrgHierarchy AS "
        f"SELECT * FROM {config.CATALOG}.{config.MAIN_SCHEMA}.OrgHierarchyBase "
        f"WHERE isDeleted IS NOT TRUE"
    )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_abac_tables.py -v`
Expected: PASS (11 tests total)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/abac_tables.py onetrust_synth/tests/test_abac_tables.py
git commit -m "feat(onetrust_synth): generate UserGroupMembers and OrgHierarchyBase"
```

---

## Task 16: ABAC orchestration script + validation gate

**Files:**
- Create: `onetrust_synth/generate_abac_tables.py`
- Create: `onetrust_synth/validate.py`
- Test: `onetrust_synth/tests/test_generate_abac_tables.py`
- Test: `onetrust_synth/tests/test_validate.py`

**Interfaces:**
- Consumes: everything from Tasks 10–15, `write.write_delta_table` (Task 8), `config.ABAC_PARTITIONED_TABLES`/`ABAC_TABLE_ROW_TARGETS` (Task 1)
- Produces: `build_all_abac_tables(spark, main_tables: dict) -> dict[str, DataFrame]`, a `main()` entry point; `validate_referential_integrity(esa_df, entity_registry, subject_registry, assignment_df) -> dict` (returns match-rate fractions), `validate_row_counts(built_tables: dict[str, int], targets: dict[str, int], tolerance: float = 0.05) -> list[str]` (returns a list of failure messages, empty if all pass)

- [ ] **Step 1: Write the failing tests**

```python
# onetrust_synth/tests/test_generate_abac_tables.py
from onetrust_synth import config
from onetrust_synth.generate_main_tables import build_all_main_tables
from onetrust_synth.generate_abac_tables import build_all_abac_tables


def test_builds_all_5_abac_tables(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    abac_tables = build_all_abac_tables(spark, main_tables)
    assert set(abac_tables.keys()) == set(config.ABAC_TABLE_ROW_TARGETS.keys())


def test_abac_table_row_counts_match_phase1_targets(spark):
    from onetrust_synth.validate import validate_row_counts

    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    abac_tables = build_all_abac_tables(spark, main_tables)
    built = {table: df.count() for table, df in abac_tables.items()}
    # UserGroupMembers dedupes (memberId, groupId) pairs, so it can land slightly
    # under target — validate_row_counts' default 5% tolerance covers that; every
    # other table hits its target exactly.
    failures = validate_row_counts(built, config.ABAC_TABLE_ROW_TARGETS, tolerance=0.05)
    assert failures == []
```

```python
# onetrust_synth/tests/test_validate.py
from onetrust_synth.validate import validate_row_counts, validate_referential_integrity
from onetrust_synth.generate_main_tables import build_all_main_tables
from onetrust_synth.generate_abac_tables import build_all_abac_tables
from onetrust_synth.registries import build_entity_registry, build_subject_registry
from onetrust_synth import config


def test_validate_row_counts_passes_on_exact_match():
    built = {"a": 100, "b": 200}
    targets = {"a": 100, "b": 200}
    assert validate_row_counts(built, targets) == []


def test_validate_row_counts_flags_mismatch_beyond_tolerance():
    built = {"a": 50}
    targets = {"a": 100}
    failures = validate_row_counts(built, targets, tolerance=0.05)
    assert len(failures) == 1
    assert "a" in failures[0]


def test_validate_referential_integrity_reports_full_match(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    entity_reg = build_entity_registry(spark, main_tables)
    subj_reg = build_subject_registry(spark)
    abac_tables = build_all_abac_tables(spark, main_tables)

    report = validate_referential_integrity(
        abac_tables["ABAC_EntitySubjectAssignment"], entity_reg, subj_reg, abac_tables["ABAC_Assignment"],
    )
    assert report["entity_match_rate"] == 1.0
    assert report["subject_match_rate"] == 1.0
    assert report["assignment_match_rate"] == 1.0
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_generate_abac_tables.py onetrust_synth/tests/test_validate.py -v`
Expected: FAIL with `ModuleNotFoundError`

- [ ] **Step 3: Write the ABAC orchestration and validation modules**

```python
# onetrust_synth/generate_abac_tables.py
from pyspark.sql import SparkSession

from onetrust_synth import config
from onetrust_synth.registries import build_org_registry, build_subject_registry, build_entity_registry
from onetrust_synth.abac_tables import (
    build_abac_assignment, build_abac_assignment_permission,
    build_abac_entity_subject_assignment, build_user_group_members, build_org_hierarchy_base,
    build_org_hierarchy_view_sql,
)
from onetrust_synth.write import write_delta_table


def build_all_abac_tables(spark: SparkSession, main_tables: dict) -> dict:
    entity_registry = build_entity_registry(spark, main_tables)
    org_registry = build_org_registry(spark)
    subject_registry = build_subject_registry(spark)

    assignment = build_abac_assignment(spark, config.ABAC_TABLE_ROW_TARGETS["ABAC_Assignment"])
    assignment_permission = build_abac_assignment_permission(
        spark, assignment, config.ABAC_TABLE_ROW_TARGETS["ABAC_AssignmentPermission"]
    )
    esa = build_abac_entity_subject_assignment(
        spark, assignment, entity_registry, org_registry, subject_registry,
        config.ABAC_TABLE_ROW_TARGETS["ABAC_EntitySubjectAssignment"],
    )
    user_group_members = build_user_group_members(spark, subject_registry, config.ABAC_TABLE_ROW_TARGETS["UserGroupMembers"])
    org_hierarchy_base = build_org_hierarchy_base(spark)

    return {
        "ABAC_Assignment": assignment,
        "ABAC_AssignmentPermission": assignment_permission,
        "ABAC_EntitySubjectAssignment": esa,
        "UserGroupMembers": user_group_members,
        "OrgHierarchy": org_hierarchy_base,  # written as OrgHierarchyBase; view created separately, see Task 17
    }


def main():
    from onetrust_synth.generate_main_tables import build_all_main_tables

    spark = SparkSession.builder.appName("onetrust_synth-abac-tables").getOrCreate()
    main_tables = build_all_main_tables(spark, scale_factor=config.SCALE_FACTOR_DEFAULT)
    abac_tables = build_all_abac_tables(spark, main_tables)

    for table_name, df in abac_tables.items():
        write_table_name = "OrgHierarchyBase" if table_name == "OrgHierarchy" else table_name
        partition_by = ["objectType"] if table_name in config.ABAC_PARTITIONED_TABLES else None
        write_delta_table(df, config.CATALOG, config.MAIN_SCHEMA, write_table_name, partition_by=partition_by)
        print(f"Wrote {config.CATALOG}.{config.MAIN_SCHEMA}.{write_table_name}: {df.count()} rows")

    # OrgHierarchyBase is the physical table written above; OrgHierarchy is a view
    # over it (matches the real DDL — see abac_docs/customer_data/OrgHierarchy.rtf).
    # Task 17's row-filter UDF reads OrgHierarchy (the view), so this must run before
    # that UDF is ever invoked.
    spark.sql(build_org_hierarchy_view_sql())
    print(f"Created view {config.CATALOG}.{config.MAIN_SCHEMA}.OrgHierarchy over OrgHierarchyBase")


if __name__ == "__main__":
    main()
```

```python
# onetrust_synth/validate.py
from pyspark.sql import DataFrame


def validate_row_counts(built: dict, targets: dict, tolerance: float = 0.05) -> list:
    failures = []
    for table, target in targets.items():
        actual = built.get(table)
        if actual is None:
            failures.append(f"{table}: missing from built tables")
            continue
        if target == 0:
            if actual != 0:
                failures.append(f"{table}: expected 0 rows, got {actual}")
            continue
        deviation = abs(actual - target) / target
        if deviation > tolerance:
            failures.append(f"{table}: expected ~{target}, got {actual} ({deviation:.1%} off)")
    return failures


def validate_referential_integrity(esa_df: DataFrame, entity_registry: DataFrame, subject_registry: DataFrame, assignment_df: DataFrame) -> dict:
    total = esa_df.count()
    if total == 0:
        return {"entity_match_rate": 1.0, "subject_match_rate": 1.0, "assignment_match_rate": 1.0}

    entity_matches = esa_df.join(
        entity_registry.select("entityId", "objectType"), on=["entityId", "objectType"], how="inner"
    ).count()
    subject_matches = esa_df.join(
        subject_registry.select("subjectId", "subjectType"), on=["subjectId", "subjectType"], how="inner"
    ).count()
    assignment_matches = esa_df.join(
        assignment_df.select(assignment_df.id.alias("assignmentId")), on="assignmentId", how="inner"
    ).count()

    return {
        "entity_match_rate": entity_matches / total,
        "subject_match_rate": subject_matches / total,
        "assignment_match_rate": assignment_matches / total,
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_generate_abac_tables.py onetrust_synth/tests/test_validate.py -v`
Expected: PASS (5 tests)

- [ ] **Step 5: Commit**

```bash
git add onetrust_synth/generate_abac_tables.py onetrust_synth/validate.py onetrust_synth/tests/test_generate_abac_tables.py onetrust_synth/tests/test_validate.py
git commit -m "feat(onetrust_synth): orchestrate ABAC table generation + add validation gate"
```

---

## Task 17: SQL — catalog/schema, tags, row-filter UDFs

**Files:**
- Create: `sql_onetrust/01_catalog_schema.sql`
- Create: `sql_onetrust/02_tags.sql`
- Create: `sql_onetrust/03_row_filter_udfs.sql`

These are run manually on Databricks (Unity Catalog features have no local equivalent to unit test). Each step documents the expected output to check for, following the existing repo's `sql/` convention.

- [ ] **Step 1: Write catalog/schema creation**

```sql
-- sql_onetrust/01_catalog_schema.sql
-- Run as workspace/catalog admin.
CREATE CATALOG IF NOT EXISTS abac_onetrust;
CREATE SCHEMA IF NOT EXISTS abac_onetrust.onetrust_sim;
CREATE SCHEMA IF NOT EXISTS abac_onetrust.monitoring;

SHOW SCHEMAS IN abac_onetrust;
```

Run on Databricks SQL editor. Expected: `SHOW SCHEMAS` lists `onetrust_sim` and `monitoring`.

- [ ] **Step 2: Write governed tags**

```sql
-- sql_onetrust/02_tags.sql   (RUN AFTER Task 9/16's Python scripts have written the tables)
-- Governed tag KEYS (abac_column_id, abac_column_org, abac_column_type) must already
-- exist — created once via Settings > Catalog > Governed tags, same as the TPC-DS POC
-- (see sql/07_tags.sql for precedent). Phase 1 tags only the 4 tables getting a policy
-- in Task 18 (see design doc section 4 / this plan's Global Constraints for why the
-- other 7 main tables are out of scope for Phase 1 policy wiring).

-- cmb_assessment: single type 'ASSESSMENT' (no_type policy shape) — id only
ALTER TABLE abac_onetrust.onetrust_sim.cmb_assessment ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

-- cmb_controlimplementation: single type 'CONTROL' (no_type policy shape) — id only
ALTER TABLE abac_onetrust.onetrust_sim.cmb_controlimplementation ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

-- cmb_template: single type 'TEMPLATE' (no_type policy shape) — id only
ALTER TABLE abac_onetrust.onetrust_sim.cmb_template ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

-- cmb_v_inventoryaggregatedrisksummary: per-row type via inventoryType (default/tagged-type
-- policy shape) — id, type, AND org (orgID exists on this table, unlike the other three)
ALTER TABLE abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary ALTER COLUMN entityID SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary ALTER COLUMN inventoryType SET TAGS ('abac_column_type' = 'true');
ALTER TABLE abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary ALTER COLUMN orgID SET TAGS ('abac_column_org' = 'true');
```

Run on Databricks SQL editor. Expected: no error (governed tag keys must be pre-created via the UI first, per the comment — if `has_tag`/`SET TAGS` errors with an unknown tag key, create the 3 keys via Settings > Catalog > Governed tags before re-running).

- [ ] **Step 3: Write row-filter UDFs (adapted from sql/04-05)**

```sql
-- sql_onetrust/03_row_filter_udfs.sql
-- Same customer semantics as sql/04_helper_udfs.sql + sql/05_dataset_udfs.sql,
-- pointed at abac_onetrust and the real OneTrust column names (entityId/subjectId/
-- assignmentId/objectType, camelCase per the RTF DDL — see design doc section 4).

CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
COMMENT 'Deterministic ABAC context for Phase 1 test-case validation'
RETURN named_struct(
  'tenant',      1,
  'user',        'u.assessment.owner@example.com',
  'org',         '100',
  'mode',        'ABAC',
  'root',        'ASSESSMENT',
  'permissions', array('TEMPLATE')
);

CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.entity_type_to_object_type(entity_type STRING)
RETURNS STRING
COMMENT 'Normalizes a raw table/column type value to the canonical ABAC object type. NOT
a plain upper() -- "Processing Activities" hyphenates to "PROCESSING-ACTIVITIES" in the
real entityTypeReference vocabulary (verified against real sample data; see
onetrust_synth/config.py INVENTORY_TYPE_TO_OBJECT_TYPE for the Python-side source of truth).'
RETURN CASE
  WHEN upper(entity_type) = 'PROCESSING ACTIVITIES' THEN 'PROCESSING-ACTIVITIES'
  ELSE upper(entity_type)
END;

CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.abac_row_filter(
  entity_id   STRING,
  object_type STRING,
  org_id      STRING,
  ctx STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
)
RETURNS BOOLEAN
RETURN (
  ctx.mode = 'DISABLE'
  OR (
    ctx.root <> object_type
    AND array_contains(ctx.permissions, object_type)
  )
  OR (
    ctx.root = object_type
    AND (
      (
        ctx.mode = 'RBAC_ABAC'
        AND org_id IN (
          SELECT orgId FROM abac_onetrust.onetrust_sim.OrgHierarchy
          WHERE parentOrgId = ctx.org
        )
      )
      OR EXISTS (
        SELECT 1
        FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment esa
        JOIN abac_onetrust.onetrust_sim.ABAC_Assignment a
          ON esa.assignmentId = a.id
          AND a.isActive
          AND a.isDeleted = false
        LEFT JOIN abac_onetrust.onetrust_sim.UserGroupMembers ugm
          ON esa.subjectType = 'USER_GROUP'
          AND esa.subjectId = ugm.groupId
          AND ugm.memberId = ctx.user
          AND ugm.isDeleted = false
        WHERE esa.isDeleted = false
          AND esa.entityId = entity_id
          AND esa.objectType = object_type
          AND (
            ugm.memberId IS NOT NULL
            OR (esa.subjectType = 'USER_ID' AND esa.subjectId = ctx.user)
          )
      )
    )
  )
);

CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.abac_row_filter_wrapper(
  entity_id STRING, object_type STRING, org_id STRING
)
RETURNS BOOLEAN
RETURN abac_onetrust.onetrust_sim.abac_row_filter(
  entity_id, abac_onetrust.onetrust_sim.entity_type_to_object_type(object_type), org_id,
  abac_onetrust.onetrust_sim.get_test_user_context()
);
```

Run on Databricks SQL editor. Expected: all 4 `CREATE OR REPLACE FUNCTION` statements succeed with no error.

- [ ] **Step 4: Commit**

```bash
git add sql_onetrust/01_catalog_schema.sql sql_onetrust/02_tags.sql sql_onetrust/03_row_filter_udfs.sql
git commit -m "feat(sql_onetrust): add catalog/schema, tags, and row-filter UDFs for Phase 1"
```

---

## Task 18: SQL — row-filter policies

**Files:**
- Create: `sql_onetrust/04_policies.sql`

- [ ] **Step 1: Write the policy statements (adapted from sql/08)**

```sql
-- sql_onetrust/04_policies.sql   (requires ABAC enabled + tags from 02_tags.sql)
-- Same pattern as sql/08_policies_row_filter.sql. TO clause: replace
-- `<SERVICE_PRINCIPAL>` with the real service principal application id before running
-- (see docs/deployment/runbook.md for how the TPC-DS POC resolved this).

CREATE OR REPLACE POLICY onetrust_sim_cmb_assessment_abac_policy
ON TABLE abac_onetrust.onetrust_sim.cmb_assessment
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper
TO `<SERVICE_PRINCIPAL>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id
USING COLUMNS (id, 'ASSESSMENT', '100');

CREATE OR REPLACE POLICY onetrust_sim_cmb_controlimplementation_abac_policy
ON TABLE abac_onetrust.onetrust_sim.cmb_controlimplementation
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper
TO `<SERVICE_PRINCIPAL>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id
USING COLUMNS (id, 'CONTROL', '100');

CREATE OR REPLACE POLICY onetrust_sim_cmb_template_abac_policy
ON TABLE abac_onetrust.onetrust_sim.cmb_template
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper
TO `<SERVICE_PRINCIPAL>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id
USING COLUMNS (id, 'TEMPLATE', '100');

-- cmb_v_inventoryaggregatedrisksummary: real per-row type + org columns — the
-- default/tagged-type shape (3 tags), not a literal.
CREATE OR REPLACE POLICY onetrust_sim_cmb_v_inventoryaggregatedrisksummary_abac_policy
ON TABLE abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper
TO `<SERVICE_PRINCIPAL>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_type') as type, has_tag('abac_column_org') as org
USING COLUMNS (id, type, org);

SHOW POLICIES ON SCHEMA abac_onetrust.onetrust_sim;
```

Run on Databricks SQL editor as owner (after substituting the real service principal). Expected: `SHOW POLICIES` lists all 4 policies.

- [ ] **Step 2: Commit**

```bash
git add sql_onetrust/04_policies.sql
git commit -m "feat(sql_onetrust): add row-filter policies for the 4 Phase 1 governed tables"
```

---

## Task 19: Seed 3 test principals + 8 basic ABAC test cases

**Files:**
- Create: `sql_onetrust/05_seed_test_principals.sql`
- Create: `sql_onetrust/06_test_cases.sql`

**Interfaces:**
- Consumes: the live `ABAC_Assignment`/`ABAC_EntitySubjectAssignment`/`UserGroupMembers` tables (Task 16), `get_test_user_context()`/`abac_row_filter_wrapper` (Task 17)

This seeds 3 deterministic test identities directly into the generated ABAC tables (on top of the synthetic bulk data — a small number of hand-picked rows the test cases can assert exact counts against, same pattern as `sql/03_seed_metadata.sql`), then runs 8 test cases in the style of `sql/06_validate_row_filter.sql`.

- [ ] **Step 1: Write the seed script**

```sql
-- sql_onetrust/05_seed_test_principals.sql   (re-runnable: deletes prior seed rows first, by name prefix)
-- Picks one real entity per governed table (from the already-generated bulk data)
-- and creates hand-authored assignment/subject rows so test cases can assert exact,
-- known outcomes — the bulk synthetic rows from Task 16 provide background noise/scale,
-- these seeded rows are the ground truth the test cases check.

DELETE FROM abac_onetrust.onetrust_sim.ABAC_Assignment WHERE staticIdentifier = 'phase1-test-seed';
DELETE FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-test-seed';
DELETE FROM abac_onetrust.onetrust_sim.UserGroupMembers WHERE tenantHash = 'phase1-test-seed';

-- one real cmb_assessment id and one real cmb_controlimplementation id, picked
-- from the already-generated data:
CREATE OR REPLACE TEMPORARY VIEW seed_assessment_entity AS
  SELECT id AS entity_id FROM abac_onetrust.onetrust_sim.cmb_assessment LIMIT 1;
CREATE OR REPLACE TEMPORARY VIEW seed_control_entity AS
  SELECT id AS entity_id FROM abac_onetrust.onetrust_sim.cmb_controlimplementation LIMIT 1;

-- assignment 900001: explicit grant on the seeded assessment to u.assessment.owner
INSERT INTO abac_onetrust.onetrust_sim.ABAC_Assignment
  (id, guid, staticIdentifier, name, objectType, sourceType, isActive, createdBy, createDT, updatedBy, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
VALUES
  (900001, uuid(), 'phase1-test-seed', 'Owner', 'ASSESSMENT', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false),
  -- 900002: an INACTIVE grant (test case 8 — must NOT grant visibility)
  (900002, uuid(), 'phase1-test-seed', 'Owner', 'ASSESSMENT', 'SYSTEM', false, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false),
  -- 900003: group grant on the seeded control to test_group_1
  (900003, uuid(), 'phase1-test-seed', 'Owner', 'CONTROL', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false);

INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900001, NULL, entity_id, NULL, 'u.assessment.owner@example.com', 'USER_ID', 'ASSESSMENT', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
FROM seed_assessment_entity
UNION ALL
SELECT 900002, NULL, entity_id, NULL, 'u.inactive.grant@example.com', 'USER_ID', 'ASSESSMENT', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
FROM seed_assessment_entity
UNION ALL
SELECT 900003, NULL, entity_id, NULL, 'test_group_1', 'USER_GROUP', 'CONTROL', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
FROM seed_control_entity;

INSERT INTO abac_onetrust.onetrust_sim.UserGroupMembers (memberId, groupId, eventTime, recModifiedTime, isDeleted, tenantHash)
VALUES ('u.group.member@example.com', 'test_group_1', current_timestamp(), current_timestamp(), false, 'phase1-test-seed');
```

Run on Databricks SQL editor. Expected: no error; `SELECT count(*) FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-test-seed'` returns 3.

- [ ] **Step 2: Write the 8 test cases**

```sql
-- sql_onetrust/06_test_cases.sql   (RUN AS OWNER — uses get_test_user_context, no policy needed)
-- get_test_user_context (03_row_filter_udfs.sql) returns:
--   user='u.assessment.owner@example.com', mode='ABAC', root='ASSESSMENT', permissions=['TEMPLATE']
-- Update the seeded entity ids below to match what 05_seed_test_principals.sql actually
-- picked (query seed_assessment_entity / seed_control_entity, or re-run 05 in the same
-- session so the temp views are live).

-- T1: root type, explicit assignment — the seeded assessment IS visible.
SELECT assert_true(count(*) = 1, 'T1 FAILED: seeded assessment should be visible')
FROM abac_onetrust.onetrust_sim.cmb_assessment
WHERE id = (SELECT entity_id FROM seed_assessment_entity)
  AND abac_onetrust.onetrust_sim.abac_row_filter_wrapper(id, 'ASSESSMENT', '100');

-- T2: root type, no assignment at all — a DIFFERENT assessment is NOT visible
-- (mode=ABAC means only explicit assignments show; picks any other real id).
SELECT assert_true(count(*) = 0, 'T2 FAILED: unassigned assessment should not be visible')
FROM abac_onetrust.onetrust_sim.cmb_assessment
WHERE id != (SELECT entity_id FROM seed_assessment_entity)
  AND id NOT IN (SELECT entityId FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE subjectId = 'u.assessment.owner@example.com')
  AND abac_onetrust.onetrust_sim.abac_row_filter_wrapper(id, 'ASSESSMENT', '100')
LIMIT 1;

-- T3: non-root type, IN permissions array — ALL cmb_template rows visible.
SELECT
  assert_true(
    (SELECT count(*) FROM abac_onetrust.onetrust_sim.cmb_template) =
    (SELECT count(*) FROM abac_onetrust.onetrust_sim.cmb_template WHERE abac_onetrust.onetrust_sim.abac_row_filter_wrapper(id, 'TEMPLATE', '100')),
    'T3 FAILED: all templates should be visible (non-root, in permissions)'
  );

-- T4: non-root type, NOT in permissions array — ZERO cmb_controlimplementation rows
-- visible under the ABAC-owner context (root=ASSESSMENT, permissions=[TEMPLATE] only).
SELECT assert_true(count(*) = 0, 'T4 FAILED: controls should not be visible (non-root, not in permissions)')
FROM abac_onetrust.onetrust_sim.cmb_controlimplementation
WHERE abac_onetrust.onetrust_sim.abac_row_filter_wrapper(id, 'CONTROL', '100');

-- T5: group membership — a user who is a MEMBER of test_group_1 (which owns the
-- seeded control) sees it, via a context override.
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context_group_member()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
RETURN named_struct('tenant', 1, 'user', 'u.group.member@example.com', 'org', '100', 'mode', 'ABAC', 'root', 'CONTROL', 'permissions', array());

SELECT assert_true(count(*) = 1, 'T5 FAILED: group member should see the group-assigned control')
FROM abac_onetrust.onetrust_sim.cmb_controlimplementation
WHERE id = (SELECT entity_id FROM seed_control_entity)
  AND abac_onetrust.onetrust_sim.abac_row_filter(
        id, 'CONTROL', '100', abac_onetrust.onetrust_sim.get_test_user_context_group_member());

-- T6: isActive=false assignment — u.inactive.grant has an ESA row but the linked
-- Assignment (900002) has isActive=false, so it must NOT grant visibility.
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context_inactive()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
RETURN named_struct('tenant', 1, 'user', 'u.inactive.grant@example.com', 'org', '100', 'mode', 'ABAC', 'root', 'ASSESSMENT', 'permissions', array());

SELECT assert_true(count(*) = 0, 'T6 FAILED: an isActive=false assignment must not grant visibility')
FROM abac_onetrust.onetrust_sim.cmb_assessment
WHERE id = (SELECT entity_id FROM seed_assessment_entity)
  AND abac_onetrust.onetrust_sim.abac_row_filter(
        id, 'ASSESSMENT', '100', abac_onetrust.onetrust_sim.get_test_user_context_inactive());

-- T7: DISABLE mode — everything visible regardless of assignments.
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context_disabled()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
RETURN named_struct('tenant', 1, 'user', 'u.disabled.mode@example.com', 'org', '100', 'mode', 'DISABLE', 'root', 'ASSESSMENT', 'permissions', array());

SELECT
  assert_true(
    (SELECT count(*) FROM abac_onetrust.onetrust_sim.cmb_assessment) =
    (SELECT count(*) FROM abac_onetrust.onetrust_sim.cmb_assessment
     WHERE abac_onetrust.onetrust_sim.abac_row_filter(id, 'ASSESSMENT', '100', abac_onetrust.onetrust_sim.get_test_user_context_disabled())),
    'T7 FAILED: DISABLE mode should show every row'
  );

-- T8: RBAC_ABAC mode over the real orgHierarchy ancestor closure — a user with
-- root=CONTROL and org = one of the 68 real orgIds sees the tagged-type
-- cmb_v_inventoryaggregatedrisksummary rows whose org is in their subtree (proves
-- the real profiled orgHierarchy data, not a fabricated tree, drives the result).
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.get_test_user_context_rbac()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
RETURN named_struct(
  'tenant', 1, 'user', 'u.rbac.viewer@example.com', 'org',
  (SELECT orgID FROM abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary LIMIT 1),
  'mode', 'RBAC_ABAC', 'root', 'ASSETS', 'permissions', array()
);

SELECT assert_true(count(*) >= 1, 'T8 FAILED: RBAC_ABAC org-subtree row should be visible for at least the seeded org itself')
FROM abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary
WHERE upper(inventoryType) = 'ASSETS'
  AND abac_onetrust.onetrust_sim.abac_row_filter(
        entityID, 'ASSETS', orgID, abac_onetrust.onetrust_sim.get_test_user_context_rbac());
```

Run on Databricks SQL editor, statement by statement. Expected: every `assert_true` statement returns without throwing (Databricks SQL's `assert_true` raises an error and halts if the condition is false — a failed assertion is a visibly failed step, not a silent wrong answer).

- [ ] **Step 3: Commit**

```bash
git add sql_onetrust/05_seed_test_principals.sql sql_onetrust/06_test_cases.sql
git commit -m "feat(sql_onetrust): seed 3 test principals and add 8 basic ABAC test cases"
```

---

## Task 20: Query validation runner

**Files:**
- Create: `onetrust_synth/run_compatible_queries.py`

**Interfaces:**
- Consumes: `config.ANNOTATED_QUERIES_CSV` (Task 1, points at `onetrust/onetrust_sanity_run_annotated.csv`, already committed)
- Produces: a `main()` script that, run on Databricks (needs a live SQL connection — no local unit test), executes every `modified_query` where `in_scope == "yes"` against the policy-active `abac_onetrust` dataset and reports pass/fail per query (pass = executes without error; a 0-row result is a pass, an exception is a fail).

- [ ] **Step 1: Write the runner**

```python
# onetrust_synth/run_compatible_queries.py
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
```

Run on Databricks (cluster driver or notebook, after Tasks 9/16/17/18 have populated and policy-wired the dataset). Expected: `Failed: 0` — if any query fails, the printed error identifies which query and why (missing table, type mismatch, etc.), to be fixed before treating Phase 1 as passing.

- [ ] **Step 2: Commit**

```bash
git add onetrust_synth/run_compatible_queries.py
git commit -m "feat(onetrust_synth): add runner for the 50 compatible queries against the live dataset"
```

---

## Phase 1 completion checklist (manual, run on Databricks in order)

1. `python3 -m pytest onetrust_synth/ -v` — all unit tests pass locally (Tasks 1–16).
2. Run `onetrust_synth/generate_main_tables.py` on a Databricks cluster attached to a Unity Catalog workspace.
3. Run `onetrust_synth/generate_abac_tables.py`.
4. Run `sql_onetrust/01_catalog_schema.sql` through `sql_onetrust/04_policies.sql` in order (Tasks 17–18) — substitute the real service principal in `04_policies.sql`.
5. Run `sql_onetrust/05_seed_test_principals.sql` then `sql_onetrust/06_test_cases.sql` (Task 19) — all 8 `assert_true` statements must pass.
6. Run `onetrust_synth/run_compatible_queries.py` (Task 20) — 0 failures out of 50.
7. If all of 1–6 pass: Phase 1 is done. Proceed to Phase 2 planning (full-scale ABAC generation + performance benchmark) per the design doc — a separate plan, not covered here.
