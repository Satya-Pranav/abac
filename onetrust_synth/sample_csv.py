# onetrust_synth/sample_csv.py
import csv
import os

from onetrust_synth import config

# Maps our table names to the real sample-file basenames (they don't follow a
# single naming rule: some carry the schema hash, one lives under a different
# schema prefix, and this is the sample_*.csv inventory from the design doc's
# section 3 — not derivable by string formatting alone).
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


# cmb_inventory's sample file header (21 columns) is a copy of
# cmb_v_inventoryaggregatedrisksummary's header and does not describe its own
# data rows (which have 19 fields, matching cmb_inventory's real, different
# schema). There's no reliable positional recovery here — unlike
# reportingmoduletoentityreferencemapping_v — so calling code must not use
# DictReader-based column lookups against this file.
_KNOWN_BAD_SAMPLE_FILES = {"cmb_inventory"}


def sample_file_path(table: str) -> str:
    return os.path.join(config.SAMPLE_DATA_DIR, _SAMPLE_FILES[table])


def load_rows(table: str) -> list[dict]:
    if table in _KNOWN_BAD_SAMPLE_FILES:
        raise ValueError(f"{table}'s sample file header is known-bad (does not match its own data) — see _KNOWN_BAD_SAMPLE_FILES")
    with open(sample_file_path(table), newline="", encoding="utf-8", errors="replace") as f:
        reader = csv.DictReader(f)
        return list(reader)


def load_column_values(table: str, column: str) -> list[str]:
    rows = load_rows(table)
    values = {r[column] for r in rows if r.get(column) not in (None, "")}
    return sorted(values)


def load_entity_type_reference_values() -> list[tuple[str, str]]:
    """
    reportingmoduletoentityreferencemapping_v's sample CSV has a misaligned header
    (a stale header from a different table's export — every column after the first
    two is empty). The real data is (reportingModule, entityTypeReference) as the
    first two columns, read positionally rather than by header name.
    """
    path = sample_file_path("reportingmoduletoentityreferencemapping_v")
    pairs = []
    with open(path, newline="", encoding="utf-8", errors="replace") as f:
        reader = csv.reader(f)
        next(reader)  # skip the (misaligned) header row
        for row in reader:
            if len(row) >= 2 and row[0] and row[1]:
                pairs.append((row[0], row[1]))
    return pairs
