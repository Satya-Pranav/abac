"""
Re-scopes onetrust_sanity_run_annotated.csv's 307 currently-excluded queries against the
34-table scale-2 catalog. Two exclusion reasons are fixable by table coverage, both handled
here:
  - "references table(s) outside our 11[...]" -- a missing table, now possibly present.
  - "different tenant schema" -- excluded only because the query was captured against a
    DIFFERENT OneTrust customer's tenant-hash-qualified schema, not because it touches
    tables we don't have; the real production table vocabulary is identical across
    tenants, only the per-tenant schema hash differs.
Both are mechanically catalog-qualified for those now judged eligible, since the query ->
modified_query transformation observed on the existing 50 in-scope rows is exactly that
(design doc section 7).
"""
import csv
import re

from onetrust_synth import config

_TABLE_MISSING_REASON_MARKERS = ("references table(s) outside our", "outside our 11")
_KNOWN_SCHEMAS = ("onetrust_sim", "monitoring")
_DIFFERENT_TENANT_SCHEMA_REASON = "different tenant schema"

# Real production queries qualify tables as auto_qa_<tenant hash>.<table> (tenant-specific,
# one hash per customer) or bare monitoring.<table> (shared across tenants, never
# hash-prefixed -- confirmed against all 357 real queries, 0 counterexamples). The table
# vocabulary itself is identical across tenants (same OneTrust product schema), so a query
# excluded only for referencing a DIFFERENT tenant's hash is fair game once every table it
# touches is confirmed present in our set.
_TENANT_HASH_PREFIX = re.compile(r"\bauto_qa_[a-z0-9]+\.", re.IGNORECASE)
_QUERY_SCHEMA_TABLE_PATTERN = re.compile(r"\b(?:auto_qa_[a-z0-9]+|monitoring)\.([a-zA-Z0-9_]+)", re.IGNORECASE)


def load_all_annotated_queries(csv_path: str = config.ANNOTATED_QUERIES_CSV) -> list[dict]:
    with open(csv_path, newline="", encoding="utf-8", errors="replace") as f:
        return list(csv.DictReader(f))


def tables_referenced(tables_used: str) -> list[str]:
    if not tables_used:
        return []
    return [t.strip() for t in tables_used.split(",") if t.strip()]


def extract_tables_from_query_schema_refs(query: str) -> list[str]:
    """Pull distinct table names out of every auto_qa_<hash>.<table>/monitoring.<table>
    reference in raw SQL text. Used for the 'different tenant schema' exclusion reason,
    which -- unlike the missing-table reason -- never lists its tables anywhere in the
    CSV's own columns (tables_used is empty, reason is a fixed one-line string), only
    inside the query text itself."""
    seen: list[str] = []
    seen_lower: set[str] = set()
    for match in _QUERY_SCHEMA_TABLE_PATTERN.finditer(query or ""):
        table = match.group(1)
        if table.lower() not in seen_lower:
            seen_lower.add(table.lower())
            seen.append(table)
    return seen


def is_now_eligible(row: dict, available_tables: set[str]) -> bool:
    reason = (row.get("reason") or "").strip().lower()
    available_lower = {t.lower() for t in available_tables}

    if reason == _DIFFERENT_TENANT_SCHEMA_REASON:
        tables = extract_tables_from_query_schema_refs(row.get("query", ""))
        return bool(tables) and all(t.lower() in available_lower for t in tables)

    if not any(marker in reason for marker in _TABLE_MISSING_REASON_MARKERS):
        return False  # excluded for a reason scale/coverage doesn't fix

    # Try to get table names from tables_used column first
    used = tables_referenced(row.get("tables_used", ""))

    # If tables_used is empty, extract table names from the reason string
    if not used:
        used = _extract_tables_from_reason(row.get("reason", ""))

    if not used:
        return False

    # table names in tables_used may differ in case from our lowercase registry keys
    return all(t.lower() in available_lower for t in used)


def _extract_tables_from_reason(reason: str) -> list[str]:
    """Extract table names from reason string like 'references table(s) outside our 11: Table1, Table2'."""
    if not reason or ':' not in reason:
        return []

    # Split on the last colon to get the table name part
    parts = reason.rsplit(':', 1)
    if len(parts) != 2:
        return []

    table_names_part = parts[1].strip()
    if not table_names_part:
        return []

    # Split on commas and process each table name
    result = []
    for table_name in table_names_part.split(','):
        table_name = table_name.strip()
        if not table_name:
            continue

        # If the table name is schema-qualified (contains a dot), take the part after the last dot
        if '.' in table_name:
            table_name = table_name.rsplit('.', 1)[1]

        result.append(table_name)

    return result


def catalog_qualify(sql: str, catalog: str, schemas: tuple = _KNOWN_SCHEMAS) -> str:
    result = sql
    # Any tenant's schema hash (ours or a different tenant's) maps to our onetrust_sim
    # schema -- the real production table vocabulary is identical across tenants, only the
    # per-tenant hash differs. Runs before the bare-schema loop below so its output
    # (already catalog-qualified) is left alone by that loop's negative lookbehind.
    result = _TENANT_HASH_PREFIX.sub(f"{catalog}.onetrust_sim.", result)
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
