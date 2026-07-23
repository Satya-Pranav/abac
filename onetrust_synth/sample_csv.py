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
