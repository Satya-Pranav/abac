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
