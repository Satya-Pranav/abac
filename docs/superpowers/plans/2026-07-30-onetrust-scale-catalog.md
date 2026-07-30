# OneTrust scale-testing catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a second, larger OneTrust catalog (`abac_onetrust_scale`) — 34 non-ABAC tables (11 existing + 23 new), 8 ABAC-governed tables (4 existing + 4 new), a ~1B-row `ABAC_EntitySubjectAssignment` — then produce a single CSV pairing every real OneTrust query (from a 357-query catalog) and every functional test case with the claim it should be tested under.

**Architecture:** Parameterize `onetrust_synth`'s catalog/table-registry/row-count constants (defaults preserve today's Phase-1 behavior exactly), add one new Databricks notebook that drives the scaled run, add new parameterized SQL-generator functions for governance (tags/policies/UDFs/seed principals) instead of static `.sql` files (since two catalogs now need the same SQL with different names), extend the Java `OnetrustCases` catalog with cases for the 4 newly-governed tables, and assemble the final CSV from two independently-produced halves (Python: real-query shortlist results; Java: functional-test case export).

**Tech Stack:** Python 3.12 + PySpark (onetrust_synth), pytest (local Spark, `local[2]`), Java 17 + Maven (JDBC/), raw Databricks SQL (governance).

## Global Constraints

- `phase1_run_all.py` and the `abac_onetrust` catalog are never modified or touched by any task in this plan — every changed function must behave identically to today when called with no new parameters (verified by the existing test suite continuing to pass unmodified).
- New catalog name: `abac_onetrust_scale`. Schemas: `onetrust_sim`, `monitoring` (same names as today, different catalog).
- Non-ABAC table scale factor: `5`. ABAC metadata table / registry scale factor: `100`, except `ABAC_EntitySubjectAssignment` which targets `1,000,000,000` directly. `orghierarchy` and `cmb_v_inventoryaggregatedrisksummary` stay unscaled (verbatim).
- Governed tables: `cmb_assessment`, `cmb_controlimplementation`, `cmb_template`, `cmb_v_inventoryaggregatedrisksummary` (unchanged policies, replayed) + `cmb_riskrelatedobjects`, `cmb_inventory`, `cmb_v_assessment_v4`, `entitylink_v3` (new policies).
- Every new/modified Python function must default to today's Phase-1 values when called with no override args — this is how "don't touch Phase 1" is enforced, not a separate code path.
- Run Python tests with: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/ -v`
- Run Java build/tests with: `cd /Users/satyapranav/Desktop/PycharmProjects/abac/JDBC && mvn -q compile` (there is no live-Databricks-dependent test to run locally for the new Java cases — compilation + the existing suite passing is the local verification bar; live correctness is verified when the notebook/suite actually runs against a real workspace).
- Source data: `onetrust/onetrust_remaining_table_profile_results2.csv`, `onetrust/onetrust_table_samples_remaining/*.csv`, `onetrust/onetrust_sanity_run_annotated.csv`.
- Spec: `docs/superpowers/specs/2026-07-30-onetrust-scale-catalog-design.md`.

---

## Task 1: `config.py` — register the 23 new tables and scale-2 targets

**Files:**
- Modify: `onetrust_synth/config.py`
- Test: `onetrust_synth/tests/test_config.py`

**Interfaces:**
- Produces: `config.REMAINING_PROFILE_CSV_PATH: str`, `config.REMAINING_SAMPLE_DATA_DIR: str`, `config.REMAINING_MAIN_TABLES: dict[str, int]` (23 entries), `config.ALL_SCALE2_MAIN_TABLES: dict[str, int]` (34 entries), `config.SCALE2_ABAC_TABLE_ROW_TARGETS: dict[str, int]` (5 entries), `config.SCALE2_SUBJECT_REGISTRY_USER_COUNT/GROUP_COUNT/STANDALONE_ENTITIES_PER_TYPE: int`, `config.scaled_row_count(table, scale_factor, table_row_counts=None)` (generalized).

- [ ] **Step 1: Write the failing tests**

```python
# append to onetrust_synth/tests/test_config.py

def test_remaining_main_tables_has_23_entries_with_real_row_counts():
    assert len(config.REMAINING_MAIN_TABLES) == 23
    assert config.REMAINING_MAIN_TABLES["entity_v3"] == 4153100
    assert config.REMAINING_MAIN_TABLES["cmb_v_assessmentquestionresponse_v3"] == 9493225
    assert config.REMAINING_MAIN_TABLES["dbxtenantschemaversion"] == 1070
    assert config.REMAINING_MAIN_TABLES["cmb_v_assessmenttag"] == 18
    # the 3 large tables with no matching sample data must NOT be present
    assert "entityattributevalue_v3" not in config.REMAINING_MAIN_TABLES
    assert "cmb_v_riskattributevalue_v3" not in config.REMAINING_MAIN_TABLES
    assert "cmb_v_inventorylinkattributemap" not in config.REMAINING_MAIN_TABLES


def test_all_scale2_main_tables_is_34_entries_and_does_not_mutate_main_tables():
    assert len(config.ALL_SCALE2_MAIN_TABLES) == 34
    assert set(config.ALL_SCALE2_MAIN_TABLES) == set(config.MAIN_TABLES) | set(config.REMAINING_MAIN_TABLES)
    # Phase 1's MAIN_TABLES must be completely unaffected
    assert len(config.MAIN_TABLES) == 11


def test_dbxtenantschemaversion_is_flagged_monitoring():
    assert config.MONITORING_TABLES == {"entitygroupconfig", "dbxtenantschemaversion"}


def test_scaled_row_count_accepts_alternate_table_dict():
    assert config.scaled_row_count("entity_v3", 5.0, config.REMAINING_MAIN_TABLES) == 20765500
    # default behavior (no override) must be unchanged
    assert config.scaled_row_count("cmb_assessment", 1.0) == 4984


def test_scale2_abac_row_targets():
    assert config.SCALE2_ABAC_TABLE_ROW_TARGETS["ABAC_EntitySubjectAssignment"] == 1_000_000_000
    assert config.SCALE2_ABAC_TABLE_ROW_TARGETS["ABAC_Assignment"] == 100_000
    assert config.SCALE2_ABAC_TABLE_ROW_TARGETS["ABAC_AssignmentPermission"] == 1_000_000
    assert config.SCALE2_ABAC_TABLE_ROW_TARGETS["UserGroupMembers"] == 500_000
    assert config.SCALE2_ABAC_TABLE_ROW_TARGETS["ABAC_OrgHierarchy"] == 183
    # Phase 1's targets must be completely unaffected
    assert config.ABAC_TABLE_ROW_TARGETS["ABAC_EntitySubjectAssignment"] == 100_000


def test_scale2_registry_sizes():
    assert config.SCALE2_SUBJECT_REGISTRY_USER_COUNT == 200_000
    assert config.SCALE2_SUBJECT_REGISTRY_GROUP_COUNT == 30_000
    assert config.SCALE2_STANDALONE_ENTITIES_PER_TYPE == 10_000
    # Phase 1's registry sizes must be completely unaffected
    assert config.SUBJECT_REGISTRY_USER_COUNT == 2000
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_config.py -v -k "remaining_main_tables or all_scale2 or dbxtenantschemaversion or scaled_row_count_accepts or scale2"`
Expected: FAIL — `AttributeError: module 'onetrust_synth.config' has no attribute 'REMAINING_MAIN_TABLES'` (and similar for the others).

- [ ] **Step 3: Implement**

Append to `onetrust_synth/config.py` (after the existing `MAIN_TABLES` dict, before `ABAC_TABLE_ROW_TARGETS`):

```python
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
```

Modify `MONITORING_TABLES` (currently `{"entitygroupconfig"}`):

```python
MONITORING_TABLES = {"entitygroupconfig", "dbxtenantschemaversion"}
```

Modify `scaled_row_count` to accept an optional alternate table dict:

```python
def scaled_row_count(table: str, scale_factor: float, table_row_counts: dict | None = None) -> int:
    source = table_row_counts if table_row_counts is not None else MAIN_TABLES
    return round(source[table] * scale_factor)
```

Append after `ABAC_TABLE_ROW_TARGETS` (Phase 1's dict stays completely unchanged above this):

```python
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
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_config.py -v`
Expected: PASS (all tests, old and new).

- [ ] **Step 5: Commit**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
git add onetrust_synth/config.py onetrust_synth/tests/test_config.py
git commit -m "feat(onetrust_synth): register 23 new tables and scale-2 targets in config"
```

---

## Task 2: `sample_csv.py` — wire the 23 new sample files

**Files:**
- Modify: `onetrust_synth/sample_csv.py`
- Test: `onetrust_synth/tests/test_sample_csv.py`

**Interfaces:**
- Consumes: `config.REMAINING_SAMPLE_DATA_DIR` (Task 1), `config.REMAINING_MAIN_TABLES` keys (Task 1).
- Produces: `sample_file_path(table)` resolves both the original 11 and the new 23; `load_rows(table)`/`load_column_values(table, column)` work unmodified for both sets (no signature change).

- [ ] **Step 1: Write the failing tests**

```python
# append to onetrust_synth/tests/test_sample_csv.py (create the file if it does not exist,
# matching the import style of onetrust_synth/tests/test_registries.py)
from onetrust_synth import config
from onetrust_synth.sample_csv import sample_file_path, load_rows, load_column_values


def test_sample_file_path_resolves_original_table():
    path = sample_file_path("cmb_assessment")
    assert path.endswith("sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_assessment.csv")
    assert config.SAMPLE_DATA_DIR in path


def test_sample_file_path_resolves_remaining_table():
    path = sample_file_path("entity_v3")
    assert path.endswith("sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_entity_v3.csv")
    assert config.REMAINING_SAMPLE_DATA_DIR in path


def test_load_rows_recovers_a_remaining_table():
    rows = load_rows("cmb_v_assessmenttag")
    assert len(rows) > 0
    assert "id" in rows[0] or "assessmentId" in rows[0]  # real columns per profile CSV


def test_load_column_values_works_for_remaining_table():
    values = load_column_values("dbxtenantschemaversion", "schemaVersion") if False else None
    # dbxtenantschemaversion's exact real columns aren't asserted here (only row-count/shape
    # matters for generation); this test just proves load_rows doesn't raise for it.
    rows = load_rows("dbxtenantschemaversion")
    assert len(rows) > 0
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_sample_csv.py -v`
Expected: FAIL — `test_sample_file_path_resolves_remaining_table` and the two remaining-table load tests raise `KeyError: 'entity_v3'` (not in `_SAMPLE_FILES`).

- [ ] **Step 3: Implement**

Modify `onetrust_synth/sample_csv.py` — add a second filename dict and update `sample_file_path`:

```python
# Add after the existing _SAMPLE_FILES dict (do not modify _SAMPLE_FILES itself):
_REMAINING_SAMPLE_FILES = {
    "cmb_v_assessmentapprover": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_assessmentapprover.csv",
    "cmb_v_assessmentinventory_v4": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_assessmentinventory_v4.csv",
    "cmb_v_assessmentquestion": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_assessmentquestion.csv",
    "cmb_v_assessmentquestionresponse_v3": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_assessmentquestionresponse_v3.csv",
    "cmb_v_assessmentrelatedentities": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_assessmentrelatedentities.csv",
    "cmb_v_assessmentrespondent": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_assessmentrespondent.csv",
    "cmb_v_assessmentstagechangetracker_v4": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_assessmentstagechangetracker_v4.csv",
    "cmb_v_assessmenttag": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_assessmenttag.csv",
    "cmb_v_controlimplementation_v4": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_controlimplementation_v4.csv",
    "cmb_v_controlimplementationentitylink": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_controlimplementationentitylink.csv",
    "cmb_v_inventory_v4": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_inventory_v4.csv",
    "cmb_v_inventorylastassessment_v3": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_inventorylastassessment_v3.csv",
    "cmb_v_inventorylinkv2": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_inventorylinkv2.csv",
    "cmb_v_inventorypersonaldataassociation": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_inventorypersonaldataassociation.csv",
    "cmb_v_inventorypersonaldataassociationclassification": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_inventorypersonaldataassociationclassification.csv",
    "cmb_v_risk_v4": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_risk_v4.csv",
    "cmb_v_riskapprover": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_riskapprover.csv",
    "cmb_v_riskcategory": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_riskcategory.csv",
    "cmb_v_riskowner": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_cmb_v_riskowner.csv",
    "entity_v3": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_entity_v3.csv",
    "entityworkflowstagechangetracker_v3": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_entityworkflowstagechangetracker_v3.csv",
    "reportingmoduletorelatedentitiesmapping_v": "sample_auto_qa_e40yx52dkbjpcqazimno9yvh4k_reportingmoduletorelatedentitiesmapping_v.csv",
    "dbxtenantschemaversion": "sample_monitoring_dbxtenantschemaversion.csv",
}

# Replace the existing sample_file_path function body with:
def sample_file_path(table: str) -> str:
    if table in _SAMPLE_FILES:
        return os.path.join(config.SAMPLE_DATA_DIR, _SAMPLE_FILES[table])
    return os.path.join(config.REMAINING_SAMPLE_DATA_DIR, _REMAINING_SAMPLE_FILES[table])
```

`load_rows()` needs no change — it already calls `sample_file_path(table)` and
`load_table_profile(config.PROFILE_CSV_PATH)` / `get_columns(...)` for column order. It must
also be able to resolve columns for the 23 new tables, which live in
`config.REMAINING_PROFILE_CSV_PATH`, not `config.PROFILE_CSV_PATH`. Update `load_rows`:

```python
def load_rows(table: str) -> list[dict]:
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    if (_profile_schema_for(table), table) not in profile:
        profile = load_table_profile(config.REMAINING_PROFILE_CSV_PATH)
    real_columns = [c.name for c in get_columns(profile, _profile_schema_for(table), table)]
    # ... rest of the function body is unchanged
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_sample_csv.py onetrust_synth/tests/test_config.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
git add onetrust_synth/sample_csv.py onetrust_synth/tests/test_sample_csv.py
git commit -m "feat(onetrust_synth): wire sample-file lookup for the 23 new tables"
```

---

## Task 3: `generate_main_tables.py` — merge profile CSVs, accept a table-set override

**Files:**
- Modify: `onetrust_synth/generate_main_tables.py`
- Test: `onetrust_synth/tests/test_generate_main_tables.py`

**Interfaces:**
- Consumes: `config.ALL_SCALE2_MAIN_TABLES` (Task 1), `config.REMAINING_PROFILE_CSV_PATH` (Task 1).
- Produces: `build_all_main_tables(spark, scale_factor=config.SCALE_FACTOR_DEFAULT, table_row_counts: dict | None = None) -> dict[str, DataFrame]` — new optional 3rd param, `None` default preserves today's exact behavior (11 tables from `config.MAIN_TABLES`).

- [ ] **Step 1: Write the failing test**

```python
# append to onetrust_synth/tests/test_generate_main_tables.py

from onetrust_synth import config
from onetrust_synth.generate_main_tables import build_all_main_tables


def test_build_all_main_tables_default_is_unchanged_11_tables(spark):
    tables = build_all_main_tables(spark, scale_factor=0.1)
    assert set(tables.keys()) == set(config.MAIN_TABLES.keys())
    assert len(tables) == 11


def test_build_all_main_tables_accepts_scale2_table_set(spark):
    tables = build_all_main_tables(spark, scale_factor=0.01, table_row_counts=config.ALL_SCALE2_MAIN_TABLES)
    assert len(tables) == 34
    assert "entity_v3" in tables
    assert "cmb_v_assessmenttag" in tables
    # every one of the 23 new tables builds without raising (no nested-column columns present,
    # per design doc section 3 — build_generic_table handles all of them)
    for new_table in config.REMAINING_MAIN_TABLES:
        assert tables[new_table].count() >= 0
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_generate_main_tables.py -v -k scale2`
Expected: FAIL — `TypeError: build_all_main_tables() got an unexpected keyword argument 'table_row_counts'`.

- [ ] **Step 3: Implement**

Modify `onetrust_synth/generate_main_tables.py`'s `build_all_main_tables`:

```python
def build_all_main_tables(
    spark: SparkSession,
    scale_factor: float = config.SCALE_FACTOR_DEFAULT,
    table_row_counts: dict | None = None,
) -> dict:
    table_row_counts = table_row_counts if table_row_counts is not None else config.MAIN_TABLES
    profile = load_table_profile(config.PROFILE_CSV_PATH)
    profile.update(load_table_profile(config.REMAINING_PROFILE_CSV_PATH))
    tables = {}

    # verbatim small tables — ignore scale_factor, they're real observed data
    if "orghierarchy" in table_row_counts:
        tables["orghierarchy"] = build_orghierarchy_df(spark)
    if "cmb_v_inventoryaggregatedrisksummary" in table_row_counts:
        tables["cmb_v_inventoryaggregatedrisksummary"] = build_cmb_v_inventoryaggregatedrisksummary_df(spark)

    for table_name in table_row_counts:
        if table_name in tables:
            continue
        schema_key = config.MONITORING_SCHEMA if table_name in config.MONITORING_TABLES else _TARGET_SCHEMA_HASH
        cols = get_columns(profile, schema_key, table_name)
        row_count = config.scaled_row_count(table_name, scale_factor, table_row_counts)

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
            df = add_categorical_column(
                df, "inventoryType", list(config.INVENTORY_TYPE_TO_OBJECT_TYPE.keys()),
                null_rate=next((c.null_rate for c in cols if c.name == "inventoryType"), 0.0),
                salt="cmb_inventory.inventoryType.real_vocab",
                row_id_col="id",
            )

        tables[table_name] = df

    return tables
```

This is a pure refactor of the existing loop body to iterate `table_row_counts` (default:
`config.MAIN_TABLES`, identical to today) instead of the hardcoded `config.MAIN_TABLES` global,
plus the two-CSV profile merge (harmless for Phase 1 — the 23 extra dict entries are never
looked up when `table_row_counts` is the default 11-table dict). `main()` is unchanged (still
calls with no override, so it keeps writing exactly the 11 Phase-1 tables).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/ -v`
Expected: PASS — full suite, including all pre-existing tests (this is the check that Phase 1 behavior is unchanged).

- [ ] **Step 5: Commit**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
git add onetrust_synth/generate_main_tables.py onetrust_synth/tests/test_generate_main_tables.py
git commit -m "feat(onetrust_synth): build_all_main_tables accepts a table-set override for scale-2"
```

---

## Task 4: `registries.py` / `generate_abac_tables.py` — entitylink_v3 entity wiring + row-target override

**Files:**
- Modify: `onetrust_synth/registries.py`
- Modify: `onetrust_synth/generate_abac_tables.py`
- Test: `onetrust_synth/tests/test_registries.py`
- Test: `onetrust_synth/tests/test_generate_abac_tables.py`

**Interfaces:**
- Produces: `registries.build_entity_registry(spark, main_tables, extra_pieces: list[DataFrame] | None = None) -> DataFrame` (new optional 3rd param), `registries.build_entitylink_v3_entity_piece(main_tables: dict) -> DataFrame` (new function), `generate_abac_tables.build_all_abac_tables(spark, main_tables, row_targets: dict | None = None, extra_entity_pieces: list[DataFrame] | None = None) -> dict[str, DataFrame]` (two new optional params).

- [ ] **Step 1: Write the failing tests**

```python
# append to onetrust_synth/tests/test_registries.py

from onetrust_synth.registries import build_entitylink_v3_entity_piece


def test_entitylink_v3_entity_piece_shape(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    piece = build_entitylink_v3_entity_piece(main_tables)
    assert set(piece.columns) == {"entityId", "objectType", "orgId"}
    assert piece.count() > 0


def test_entity_registry_accepts_extra_pieces(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    base_reg = build_entity_registry(spark, main_tables)
    extra = build_entitylink_v3_entity_piece(main_tables)
    combined_reg = build_entity_registry(spark, main_tables, extra_pieces=[extra])
    assert combined_reg.count() >= base_reg.count()
    types = {r["objectType"] for r in combined_reg.select("objectType").distinct().collect()}
    assert "CONTROLTEMPLATE" in types


def test_entity_registry_without_extra_pieces_is_unchanged(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    reg = build_entity_registry(spark, main_tables)
    types = {r["objectType"] for r in reg.select("objectType").distinct().collect()}
    assert "CONTROLTEMPLATE" not in types
```

```python
# append to onetrust_synth/tests/test_generate_abac_tables.py

from onetrust_synth import config
from onetrust_synth.registries import build_entitylink_v3_entity_piece
from onetrust_synth.generate_abac_tables import build_all_abac_tables
from onetrust_synth.generate_main_tables import build_all_main_tables


def test_build_all_abac_tables_default_targets_are_unchanged(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    abac = build_all_abac_tables(spark, main_tables)
    assert abac["ABAC_EntitySubjectAssignment"].count() > 0  # Phase-1 sized, not asserting exact count (hash-index driven)


def test_build_all_abac_tables_accepts_row_targets_override(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    small_scale2_targets = {
        "ABAC_Assignment": 50, "ABAC_AssignmentPermission": 200,
        "ABAC_EntitySubjectAssignment": 500, "UserGroupMembers": 100,
        "ABAC_OrgHierarchy": 183,
    }
    abac = build_all_abac_tables(spark, main_tables, row_targets=small_scale2_targets)
    assert abac["ABAC_Assignment"].count() == 50


def test_build_all_abac_tables_accepts_extra_entity_pieces(spark):
    main_tables = build_all_main_tables(spark, scale_factor=0.1)
    extra = build_entitylink_v3_entity_piece(main_tables)
    small_targets = {
        "ABAC_Assignment": 200, "ABAC_AssignmentPermission": 500,
        "ABAC_EntitySubjectAssignment": 2000, "UserGroupMembers": 100,
        "ABAC_OrgHierarchy": 183,
    }
    abac = build_all_abac_tables(spark, main_tables, row_targets=small_targets, extra_entity_pieces=[extra])
    esa = abac["ABAC_EntitySubjectAssignment"]
    types = {r["objectType"] for r in esa.select("objectType").distinct().collect()}
    # CONTROLTEMPLATE only appears in ESA if some ABAC_Assignment row also has that objectType —
    # not guaranteed with a small random Assignment sample, so this test only asserts the
    # pipeline runs end-to-end without error when extra_entity_pieces is supplied.
    assert esa.count() > 0
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_registries.py onetrust_synth/tests/test_generate_abac_tables.py -v -k "extra_pieces or entitylink_v3 or row_targets"`
Expected: FAIL — `ImportError: cannot import name 'build_entitylink_v3_entity_piece'` and `TypeError: unexpected keyword argument`.

- [ ] **Step 3: Implement**

Add to `onetrust_synth/registries.py` (after `_inventory_type_to_object_type_column`):

```python
def build_entitylink_v3_entity_piece(main_tables: dict) -> DataFrame:
    """
    entitylink_v3 is not in config.ENTITY_SOURCE_TABLES (unlike the other 7 governed-or-candidate
    tables) — it links two entities per row, and only entityid1/entityid1typereference was chosen
    to drive the row filter (design doc section 5). Kept as a standalone function (not folded into
    ENTITY_SOURCE_TABLES) so Phase 1's entity_registry composition is provably unaffected even
    though entitylink_v3 already exists as a Phase-1 main table.
    """
    df = main_tables["entitylink_v3"]
    return (
        df.select(
            F.col("entityid1").alias("entityId"),
            F.upper(F.col("entityid1typereference")).alias("objectType"),
        )
        .withColumn("orgId", F.lit(None).cast("string"))
    )
```

Modify `build_entity_registry` to accept `extra_pieces`:

```python
def build_entity_registry(spark: SparkSession, main_tables: dict, extra_pieces: list | None = None) -> DataFrame:
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

    if extra_pieces:
        pieces.extend(extra_pieces)

    harvested = pieces[0]
    for p in pieces[1:]:
        harvested = harvested.unionByName(p)
    harvested = harvested.dropDuplicates(["entityId", "objectType"])

    covered_types = {t for _, (_, t) in config.ENTITY_SOURCE_TABLES.items() if t is not None}
    covered_types |= set(config.INVENTORY_TYPE_TO_OBJECT_TYPE.values())
    all_types = {t for _, t in load_entity_type_reference_values()}
    uncovered_types = sorted(all_types - covered_types)
    # NOTE: entitylink_v3's CONTROLTEMPLATE/EVIDENCETASKTEMPLATE object types are not in
    # reportingmoduletoentityreferencemapping_v's vocabulary, so they never appear in
    # uncovered_types/standalone entities — this is fine, they still reach the registry via
    # extra_pieces above; standalone-entity generation is only a fallback for types with NO
    # source table at all.

    standalone_pieces = []
    for object_type in uncovered_types:
        df = add_id_column(
            base_row_id_df(spark, config.STANDALONE_ENTITIES_PER_TYPE),
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

(Only the `extra_pieces` parameter and the two lines that consume it are new; the rest of the
function body is unchanged from today.)

Modify `onetrust_synth/generate_abac_tables.py`'s `build_all_abac_tables`:

```python
def build_all_abac_tables(
    spark: SparkSession,
    main_tables: dict,
    row_targets: dict | None = None,
    extra_entity_pieces: list | None = None,
) -> dict:
    row_targets = row_targets if row_targets is not None else config.ABAC_TABLE_ROW_TARGETS
    entity_registry = build_entity_registry(spark, main_tables, extra_pieces=extra_entity_pieces)
    org_registry = build_org_registry(spark)
    subject_registry = build_subject_registry(spark)

    assignment = build_abac_assignment(spark, row_targets["ABAC_Assignment"])
    assignment_permission = build_abac_assignment_permission(
        spark, assignment, row_targets["ABAC_AssignmentPermission"]
    )
    esa = build_abac_entity_subject_assignment(
        spark, assignment, entity_registry, org_registry, subject_registry,
        row_targets["ABAC_EntitySubjectAssignment"],
    )
    user_group_members = build_user_group_members(spark, subject_registry, row_targets["UserGroupMembers"])
    org_hierarchy_base = build_org_hierarchy_base(spark)

    return {
        "ABAC_Assignment": assignment,
        "ABAC_AssignmentPermission": assignment_permission,
        "ABAC_EntitySubjectAssignment": esa,
        "UserGroupMembers": user_group_members,
        "ABAC_OrgHierarchy": org_hierarchy_base,
    }
```

`main()` is unchanged (no override args passed, so it keeps building Phase-1-sized tables with
no extra entity pieces).

Note: `build_subject_registry(spark)` still reads `config.SUBJECT_REGISTRY_USER_COUNT`/
`GROUP_COUNT` directly (module-level constants, not parameters) — Task 7's notebook needs the
scale-2 registry sizes (`config.SCALE2_SUBJECT_REGISTRY_USER_COUNT`/`GROUP_COUNT`). Since
`build_subject_registry` has no override parameter yet, add one now for completeness:

```python
# in registries.py, replace build_subject_registry with:
def build_subject_registry(
    spark: SparkSession, user_count: int | None = None, group_count: int | None = None,
) -> DataFrame:
    user_count = user_count if user_count is not None else config.SUBJECT_REGISTRY_USER_COUNT
    group_count = group_count if group_count is not None else config.SUBJECT_REGISTRY_GROUP_COUNT
    users = add_id_column(base_row_id_df(spark, user_count), "subjectId", prefix="user_")
    users = users.withColumn("subjectType", F.lit("USER_ID")).drop("_row_id")

    groups = add_id_column(base_row_id_df(spark, group_count), "subjectId", prefix="group_")
    groups = groups.withColumn("subjectType", F.lit("USER_GROUP")).drop("_row_id")

    return users.unionByName(groups)
```

And thread it through `build_all_abac_tables` with a 5th optional param `registry_sizes: dict | None = None` (keys `"users"`/`"groups"`), used as:

```python
    registry_sizes = registry_sizes or {}
    subject_registry = build_subject_registry(
        spark, user_count=registry_sizes.get("users"), group_count=registry_sizes.get("groups"),
    )
```

(insert this in place of the plain `subject_registry = build_subject_registry(spark)` line, and
add `registry_sizes: dict | None = None` to `build_all_abac_tables`'s signature.)

Add a test for this too:

```python
# append to onetrust_synth/tests/test_registries.py

def test_subject_registry_accepts_size_override(spark):
    reg = build_subject_registry(spark, user_count=10, group_count=5)
    assert reg.filter(reg.subjectType == "USER_ID").count() == 10
    assert reg.filter(reg.subjectType == "USER_GROUP").count() == 5


def test_subject_registry_default_is_unchanged(spark):
    reg = build_subject_registry(spark)
    assert reg.filter(reg.subjectType == "USER_ID").count() == config.SUBJECT_REGISTRY_USER_COUNT
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/ -v`
Expected: PASS — full suite.

- [ ] **Step 5: Commit**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
git add onetrust_synth/registries.py onetrust_synth/generate_abac_tables.py onetrust_synth/tests/test_registries.py onetrust_synth/tests/test_generate_abac_tables.py
git commit -m "feat(onetrust_synth): entitylink_v3 entity wiring + row-target/registry-size overrides"
```

---

## Task 5: `governance_sql.py` — UDFs, tags, and policies for all 8 governed tables

**Files:**
- Create: `onetrust_synth/governance_sql.py`
- Test: `onetrust_synth/tests/test_governance_sql.py`

**Interfaces:**
- Consumes: nothing from earlier tasks (pure string-building, no Spark).
- Produces: `build_udf_sql(catalog: str, schema: str = "onetrust_sim") -> list[str]`, `build_tags_sql(catalog: str, schema: str = "onetrust_sim") -> list[str]`, `build_policies_sql(catalog: str, schema: str = "onetrust_sim", service_principal: str = "<SERVICE_PRINCIPAL>") -> list[str]` — each returns a list of individually-executable SQL statements (no `;`-splitting needed by the caller), mirroring `sql_onetrust/03_row_filter_udfs.sql` / `02_tags.sql` / `04_policies.sql` parameterized by catalog, extended to the 4 new governed tables.

- [ ] **Step 1: Write the failing tests**

```python
# onetrust_synth/tests/test_governance_sql.py
from onetrust_synth.governance_sql import build_udf_sql, build_tags_sql, build_policies_sql


def test_build_udf_sql_is_catalog_qualified():
    stmts = build_udf_sql("abac_onetrust_scale")
    assert len(stmts) == 4  # get_test_user_context, entity_type_to_object_type, abac_row_filter, abac_row_filter_wrapper
    joined = "\n".join(stmts)
    assert "abac_onetrust_scale.onetrust_sim.abac_row_filter" in joined
    assert "abac_onetrust.onetrust_sim" not in joined  # never leaks the original catalog name


def test_build_tags_sql_covers_all_8_tables():
    stmts = build_tags_sql("abac_onetrust_scale")
    joined = "\n".join(stmts)
    for table in [
        "cmb_assessment", "cmb_controlimplementation", "cmb_template",
        "cmb_v_inventoryaggregatedrisksummary", "cmb_riskrelatedobjects",
        "cmb_inventory", "cmb_v_assessment_v4", "entitylink_v3",
    ]:
        assert f"abac_onetrust_scale.onetrust_sim.{table}" in joined
    # spot-check the 3 new tag columns from design doc section 5
    assert "ALTER COLUMN riskId SET TAGS ('abac_column_id' = 'true')" in joined
    assert "ALTER COLUMN entityType SET TAGS ('abac_column_type' = 'true')" in joined
    assert "ALTER COLUMN organizationID SET TAGS ('abac_column_org' = 'true')" in joined
    assert "ALTER COLUMN entityid1 SET TAGS ('abac_column_id' = 'true')" in joined
    assert "ALTER COLUMN entityid1typereference SET TAGS ('abac_column_type' = 'true')" in joined


def test_build_policies_sql_covers_all_8_tables_with_correct_shapes():
    stmts = build_policies_sql("abac_onetrust_scale", service_principal="sp-app-id")
    joined = "\n".join(stmts)
    assert len(stmts) == 8
    assert "TO `sp-app-id`" in joined
    # id-only shape (literal type + literal org)
    assert "USING COLUMNS (id, 'ASSESSMENT', '100')" in joined  # cmb_assessment, unchanged
    # full 3-tag shape (new): cmb_riskrelatedobjects
    assert "MATCH COLUMNS has_tag('abac_column_id') as id, has_tag('abac_column_type') as type, has_tag('abac_column_org') as org" in joined
    # cmb_v_assessment_v4: id + literal type + org column
    assert "USING COLUMNS (id, 'ASSESSMENT', org)" in joined
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_governance_sql.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'onetrust_synth.governance_sql'`.

- [ ] **Step 3: Implement**

```python
# onetrust_synth/governance_sql.py
"""
Parameterized re-emission of sql_onetrust/03_row_filter_udfs.sql, 02_tags.sql, and
04_policies.sql for a given catalog — needed because a second catalog (abac_onetrust_scale)
requires its own copies of the same UDFs/tags/policies, and the original files hardcode
`abac_onetrust` throughout (see design doc section 6). The original sql_onetrust/*.sql files
are left untouched and remain the source of truth for abac_onetrust itself; this module is
only used for the scale-2 catalog.

Extends coverage from the original 4 governed tables to 8 (design doc section 5): the 4 new
ones (cmb_riskrelatedobjects, cmb_inventory, cmb_v_assessment_v4, entitylink_v3) use real
per-row columns rather than literals wherever the profile data confirmed one exists.
"""


def build_udf_sql(catalog: str, schema: str = "onetrust_sim") -> list[str]:
    q = f"{catalog}.{schema}"
    return [
        f"""CREATE OR REPLACE FUNCTION {q}.get_test_user_context()
RETURNS STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
COMMENT 'Deterministic ABAC context for test-case validation'
RETURN named_struct(
  'tenant',      1,
  'user',        'u.assessment.owner@example.com',
  'org',         '100',
  'mode',        'ABAC',
  'root',        'ASSESSMENT',
  'permissions', array('TEMPLATE')
);""",
        f"""CREATE OR REPLACE FUNCTION {q}.entity_type_to_object_type(entity_type STRING)
RETURNS STRING
COMMENT 'Normalizes a raw table/column type value to the canonical ABAC object type.'
RETURN CASE
  WHEN upper(entity_type) = 'PROCESSING ACTIVITIES' THEN 'PROCESSING-ACTIVITIES'
  ELSE upper(entity_type)
END;""",
        f"""CREATE OR REPLACE FUNCTION {q}.abac_row_filter(
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
          SELECT orgId FROM {q}.ABAC_OrgHierarchy
          WHERE parentOrgId = ctx.org
        )
      )
      OR EXISTS (
        SELECT 1
        FROM {q}.ABAC_EntitySubjectAssignment esa
        JOIN {q}.ABAC_Assignment a
          ON esa.assignmentId = a.id
          AND a.isActive
          AND a.isDeleted = false
        LEFT JOIN {q}.UserGroupMembers ugm
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
);""",
        f"""CREATE OR REPLACE FUNCTION {q}.abac_row_filter_wrapper(
  entity_id STRING, object_type STRING, org_id STRING
)
RETURNS BOOLEAN
RETURN {q}.abac_row_filter(
  entity_id, {q}.entity_type_to_object_type(object_type), org_id,
  {q}.get_test_user_context()
);""",
    ]


# (table, id_column, type_tag_column_or_None, org_tag_column_or_None)
_TAG_SPEC = [
    ("cmb_assessment", "id", None, None),
    ("cmb_controlimplementation", "id", None, None),
    ("cmb_template", "id", None, None),
    ("cmb_v_inventoryaggregatedrisksummary", "entityID", "inventoryType", "orgID"),
    ("cmb_riskrelatedobjects", "riskId", "entityType", "organizationID"),
    ("cmb_inventory", "id", "inventoryType", None),
    ("cmb_v_assessment_v4", "id", None, "orgID"),
    ("entitylink_v3", "entityid1", "entityid1typereference", None),
]


def build_tags_sql(catalog: str, schema: str = "onetrust_sim") -> list[str]:
    q = f"{catalog}.{schema}"
    stmts = []
    for table, id_col, type_col, org_col in _TAG_SPEC:
        stmts.append(f"ALTER TABLE {q}.{table} ALTER COLUMN {id_col} SET TAGS ('abac_column_id' = 'true');")
        if type_col:
            stmts.append(f"ALTER TABLE {q}.{table} ALTER COLUMN {type_col} SET TAGS ('abac_column_type' = 'true');")
        if org_col:
            stmts.append(f"ALTER TABLE {q}.{table} ALTER COLUMN {org_col} SET TAGS ('abac_column_org' = 'true');")
    return stmts


# (table, literal_type_or_None, literal_org_or_None) — None means "bind the real tagged column instead"
_POLICY_SPEC = {
    "cmb_assessment": ("ASSESSMENT", "100"),
    "cmb_controlimplementation": ("CONTROL", "100"),
    "cmb_template": ("TEMPLATE", "100"),
    "cmb_v_inventoryaggregatedrisksummary": (None, None),
    "cmb_riskrelatedobjects": (None, None),
    "cmb_inventory": (None, "100"),
    "cmb_v_assessment_v4": ("ASSESSMENT", None),
    "entitylink_v3": (None, "100"),
}


def build_policies_sql(catalog: str, schema: str = "onetrust_sim", service_principal: str = "<SERVICE_PRINCIPAL>") -> list[str]:
    q = f"{catalog}.{schema}"
    stmts = []
    tag_spec_by_table = {t: (id_c, type_c, org_c) for t, id_c, type_c, org_c in _TAG_SPEC}

    for table, (literal_type, literal_org) in _POLICY_SPEC.items():
        _, type_col, org_col = tag_spec_by_table[table]
        match_cols = ["has_tag('abac_column_id') as id"]
        using_cols = ["id"]

        if literal_type is not None:
            using_cols.append(f"'{literal_type}'")
        else:
            match_cols.append("has_tag('abac_column_type') as type")
            using_cols.append("type")

        if literal_org is not None:
            using_cols.append(f"'{literal_org}'")
        else:
            match_cols.append("has_tag('abac_column_org') as org")
            using_cols.append("org")

        stmts.append(
            f"CREATE OR REPLACE POLICY {schema}_{table}_abac_policy\n"
            f"ON TABLE {q}.{table}\n"
            f"ROW FILTER {q}.abac_row_filter_wrapper\n"
            f"TO `{service_principal}`\n"
            f"FOR TABLES\n"
            f"MATCH COLUMNS {', '.join(match_cols)}\n"
            f"USING COLUMNS ({', '.join(using_cols)});"
        )
    return stmts
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_governance_sql.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
git add onetrust_synth/governance_sql.py onetrust_synth/tests/test_governance_sql.py
git commit -m "feat(onetrust_synth): parameterized UDF/tag/policy SQL for the 8-table scale-2 catalog"
```

---

## Task 6: `governance_sql.py` — seeded test principals for all 8 governed tables

**Files:**
- Modify: `onetrust_synth/governance_sql.py`
- Test: `onetrust_synth/tests/test_governance_sql.py`

**Interfaces:**
- Produces: `build_seed_principals_sql(catalog: str, schema: str = "onetrust_sim") -> list[str]` — self-contained `DELETE`+`INSERT` statements for all 8 governed tables' known test principals (assignment IDs 900001-900009 — 5 for the original 4 tables, matching `sql_onetrust/05`'s existing count, + 1 each for the 4 new tables), mirroring `sql_onetrust/05_seed_test_principals.sql`'s exact pattern (deterministic `ORDER BY ... LIMIT 1` entity picks, no cross-statement state).

Real values used (verified against the actual sample data, not guessed — see design doc section
5 and this task's research): `cmb_riskrelatedobjects.entityType='INVENTORY'` (a real value that
maps to itself via `upper()`, avoiding the hyphenation special-case), `cmb_inventory.inventoryType
='Assets'` (maps to `'ASSETS'`, same convention as the existing `cmb_v_inventoryaggregatedrisksummary`
seed), `cmb_v_assessment_v4.orgID='b99df4a4-2bf5-4c08-9483-bd636470bc11'` (the same real org
already used elsewhere in this suite, e.g. `Runner.java`'s `ONETRUST_REAL_ASSETS_ORG`),
`entitylink_v3.entityid1typereference='ControlTemplate'` (the only value the 500-row sample
observed — see the caveat comment in the code below).

- [ ] **Step 1: Write the failing tests**

```python
# append to onetrust_synth/tests/test_governance_sql.py
from onetrust_synth.governance_sql import build_seed_principals_sql


def test_build_seed_principals_sql_covers_all_8_tables():
    stmts = build_seed_principals_sql("abac_onetrust_scale")
    joined = "\n".join(stmts)
    # 3 DELETEs (idempotent re-run) + inserts for 9 assignment ids (900001-900009: 5 for the
    # original 4 tables + 1 each for the 4 new tables)
    assert joined.count("DELETE FROM") == 3
    for assignment_id in range(900001, 900010):
        assert str(assignment_id) in joined
    assert "u.assessment.owner@example.com" in joined  # original 4, replayed
    assert "u.risk.owner@example.com" in joined  # new
    assert "u.inventory.owner@example.com" in joined  # new
    assert "u.assessmentv4.owner@example.com" in joined  # new
    assert "u.entitylink.owner@example.com" in joined  # new
    assert "abac_onetrust_scale.onetrust_sim.cmb_riskrelatedobjects" in joined
    assert "WHERE upper(entityType) = 'INVENTORY'" in joined
    assert "WHERE upper(inventoryType) = 'ASSETS'" in joined
    assert "b99df4a4-2bf5-4c08-9483-bd636470bc11" in joined
    assert "WHERE entityid1typereference = 'ControlTemplate'" in joined
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_governance_sql.py -v -k seed_principals`
Expected: FAIL — `ImportError: cannot import name 'build_seed_principals_sql'`.

- [ ] **Step 3: Implement**

Append to `onetrust_synth/governance_sql.py`:

```python
def build_seed_principals_sql(catalog: str, schema: str = "onetrust_sim") -> list[str]:
    q = f"{catalog}.{schema}"
    stmts = [
        f"DELETE FROM {q}.ABAC_Assignment WHERE staticIdentifier = 'phase2-test-seed';",
        f"DELETE FROM {q}.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase2-test-seed';",
        f"DELETE FROM {q}.UserGroupMembers WHERE tenantHash = 'phase2-test-seed';",
    ]

    def assignment_insert(aid, object_type, is_active="true"):
        return (
            f"INSERT INTO {q}.ABAC_Assignment "
            "(id, guid, staticIdentifier, name, objectType, sourceType, isActive, createdBy, "
            "createDT, updatedBy, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)\n"
            f"SELECT {aid}, uuid(), 'phase2-test-seed', 'Owner', '{object_type}', 'SYSTEM', {is_active}, "
            "'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), "
            "current_timestamp(), 'phase2-test-seed', false;"
        )

    def esa_insert(aid, table, id_col, filter_clause, subject, subject_type, object_type_expr):
        return (
            f"INSERT INTO {q}.ABAC_EntitySubjectAssignment "
            "(assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, "
            "objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)\n"
            f"SELECT {aid}, NULL, entity_id, NULL, '{subject}', '{subject_type}', {object_type_expr}, "
            "current_timestamp(), current_timestamp(), current_timestamp(), 'phase2-test-seed', false\n"
            f"FROM (SELECT {id_col} AS entity_id FROM {q}.{table} {filter_clause} LIMIT 1) ent;"
        )

    # --- original 4, replayed verbatim (same subjects/tables as sql_onetrust/05, new catalog) ---
    stmts.append(assignment_insert(900001, "ASSESSMENT"))
    stmts.append(assignment_insert(900002, "ASSESSMENT", is_active="false"))
    stmts.append(assignment_insert(900003, "CONTROL"))
    stmts.append(esa_insert(900001, "cmb_assessment", "id", "ORDER BY id", "u.assessment.owner@example.com", "USER_ID", "'ASSESSMENT'"))
    stmts.append(esa_insert(900002, "cmb_assessment", "id", "ORDER BY id", "u.inactive.grant@example.com", "USER_ID", "'ASSESSMENT'"))
    stmts.append(esa_insert(900003, "cmb_controlimplementation", "id", "ORDER BY id", "test_group_1", "USER_GROUP", "'CONTROL'"))
    stmts.append(
        f"INSERT INTO {q}.UserGroupMembers (memberId, groupId, eventTime, recModifiedTime, isDeleted, tenantHash)\n"
        f"VALUES ('u.group.member@example.com', 'test_group_1', current_timestamp(), current_timestamp(), false, 'phase2-test-seed');"
    )
    stmts.append(assignment_insert(900004, "TEMPLATE"))
    stmts.append(esa_insert(900004, "cmb_template", "id", "ORDER BY id", "u.template.owner@example.com", "USER_ID", "'TEMPLATE'"))
    stmts.append(assignment_insert(900005, "ASSETS"))
    stmts.append(esa_insert(
        900005, "cmb_v_inventoryaggregatedrisksummary", "entityID",
        "WHERE upper(inventoryType) = 'ASSETS' ORDER BY entityID",
        "u.assets.owner@example.com", "USER_ID", "'ASSETS'",
    ))

    # --- 4 new governed tables (design doc section 5) ---
    stmts.append(assignment_insert(900006, "INVENTORY"))
    stmts.append(esa_insert(
        900006, "cmb_riskrelatedobjects", "riskId",
        "WHERE upper(entityType) = 'INVENTORY' ORDER BY riskId",
        "u.risk.owner@example.com", "USER_ID", "'INVENTORY'",
    ))

    stmts.append(assignment_insert(900007, "ASSETS"))
    stmts.append(esa_insert(
        900007, "cmb_inventory", "id",
        "WHERE upper(inventoryType) = 'ASSETS' ORDER BY id",
        "u.inventory.owner@example.com", "USER_ID", "'ASSETS'",
    ))

    stmts.append(assignment_insert(900008, "ASSESSMENT"))
    # cmb_v_assessment_v4's id is a fan-out column (design doc section 5): more than one physical
    # row can share the picked id, which is expected (mirrors TPC-DS A5), not a bug — the test
    # case built on this seed asserts nonzero, not exactly 1.
    stmts.append(esa_insert(
        900008, "cmb_v_assessment_v4", "id",
        "WHERE orgID = 'b99df4a4-2bf5-4c08-9483-bd636470bc11' ORDER BY id",
        "u.assessmentv4.owner@example.com", "USER_ID", "'ASSESSMENT'",
    ))

    stmts.append(assignment_insert(900009, "CONTROLTEMPLATE"))
    # entityid1typereference's real vocabulary has 5 values, but the 500-row sample only
    # observed 'ControlTemplate' (design doc section 5 caveat) — build_generic_table's
    # categorical synthesis is undersampled the same way, so this is the only value
    # guaranteed to actually appear in the generated data. entityid1 is also not unique
    # (fan-out, same as cmb_v_assessment_v4) — nonzero, not exactly-1, expected.
    stmts.append(esa_insert(
        900009, "entitylink_v3", "entityid1",
        "WHERE entityid1typereference = 'ControlTemplate' ORDER BY entityid1",
        "u.entitylink.owner@example.com", "USER_ID", "'CONTROLTEMPLATE'",
    ))

    return stmts
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_governance_sql.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
git add onetrust_synth/governance_sql.py onetrust_synth/tests/test_governance_sql.py
git commit -m "feat(onetrust_synth): seeded test principals for all 8 scale-2 governed tables"
```

---

## Task 7: `databricks/phase2_scale_run.py` — the orchestration notebook

**Files:**
- Create: `databricks/phase2_scale_run.py`

**Interfaces:**
- Consumes: everything from Tasks 1-6 (`config.ALL_SCALE2_MAIN_TABLES`, `config.SCALE2_*`, `generate_main_tables.build_all_main_tables(..., table_row_counts=...)`, `generate_abac_tables.build_all_abac_tables(..., row_targets=..., extra_entity_pieces=..., registry_sizes=...)`, `registries.build_entitylink_v3_entity_piece`, `governance_sql.build_udf_sql/build_tags_sql/build_policies_sql/build_seed_principals_sql`, `validate.validate_row_counts/validate_referential_integrity` (unmodified, already generic — see design doc section 9 correction: no code change was needed there).
- No downstream task consumes this notebook's output programmatically — it's the terminal artifact this phase produces (a live Databricks catalog), run manually by the user per the design's execution-access decision.

This task has no automated test (needs a live Databricks Unity Catalog session, unavailable in
this environment — same constraint noted throughout the design doc). Verification is: (a) the
file has valid Python syntax (`python3 -m py_compile`), and (b) the user runs it against a real
workspace per Step 3 below and confirms the dry-run validation output.

- [ ] **Step 1: Write the notebook**

```python
# databricks/phase2_scale_run.py
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
for table_name, df in main_tables.items():
    schema = MONITORING_SCHEMA if table_name in config.MONITORING_TABLES else MAIN_SCHEMA
    write_delta_table(df, CATALOG, schema, table_name)
    print(f"Wrote {CATALOG}.{schema}.{table_name}: {df.count()} rows")

# COMMAND ----------
# Step 3: ABAC tables — Phase-1-sized targets for a dry run, SCALE2 targets (up to 1B) for real
from onetrust_synth.generate_abac_tables import build_all_abac_tables
from onetrust_synth.registries import build_entitylink_v3_entity_piece
from onetrust_synth.abac_tables import build_org_hierarchy_view_sql

row_targets = config.ABAC_TABLE_ROW_TARGETS if DRY_RUN else config.SCALE2_ABAC_TABLE_ROW_TARGETS
registry_sizes = None if DRY_RUN else {
    "users": config.SCALE2_SUBJECT_REGISTRY_USER_COUNT,
    "groups": config.SCALE2_SUBJECT_REGISTRY_GROUP_COUNT,
}
extra_entity_pieces = [build_entitylink_v3_entity_piece(main_tables)]

abac_tables = build_all_abac_tables(
    spark, main_tables, row_targets=row_targets,
    extra_entity_pieces=extra_entity_pieces, registry_sizes=registry_sizes,
)
for table_name, df in abac_tables.items():
    write_table_name = "OrgHierarchyBase" if table_name == "ABAC_OrgHierarchy" else table_name
    partition_by = ["objectType"] if table_name in config.ABAC_PARTITIONED_TABLES else None
    write_delta_table(df, CATALOG, MAIN_SCHEMA, write_table_name, partition_by=partition_by)
    print(f"Wrote {CATALOG}.{MAIN_SCHEMA}.{write_table_name}: {df.count()} rows")

spark.sql(
    f"CREATE OR REPLACE VIEW {CATALOG}.{MAIN_SCHEMA}.ABAC_OrgHierarchy AS "
    f"SELECT * FROM {CATALOG}.{MAIN_SCHEMA}.OrgHierarchyBase WHERE isDeleted IS NOT TRUE"
)

# COMMAND ----------
# Validation gate — must pass before Step 4 (tags/policies) runs, same order as phase1_run_all.py
from onetrust_synth.validate import validate_row_counts, validate_referential_integrity
from onetrust_synth.registries import build_org_registry, build_subject_registry, build_entity_registry

built_counts = {k: v.count() for k, v in abac_tables.items()}
row_failures = validate_row_counts(built_counts, row_targets, tolerance=0.05)
assert not row_failures, f"Row count validation failed: {row_failures}"
print("Row-count validation passed.")

# build_all_abac_tables doesn't return the registries it built internally (Task 4's return
# contract stays {table_name: DataFrame} only, so Task 4's tests are unaffected) — rebuild them
# here for the FK-integrity check. Deterministic generation (generator.py's hash-based approach)
# means this reproduces byte-identical registries, not a second random draw.
entity_registry_check = build_entity_registry(spark, main_tables, extra_pieces=extra_entity_pieces)
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
from onetrust_synth.governance_sql import build_udf_sql, build_tags_sql, build_policies_sql, build_seed_principals_sql

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
print(f"Phase 2 {'DRY RUN' if DRY_RUN else 'FULL SCALE'} complete: catalog={CATALOG}, "
      f"scale_factor={scale_factor}, ESA target={row_targets['ABAC_EntitySubjectAssignment']}")
```

`entity_registry_check`/`subject_registry_check` are rebuilt here rather than threaded out of
`build_all_abac_tables` — this keeps Task 4's `{table_name: DataFrame}` return contract (and its
tests) unchanged, at the cost of rebuilding two registries the function already built once
internally. Both are cheap relative to the ESA write itself (hundreds of thousands to low
millions of rows even at real scale, vs. up to 1B for ESA), so this is a reasonable trade even
for the full-scale run, not just the dry run.

- [ ] **Step 2: Verify syntax**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m py_compile databricks/phase2_scale_run.py`
Expected: no output (clean compile). This only proves the file is syntactically valid Python —
it does NOT prove the notebook runs correctly, since `spark`/`dbutils` aren't available outside
Databricks.

- [ ] **Step 3: Hand off for a live dry run**

This step is manual, performed by the user (not this plan's automated portion): open
`databricks/phase2_scale_run.py` as a Databricks notebook, set the `dry_run` widget to `true`,
fill in `service_principal`, run all cells, confirm the final print statement and that
`SHOW POLICIES` lists all 8 tables. Only after that succeeds, re-run with `dry_run=false` for
the real 1B-row build.

- [ ] **Step 4: Commit**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
git add databricks/phase2_scale_run.py
git commit -m "feat: add phase2_scale_run notebook for the abac_onetrust_scale catalog"
```

---

## Task 8: `query_rewrite.py` — re-scope and mechanically rewrite the 307 excluded queries

**Files:**
- Create: `onetrust_synth/query_rewrite.py`
- Test: `onetrust_synth/tests/test_query_rewrite.py`

**Interfaces:**
- Consumes: `config.ANNOTATED_QUERIES_CSV` (existing constant), `config.ALL_SCALE2_MAIN_TABLES` (Task 1) for the "table now exists" check.
- Produces: `load_all_annotated_queries(csv_path: str = config.ANNOTATED_QUERIES_CSV) -> list[dict]`, `tables_referenced(tables_used: str) -> list[str]`, `is_now_eligible(row: dict, available_tables: set[str]) -> bool`, `catalog_qualify(sql: str, catalog: str, schemas: tuple[str, ...] = ("onetrust_sim", "monitoring")) -> str`, `build_modified_query(row: dict, catalog: str) -> str`.

- [ ] **Step 1: Write the failing tests**

```python
# onetrust_synth/tests/test_query_rewrite.py
from onetrust_synth.query_rewrite import (
    load_all_annotated_queries, tables_referenced, is_now_eligible, catalog_qualify, build_modified_query,
)


def test_load_all_annotated_queries_returns_357_rows():
    rows = load_all_annotated_queries()
    assert len(rows) == 357
    assert sum(1 for r in rows if r["in_scope"] == "yes") == 50
    assert sum(1 for r in rows if r["in_scope"] == "no") == 307


def test_tables_referenced_parses_comma_separated_list():
    assert tables_referenced("cmb_assessment, cmb_template") == ["cmb_assessment", "cmb_template"]
    assert tables_referenced("EntityGroupConfig") == ["EntityGroupConfig"]


def test_is_now_eligible_true_when_all_tables_now_present():
    available = {"cmb_assessment", "entity_v3"}
    row = {"tables_used": "cmb_assessment, entity_v3", "reason": "references table(s) outside our 11: entity_v3"}
    assert is_now_eligible(row, available) is True


def test_is_now_eligible_false_when_a_table_is_still_missing():
    available = {"cmb_assessment"}
    row = {"tables_used": "cmb_assessment, entityattributevalue_v3", "reason": "references table(s) outside our 11"}
    assert is_now_eligible(row, available) is False


def test_is_now_eligible_false_for_non_table_exclusion_reasons():
    available = {"cmb_assessment"}
    row = {"tables_used": "cmb_assessment", "reason": "different tenant schema"}
    assert is_now_eligible(row, available) is False
    row2 = {"tables_used": "", "reason": "no real table reference (BI/AAS plumbing query)"}
    assert is_now_eligible(row2, available) is False


def test_catalog_qualify_adds_catalog_prefix_to_bare_schema_table():
    sql = "SELECT egc1_0.entityType FROM monitoring.EntityGroupConfig egc1_0"
    result = catalog_qualify(sql, "abac_onetrust_scale")
    assert "abac_onetrust_scale.monitoring.EntityGroupConfig" in result
    assert "FROM monitoring.EntityGroupConfig" not in result


def test_catalog_qualify_is_idempotent_on_already_qualified_sql():
    sql = "SELECT * FROM abac_onetrust_scale.onetrust_sim.cmb_assessment"
    result = catalog_qualify(sql, "abac_onetrust_scale")
    assert result.count("abac_onetrust_scale.abac_onetrust_scale") == 0


def test_build_modified_query_reuses_existing_modified_query_when_present():
    row = {"query": "select 1", "modified_query": "SELECT 1  -- already rewritten", "tables_used": ""}
    assert build_modified_query(row, "abac_onetrust_scale") == "SELECT 1  -- already rewritten"


def test_build_modified_query_rewrites_when_modified_query_is_empty():
    row = {"query": "select x from monitoring.EntityGroupConfig", "modified_query": "", "tables_used": "EntityGroupConfig"}
    result = build_modified_query(row, "abac_onetrust_scale")
    assert "abac_onetrust_scale.monitoring.EntityGroupConfig" in result
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_query_rewrite.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'onetrust_synth.query_rewrite'`.

- [ ] **Step 3: Implement**

```python
# onetrust_synth/query_rewrite.py
"""
Re-scopes onetrust_sanity_run_annotated.csv's 307 currently-excluded queries against the
34-table scale-2 catalog: filters to ones whose only blocker was a missing table (now
present), and mechanically catalog-qualifies bare schema.table references for those, since
the query -> modified_query transformation observed on the existing 50 in-scope rows is
exactly that (design doc section 7).
"""
import csv
import re

from onetrust_synth import config

_TABLE_MISSING_REASON_MARKERS = ("references table(s) outside our", "outside our 11")
_KNOWN_SCHEMAS = ("onetrust_sim", "monitoring")


def load_all_annotated_queries(csv_path: str = config.ANNOTATED_QUERIES_CSV) -> list[dict]:
    with open(csv_path, newline="", encoding="utf-8", errors="replace") as f:
        return list(csv.DictReader(f))


def tables_referenced(tables_used: str) -> list[str]:
    if not tables_used:
        return []
    return [t.strip() for t in tables_used.split(",") if t.strip()]


def is_now_eligible(row: dict, available_tables: set[str]) -> bool:
    reason = (row.get("reason") or "").lower()
    if not any(marker in reason for marker in _TABLE_MISSING_REASON_MARKERS):
        return False  # excluded for a reason scale/coverage doesn't fix
    used = tables_referenced(row.get("tables_used", ""))
    if not used:
        return False
    # table names in tables_used may differ in case from our lowercase registry keys
    available_lower = {t.lower() for t in available_tables}
    return all(t.lower() in available_lower for t in used)


def catalog_qualify(sql: str, catalog: str, schemas: tuple = _KNOWN_SCHEMAS) -> str:
    result = sql
    for schema in schemas:
        # only qualify a bare "schema.table" that isn't already preceded by our own catalog name
        pattern = re.compile(rf"(?<!{re.escape(catalog)}\.)\b{re.escape(schema)}\.", re.IGNORECASE)
        result = pattern.sub(f"{catalog}.{schema}.", result)
    return result


def build_modified_query(row: dict, catalog: str) -> str:
    existing = (row.get("modified_query") or "").strip()
    if existing:
        return existing
    return catalog_qualify(row["query"], catalog)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_query_rewrite.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
git add onetrust_synth/query_rewrite.py onetrust_synth/tests/test_query_rewrite.py
git commit -m "feat(onetrust_synth): re-scope and mechanically rewrite newly-eligible real queries"
```

---

## Task 9: `query_shortlist.py` — claim pairing + execute-and-catch against the live catalog

**Files:**
- Create: `onetrust_synth/query_shortlist.py`
- Test: `onetrust_synth/tests/test_query_shortlist.py`

**Interfaces:**
- Consumes: `query_rewrite.load_all_annotated_queries/is_now_eligible/build_modified_query` (Task 8), `governance_sql`'s seeded-principal subject list (Task 6, as literal claim data — see `SEEDED_CLAIMS_BY_TABLE` below).
- Produces: `SEEDED_CLAIMS_BY_TABLE: dict[str, str]` (table → claim JSON), `claim_for_query(tables_used: str) -> str`, `build_shortlist_rows(catalog: str) -> list[dict]` (the non-Spark parts: filtering + claim pairing + modified_query — everything except actually running the SQL), `run_shortlist(spark, catalog: str) -> list[dict]` (adds `verified_status`/`row_count`/`error` by executing each row's query, needs a live Spark session so only smoke-testable, not unit-testable), `write_shortlist_csv(rows: list[dict], out_path: str) -> None`.

- [ ] **Step 1: Write the failing tests**

```python
# onetrust_synth/tests/test_query_shortlist.py
import csv
import os
import tempfile

from onetrust_synth.query_shortlist import (
    SEEDED_CLAIMS_BY_TABLE, claim_for_query, build_shortlist_rows, write_shortlist_csv,
)


def test_seeded_claims_cover_all_8_governed_tables():
    assert set(SEEDED_CLAIMS_BY_TABLE) == {
        "cmb_assessment", "cmb_controlimplementation", "cmb_template",
        "cmb_v_inventoryaggregatedrisksummary", "cmb_riskrelatedobjects",
        "cmb_inventory", "cmb_v_assessment_v4", "entitylink_v3",
    }
    assert '"user":"u.assessment.owner@example.com"' in SEEDED_CLAIMS_BY_TABLE["cmb_assessment"].replace(" ", "")


def test_claim_for_query_picks_first_governed_table():
    claim = claim_for_query("cmb_assessment, cmb_template")
    assert "u.assessment.owner@example.com" in claim


def test_claim_for_query_falls_back_to_disable_probe_when_ungoverned():
    claim = claim_for_query("orghierarchy")
    assert '"mode":"DISABLE"' in claim.replace(" ", "")


def test_build_shortlist_rows_includes_all_50_original_plus_newly_eligible():
    rows = build_shortlist_rows("abac_onetrust_scale")
    aliases = {r["query_id"] for r in rows}
    assert len(aliases) >= 50  # at least the original 50, plus however many of the 307 are now eligible
    for r in rows:
        assert r["source"] == "real_query"
        assert "abac_onetrust_scale" in r["query"] or r["query"].strip() == ""
        assert r["claim"]


def test_write_shortlist_csv_produces_expected_columns():
    rows = [{
        "query_id": "q1", "source": "real_query", "tables_used": "cmb_assessment",
        "claim": '{"mode":"ABAC"}', "query": "SELECT 1", "expected_or_observed": "3",
        "verified_status": "PASS",
    }]
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "shortlist.csv")
        write_shortlist_csv(rows, out)
        with open(out, newline="", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            written = list(reader)
        assert written[0]["query_id"] == "q1"
        assert set(reader.fieldnames) == {
            "query_id", "source", "tables_used", "claim", "query",
            "expected_or_observed", "verified_status",
        }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_query_shortlist.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'onetrust_synth.query_shortlist'`.

- [ ] **Step 3: Implement**

```python
# onetrust_synth/query_shortlist.py
"""
Pairs every shortlisted real query with a claim, then (when a live Spark/Unity Catalog session
is available — see run_shortlist) executes each and records pass/fail. Design doc section 7-8.
"""
import csv

from onetrust_synth import config
from onetrust_synth.query_rewrite import load_all_annotated_queries, is_now_eligible, build_modified_query, tables_referenced

# One claim per governed table, matching governance_sql.build_seed_principals_sql's seeded
# subjects exactly (design doc section 5/7.4) — kept as a literal here rather than importing
# from governance_sql, since governance_sql emits SQL strings, not claim JSON.
SEEDED_CLAIMS_BY_TABLE = {
    "cmb_assessment": '{"tenant":1,"user":"u.assessment.owner@example.com","org":"100","mode":"ABAC","root":"ASSESSMENT","permissions":[]}',
    "cmb_controlimplementation": '{"tenant":1,"user":"test_group_1","org":"100","mode":"ABAC","root":"CONTROL","permissions":[]}',
    "cmb_template": '{"tenant":1,"user":"u.template.owner@example.com","org":"100","mode":"ABAC","root":"TEMPLATE","permissions":[]}',
    "cmb_v_inventoryaggregatedrisksummary": '{"tenant":1,"user":"u.assets.owner@example.com","org":"100","mode":"ABAC","root":"ASSETS","permissions":[]}',
    "cmb_riskrelatedobjects": '{"tenant":1,"user":"u.risk.owner@example.com","org":"100","mode":"ABAC","root":"INVENTORY","permissions":[]}',
    "cmb_inventory": '{"tenant":1,"user":"u.inventory.owner@example.com","org":"100","mode":"ABAC","root":"ASSETS","permissions":[]}',
    "cmb_v_assessment_v4": '{"tenant":1,"user":"u.assessmentv4.owner@example.com","org":"100","mode":"ABAC","root":"ASSESSMENT","permissions":[]}',
    "entitylink_v3": '{"tenant":1,"user":"u.entitylink.owner@example.com","org":"100","mode":"ABAC","root":"CONTROLTEMPLATE","permissions":[]}',
}
_DISABLE_PROBE_CLAIM = '{"tenant":1,"user":"probe","org":"100","mode":"DISABLE","root":"Customer","permissions":[]}'


def claim_for_query(tables_used: str) -> str:
    for table in tables_referenced(tables_used):
        claim = SEEDED_CLAIMS_BY_TABLE.get(table.lower()) or SEEDED_CLAIMS_BY_TABLE.get(table)
        if claim:
            return claim
    return _DISABLE_PROBE_CLAIM


def build_shortlist_rows(catalog: str) -> list[dict]:
    all_rows = load_all_annotated_queries()
    available_tables = set(config.ALL_SCALE2_MAIN_TABLES.keys())
    rows = []

    for row in all_rows:
        eligible = row["in_scope"] == "yes" or is_now_eligible(row, available_tables)
        if not eligible:
            continue
        rows.append({
            "query_id": row["query_alias"],
            "source": "real_query",
            "tables_used": row.get("tables_used", ""),
            "claim": claim_for_query(row.get("tables_used", "")),
            "query": build_modified_query(row, catalog),
            "expected_or_observed": "",  # filled in by run_shortlist
            "verified_status": "",  # filled in by run_shortlist
        })
    return rows


def run_shortlist(spark, catalog: str) -> list[dict]:
    """Needs a live Spark session with Unity Catalog access — not unit-testable locally."""
    rows = build_shortlist_rows(catalog)
    for row in rows:
        try:
            count = spark.sql(row["query"]).count()
            row["expected_or_observed"] = str(count)
            row["verified_status"] = "PASS"
        except Exception as e:
            row["expected_or_observed"] = ""
            row["verified_status"] = f"FAIL: {str(e)[:300]}"
    return rows


def write_shortlist_csv(rows: list[dict], out_path: str) -> None:
    fieldnames = ["query_id", "source", "tables_used", "claim", "query", "expected_or_observed", "verified_status"]
    with open(out_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k, "") for k in fieldnames})
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_query_shortlist.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
git add onetrust_synth/query_shortlist.py onetrust_synth/tests/test_query_shortlist.py
git commit -m "feat(onetrust_synth): claim-pairing and shortlist CSV production for real queries"
```

---

## Task 10: `OnetrustCases.java` — functional test cases for the 4 new governed tables

**Files:**
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Consumes: `Case` record, `Expect` factories, `Cases.claim(user, org, mode, root, permissionsJson)` (all existing).
- Produces: `newGovernedTableCases(): List<Case>` — new group-builder method following the exact style of `abacGroupCases()`; wired into `all()`.

- [ ] **Step 1: Add the new group-builder method**

Insert into `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`, immediately after the
existing `abacGroupCases()` method (i.e., after its closing `}` around line 219 — verify the
exact line by searching for `return cs;` following `abacGroupCases`):

```java
    /** The 4 tables newly governed for the scale-2 catalog (design doc section 5): real
     *  per-row entityType/inventoryType/orgID/typereference columns drive the filter instead
     *  of a literal, mirroring OT-A2/OT-A6's positive/negative pairing. Requires
     *  onetrust_synth.governance_sql's seed principals (assignment IDs 900006-900009) to
     *  already be applied — see databricks/phase2_scale_run.py Step 6. */
    public static List<Case> newGovernedTableCases() {
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-RRO1", "ABAC", "cmb_riskrelatedobjects: real entityType/organizationID columns drive the filter -> seeded grant visible.",
            "New for the scale-2 catalog. u.risk.owner has an explicit ESA grant (seed id 900006) on one real "
                + "riskId whose entityType is 'INVENTORY' -- proves the full 3-tag pattern (id+type+org) works "
                + "on a table with real per-row columns for all three, unlike the original 4 tables' literals.",
            Cases.claim("u.risk.owner@example.com", "100", "ABAC", "INVENTORY", "[]"),
            "SELECT count(*) FROM " + q("cmb_riskrelatedobjects")
                + " WHERE riskId = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.risk.owner@example.com' AND objectType = 'INVENTORY' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-RRO2", "ABAC", "cmb_riskrelatedobjects: deny wrong user.",
            "Negative pairing for OT-RRO1 -- u.template.owner has no INVENTORY-type assignment.",
            Cases.claim("u.template.owner@example.com", "100", "ABAC", "INVENTORY", "[]"),
            "SELECT count(*) FROM " + q("cmb_riskrelatedobjects"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-INV1", "ABAC", "cmb_inventory: real inventoryType column (via entity_type_to_object_type) drives the filter -> seeded grant visible.",
            "New for the scale-2 catalog. u.inventory.owner has an explicit ESA grant (seed id 900007) on "
                + "one real id whose inventoryType is 'Assets' -> object type 'ASSETS'.",
            Cases.claim("u.inventory.owner@example.com", "100", "ABAC", "ASSETS", "[]"),
            "SELECT count(*) FROM " + q("cmb_inventory")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.inventory.owner@example.com' AND objectType = 'ASSETS' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-INV2", "ABAC", "cmb_inventory: deny wrong user.",
            "Negative pairing for OT-INV1.",
            Cases.claim("u.template.owner@example.com", "100", "ABAC", "ASSETS", "[]"),
            "SELECT count(*) FROM " + q("cmb_inventory"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-AV41", "ABAC", "cmb_v_assessment_v4: fan-out id (many physical rows can share one id) -> seeded grant makes at least one visible.",
            "New for the scale-2 catalog. u.assessmentv4.owner has an explicit ESA grant (seed id 900008) on "
                + "one real id in org b99df4a4-2bf5-4c08-9483-bd636470bc11. Unlike OT-A2's exact(1), this "
                + "asserts nonzero because cmb_v_assessment_v4's id is NOT unique (ndv=2,666 across 1.59M "
                + "real rows, same fan-out shape TPC-DS's A5 tests deliberately) -- more than 1 row can "
                + "legitimately share the granted id.",
            Cases.claim("u.assessmentv4.owner@example.com", "100", "ABAC", "ASSESSMENT", "[]"),
            "SELECT count(*) FROM " + q("cmb_v_assessment_v4")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.assessmentv4.owner@example.com' AND objectType = 'ASSESSMENT' LIMIT 1)",
            Expect.nonzero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-AV42", "ABAC", "cmb_v_assessment_v4: deny wrong user.",
            "Negative pairing for OT-AV41.",
            Cases.claim("u.template.owner@example.com", "100", "ABAC", "ASSESSMENT", "[]"),
            "SELECT count(*) FROM " + q("cmb_v_assessment_v4"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-EL1", "ABAC", "entitylink_v3: real entityid1typereference column drives the filter -> seeded grant makes at least one row visible.",
            "New for the scale-2 catalog. u.entitylink.owner has an explicit ESA grant (seed id 900009) on "
                + "one real entityid1 whose entityid1typereference is 'ControlTemplate' -> object type "
                + "'CONTROLTEMPLATE'. Asserts nonzero, not exactly 1: entityid1 is not unique either "
                + "(ndv=673 across 1M+ real rows) -- same fan-out reasoning as OT-AV41.",
            Cases.claim("u.entitylink.owner@example.com", "100", "ABAC", "CONTROLTEMPLATE", "[]"),
            "SELECT count(*) FROM " + q("entitylink_v3")
                + " WHERE entityid1 = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.entitylink.owner@example.com' AND objectType = 'CONTROLTEMPLATE' LIMIT 1)",
            Expect.nonzero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-EL2", "ABAC", "entitylink_v3: deny wrong user.",
            "Negative pairing for OT-EL1.",
            Cases.claim("u.template.owner@example.com", "100", "ABAC", "CONTROLTEMPLATE", "[]"),
            "SELECT count(*) FROM " + q("entitylink_v3"), Expect.zero(), NEEDS_CLAIM_SWAP));

        return cs;
    }
```

Wire it into `all()` — find the existing chain of `cs.addAll(...)` calls (starting around line
50, `cs.addAll(functionalCases()); cs.addAll(abacGroupCases()); ...`) and add one line
immediately after `cs.addAll(abacGroupCases());`:

```java
        cs.addAll(newGovernedTableCases());
```

- [ ] **Step 2: Compile to verify no syntax errors**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac/JDBC && mvn -q compile`
Expected: BUILD SUCCESS, no output.

- [ ] **Step 3: Commit**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
git add JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat(JDBC): add functional test cases for the 4 newly-governed scale-2 tables"
```

---

## Task 11: Java CSV exporter for functional-test cases

**Files:**
- Create: `JDBC/src/main/java/com/abacpoc/cases/CsvExporter.java`

**Interfaces:**
- Consumes: `OnetrustCases.all()` (existing + Task 10's 8 new cases), `Case`/`Expect` (existing).
- Produces: a runnable `main(String[] args)` writing a CSV to `args[0]` with columns matching Task 9's `write_shortlist_csv` schema (`query_id,source,tables_used,claim,query,expected_or_observed,verified_status`) so the two halves concatenate cleanly in Task 12. SQL text is catalog-qualified from `abac_onetrust` to `abac_onetrust_scale` by literal string substitution (every case's SQL uses the fixed `SCHEMA = "abac_onetrust.onetrust_sim"` prefix via the `q()` helper, so the substitution is exact and total).

- [ ] **Step 1: Write the exporter**

```java
// JDBC/src/main/java/com/abacpoc/cases/CsvExporter.java
package com.abacpoc.cases;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Exports OnetrustCases.all() to a CSV matching query_shortlist.write_shortlist_csv's schema
 * (query_id,source,tables_used,claim,query,expected_or_observed,verified_status), so this file
 * and the Python-produced real-query shortlist concatenate into one final deliverable CSV
 * (design doc section 8). "tables_used" is left blank here -- Case has no structured table
 * field, only an id/group/sql/claim; the query text itself names the table(s).
 *
 * SQL text is rewritten from the original abac_onetrust catalog to abac_onetrust_scale via a
 * literal substring replace: every OnetrustCases query is built through the q(String) helper,
 * which always prefixes with the fixed SCHEMA constant "abac_onetrust.onetrust_sim" -- so the
 * replacement is exact and total, not a heuristic.
 */
public final class CsvExporter {

    private static final String ORIGINAL_CATALOG_SCHEMA = "abac_onetrust.onetrust_sim";
    private static final String TARGET_CATALOG_SCHEMA = "abac_onetrust_scale.onetrust_sim";

    private CsvExporter() { }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("Usage: CsvExporter <output.csv>");
            System.exit(1);
        }
        Path out = Paths.get(args[0]);
        List<Case> cases = OnetrustCases.all();

        try (PrintWriter w = new PrintWriter(Path.of(out.toString()).toFile(), "UTF-8")) {
            w.println("query_id,source,tables_used,claim,query,expected_or_observed,verified_status");
            for (Case c : cases) {
                String query = c.sql().replace(ORIGINAL_CATALOG_SCHEMA, TARGET_CATALOG_SCHEMA);
                w.println(String.join(",",
                    csvField(c.id()),
                    csvField("functional_test"),
                    csvField(""),
                    csvField(c.claim()),
                    csvField(query),
                    csvField(c.exp().describe()),
                    csvField("")
                ));
            }
        }
        System.out.println("Wrote " + cases.size() + " functional-test rows to " + out);
    }

    private static String csvField(String value) {
        String v = value == null ? "" : value;
        String escaped = v.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }
}
```

- [ ] **Step 2: Compile and run it**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac/JDBC && mvn -q compile`
Expected: BUILD SUCCESS.

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac/JDBC && mvn -q exec:java -Dexec.mainClass=com.abacpoc.cases.CsvExporter -Dexec.args="/tmp/functional_tests.csv"` (if the `exec-maven-plugin` isn't configured in `pom.xml`, instead run: `java -cp target/classes:$(find ~/.m2 -name 'commons-csv-*.jar' | head -1) com.abacpoc.cases.CsvExporter /tmp/functional_tests.csv`)
Expected: prints `Wrote 127 functional-test rows to /tmp/functional_tests.csv` (119 existing + 8 new from Task 10), and `/tmp/functional_tests.csv` has that many data rows plus a header.

- [ ] **Step 3: Commit**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
git add JDBC/src/main/java/com/abacpoc/cases/CsvExporter.java
git commit -m "feat(JDBC): export OnetrustCases functional tests to the shared CSV schema"
```

---

## Task 12: Final CSV assembly

**Files:**
- Create: `onetrust_synth/assemble_final_csv.py`
- Test: `onetrust_synth/tests/test_assemble_final_csv.py`

**Interfaces:**
- Consumes: two CSV files with the identical 7-column schema from Task 9 (`write_shortlist_csv`'s output) and Task 11 (`CsvExporter`'s output).
- Produces: `assemble(real_query_csv: str, functional_test_csv: str, out_path: str) -> int` (returns total row count written) — a trivial concatenation with header de-duplication, since both halves already share the exact same column order.

- [ ] **Step 1: Write the failing test**

```python
# onetrust_synth/tests/test_assemble_final_csv.py
import csv
import os
import tempfile

from onetrust_synth.assemble_final_csv import assemble

_HEADER = "query_id,source,tables_used,claim,query,expected_or_observed,verified_status\n"


def test_assemble_concatenates_both_csvs_with_one_header():
    with tempfile.TemporaryDirectory() as d:
        real_path = os.path.join(d, "real.csv")
        func_path = os.path.join(d, "func.csv")
        out_path = os.path.join(d, "final.csv")

        with open(real_path, "w") as f:
            f.write(_HEADER)
            f.write('q1,real_query,cmb_assessment,"{}",SELECT 1,3,PASS\n')

        with open(func_path, "w") as f:
            f.write(_HEADER)
            f.write('OT-A1,functional_test,,"{}",SELECT 2,ALL rows,\n')

        total = assemble(real_path, func_path, out_path)
        assert total == 2

        with open(out_path, newline="") as f:
            rows = list(csv.DictReader(f))
        assert len(rows) == 2
        assert {r["query_id"] for r in rows} == {"q1", "OT-A1"}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_assemble_final_csv.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'onetrust_synth.assemble_final_csv'`.

- [ ] **Step 3: Implement**

```python
# onetrust_synth/assemble_final_csv.py
"""Concatenates the Python-produced real-query shortlist and the Java-produced functional-test
export into the single final deliverable CSV (design doc section 8) -- both already share the
same 7-column schema, so this is pure concatenation, not a merge."""
import csv


def assemble(real_query_csv: str, functional_test_csv: str, out_path: str) -> int:
    total = 0
    with open(out_path, "w", newline="", encoding="utf-8") as out_f:
        writer = None
        for source_path in (real_query_csv, functional_test_csv):
            with open(source_path, newline="", encoding="utf-8") as in_f:
                reader = csv.DictReader(in_f)
                if writer is None:
                    writer = csv.DictWriter(out_f, fieldnames=reader.fieldnames)
                    writer.writeheader()
                for row in reader:
                    writer.writerow(row)
                    total += 1
    return total
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/test_assemble_final_csv.py -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
git add onetrust_synth/assemble_final_csv.py onetrust_synth/tests/test_assemble_final_csv.py
git commit -m "feat(onetrust_synth): assemble the final queries+claims CSV from both halves"
```

---

## Task 13: End-to-end run guide (manual, post-dry-run)

**Files:**
- None (this task is a checklist for the user, not code — everything it references was already built and tested in Tasks 1-12).

- [ ] **Step 1:** Run the full local test suite one more time: `cd /Users/satyapranav/Desktop/PycharmProjects/abac && python3 -m pytest onetrust_synth/tests/ -v && cd JDBC && mvn -q compile`. Expected: all green.
- [ ] **Step 2:** Run `databricks/phase2_scale_run.py` (Task 7) with `dry_run=true` against a real workspace. Confirm the final print statement and `SHOW POLICIES` output.
- [ ] **Step 3:** Re-run with `dry_run=false` for the real 1B-row build (expect a long-running job — see the cluster-sizing note in the design doc section 4 and Task 7's notebook comments).
- [ ] **Step 4:** From a Databricks notebook cell with `spark` in scope, run: `from onetrust_synth.query_shortlist import run_shortlist, write_shortlist_csv; rows = run_shortlist(spark, "abac_onetrust_scale"); write_shortlist_csv(rows, "/dbfs/tmp/real_query_shortlist.csv")`, then download that file locally.
- [ ] **Step 5:** Locally, run Task 11's `CsvExporter` to produce `functional_tests.csv`, then `python3 -c "from onetrust_synth.assemble_final_csv import assemble; print(assemble('real_query_shortlist.csv', 'functional_tests.csv', 'docs/testing/onetrust-scale-catalog-queries.csv'))"`.
- [ ] **Step 6:** Commit the final CSV: `git add docs/testing/onetrust-scale-catalog-queries.csv && git commit -m "docs: add final queries+claims CSV for the scale-2 catalog"`.
