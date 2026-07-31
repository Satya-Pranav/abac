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
import functools
import re

from onetrust_synth import config
from onetrust_synth.sample_csv import load_rows

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


_DOUBLE_QUOTED_TOKEN = re.compile(r'"([^"]*)"')


def normalize_double_quoted_identifiers(sql: str) -> str:
    """
    Converts ANSI-style double-quoted identifiers ("colName") to Databricks' native
    backtick-quoted identifiers (`colName`). Databricks' default SQL dialect parses double
    quotes as string literals, not identifiers (spark.sql.ansi.doubleQuotedIdentifiers
    defaults to false even under ANSI mode) -- so a query captured from a system that treats
    double quotes as identifiers (e.g. a BI tool that always wraps its generated derived-table
    alias in double quotes, like "$Table"/"_") fails to parse at all on Databricks. Confirmed
    live 2026-07-31: dozens of shortlisted queries hit exactly this
    ([PARSE_SYNTAX_ERROR] at the first double-quoted token) once run for real.

    Backticks support the same spaces/special characters double-quoted identifiers do (real
    OneTrust columns like "lastModified Date" need this), so this is a purely syntactic swap --
    it doesn't change what the query selects. Safe to apply unconditionally: a no-op for
    queries with no double-quoted text, and harmless even inside a /* ... */ comment (some of
    the CSV's original in_scope=yes queries carry a debug comment with embedded, escaped JSON --
    Spark's parser strips comment bodies wholesale regardless of their content, and this
    substitution can't introduce a premature `*/`).
    """
    return _DOUBLE_QUOTED_TOKEN.sub(lambda m: f"`{m.group(1)}`", sql)


# table [AS] alias, from either FROM or JOIN -- catalog/schema prefix (if already qualified)
# is skipped so the captured group is always the bare table name sample_csv.load_column_values
# expects. Global (not scope-aware) on purpose: these BI-tool-generated queries don't reuse
# alias names across nesting levels in practice, and a predicate's real column source is
# always the innermost FROM/JOIN using that alias, wherever in the query text it appears.
_TABLE_ALIAS_PATTERN = re.compile(r"\b(?:FROM|JOIN)\s+(?:[\w.]+\.)?(\w+)\s+(?:AS\s+)?(\w+)\b", re.IGNORECASE)

# alias.column, optionally wrapped in ucase(...)/upper(...) on either or both sides -- the
# consistent style these BI-tool-generated queries use for case-insensitive comparison.
_FUNC_WRAP = r"(?:ucase|upper)\s*\(\s*"
_COL_REF = r"(\w+)\.(\w+)"
_ALIASED_EQUALITY_PREDICATE = re.compile(
    rf"(?:{_FUNC_WRAP})?{_COL_REF}\)?\s*=\s*(?:{_FUNC_WRAP})?'([^']*)'\)?", re.IGNORECASE,
)
_ALIASED_IN_PREDICATE = re.compile(
    rf"(?:{_FUNC_WRAP})?{_COL_REF}\)?\s+IN\s*\(([^)]*)\)", re.IGNORECASE,
)
_MAX_IN_LIST_SUBSTITUTES = 10  # cap so a fix doesn't balloon a real IN list unreasonably

# A predicate inside a single-table subquery/scope often skips the alias entirely (e.g. "(SELECT
# ... FROM cmb_riskrelatedobjects WHERE ucase(entityType) IN (...))" -- unambiguous with only
# one table in scope, so the alias.column form above never matches it at all. Requiring the
# ucase(.../upper(...) wrap here (not optional, unlike the aliased patterns) is a deliberate
# precision guard: a bare `\w+ = 'value'` with no qualifier is too easy to false-positive on
# something that isn't a real column reference, but this specific wrapped-bare-identifier shape
# is consistently a genuine column comparison in these BI-tool-generated queries.
_UNQUALIFIED_EQUALITY_PREDICATE = re.compile(
    rf"{_FUNC_WRAP}(\w+)\s*\)\s*=\s*(?:{_FUNC_WRAP})?'([^']*)'\)?", re.IGNORECASE,
)
_UNQUALIFIED_IN_PREDICATE = re.compile(
    rf"{_FUNC_WRAP}(\w+)\s*\)\s+IN\s*\(([^)]*)\)", re.IGNORECASE,
)
_FROM_TABLE = re.compile(r"\bFROM\s+(?:[\w.]+\.)?(\w+)\b", re.IGNORECASE)
_STARTS_WITH_FUNC_WRAP = re.compile(rf"^{_FUNC_WRAP}", re.IGNORECASE)


def _is_lhs_wrapped(matched_text: str) -> bool:
    """True when the matched predicate's column side is wrapped in ucase(.../upper(... -- the
    wrap is always the first thing in the pattern when present, so checking match.group(0)'s
    own prefix tells us whether THIS specific match actually used it (the aliased patterns
    make the wrap optional, so not every match has one)."""
    return bool(_STARTS_WITH_FUNC_WRAP.match(matched_text))


def _nearest_preceding_table(sql: str, position: int) -> str | None:
    """The table named by the closest FROM before `position` -- a pragmatic (not truly
    scope-aware, since regex can't fully parse nested SQL) stand-in for "which table is this
    unqualified column actually on," but correct for the straightforward nested-SELECT shape
    these BI-tool-generated queries consistently use."""
    matches = list(_FROM_TABLE.finditer(sql, 0, position))
    return matches[-1].group(1) if matches else None


@functools.lru_cache(maxsize=None)
def _cached_column_values(table: str, column: str) -> tuple:
    # SQL identifiers are case-insensitive, but a query's own casing of a table/column
    # reference has no reason to match sample_csv's canonical casing (its _SAMPLE_FILES keys
    # are all lowercase; real column names keep whatever case the real profile data uses).
    # Confirmed live 2026-07-31: a query referenced OrgHierarchy.parentOrgID, but the real
    # table/column are orghierarchy/parentOrgId -- table AND column case both differed, so a
    # direct load_column_values(table, column) call found nothing despite real data existing.
    try:
        rows = load_rows(table.lower())
    except (FileNotFoundError, KeyError, ValueError):
        return ()
    if not rows:
        return ()
    real_column = next((k for k in rows[0].keys() if k.lower() == column.lower()), None)
    if not real_column:
        return ()
    values = {r[real_column] for r in rows if r.get(real_column) not in (None, "")}
    return tuple(sorted(values))


def substitute_unreachable_literal_predicates(sql: str) -> str:
    """
    Real captured queries often hardcode literal filter values (org UUIDs, timezone names,
    enum-like type codes, ...) from whichever real tenant/context they were originally
    captured against -- this project's synthetic data was never generated to match those
    specific values, so a query parses and runs fine but returns 0 rows regardless of ABAC
    claim, no matter how many OTHER predicates on the same query got fixed. Confirmed live
    2026-07-31: fixing just the UUID-shaped predicates left many queries still at 0 rows
    because a SECOND predicate on the same query (e.g. entity_v3.entityTypeReference IN
    ('AISYSTEMS','MODELS',...), when that column's real sample data only ever contains
    'evidence-task-implementation-link') was independently unreachable too -- every predicate
    in an ANDed WHERE clause has to be satisfiable, not just one.

    Resolves each predicate's alias back to its real table via the query's own FROM/JOIN
    clauses, then checks that specific (table, column) pair's real local sample data
    (sample_csv.load_column_values, the same source generate_main_tables.py's categorical
    generation draws from). Handles both `col = 'value'` and `col IN ('v1','v2',...)`, each
    optionally wrapped in ucase(...)/upper(...) (matched case-insensitively either way, since
    that wrapping signals the query's own intent is a case-insensitive comparison).

    Only rewrites when NONE of a predicate's value(s) are confirmed present in real sample
    data for that (table, column) pair -- if at least one already matches, or the pair has no
    local sample data to check against at all, the predicate is left untouched rather than
    guessed at. An IN list gets its values replaced (capped at 10) with real ones, not just
    appended to, since the original values are equally unreachable.

    When the column side is wrapped in ucase(.../upper(..., the substituted value(s) are
    UPPERCASED too, not inserted in their real (often lowercase) case. Confirmed live
    2026-07-31: entity_v3.entityTypeReference's one real value is
    'evidence-task-implementation-link' (all lowercase), but
    "ucase(entityTypeReference) IN ('evidence-task-implementation-link')" still matched ZERO
    rows -- ucase() only transforms the column side, so a lowercase literal compared against
    an uppercased column value never matches. Upper-casing the substitute is always safe here
    regardless of whether the literal side happens to ALSO be wrapped (ucase() is idempotent
    on an already-uppercase string).
    """
    alias_to_table = {}
    for table, alias in _TABLE_ALIAS_PATTERN.findall(sql):
        alias_to_table[alias] = table
        alias_to_table.setdefault(table, table)  # unaliased table.column references too

    def _replace_equality(match):
        alias, column, value = match.group(1), match.group(2), match.group(3)
        table = alias_to_table.get(alias)
        if not table:
            return match.group(0)
        samples = _cached_column_values(table, column)
        if not samples or value.lower() in {s.lower() for s in samples}:
            return match.group(0)
        substitute = samples[0].upper() if _is_lhs_wrapped(match.group(0)) else samples[0]
        return match.group(0).replace(f"'{value}'", f"'{substitute}'", 1)

    def _replace_in_list(match):
        alias, column, list_content = match.group(1), match.group(2), match.group(3)
        table = alias_to_table.get(alias)
        if not table:
            return match.group(0)
        samples = _cached_column_values(table, column)
        if not samples:
            return match.group(0)
        values = re.findall(r"'([^']*)'", list_content)
        samples_lower = {s.lower() for s in samples}
        if any(v.lower() in samples_lower for v in values):
            return match.group(0)
        substitutes = [s.upper() for s in samples] if _is_lhs_wrapped(match.group(0)) else samples
        replacement = ", ".join(f"'{v}'" for v in substitutes[:_MAX_IN_LIST_SUBSTITUTES])
        return match.group(0).replace(list_content, replacement, 1)

    def _replace_unqualified_equality(match):
        column, value = match.group(1), match.group(2)
        table = _nearest_preceding_table(sql, match.start())
        if not table:
            return match.group(0)
        samples = _cached_column_values(table, column)
        if not samples or value.lower() in {s.lower() for s in samples}:
            return match.group(0)
        # unlike the aliased pattern, the wrap is mandatory here -- always uppercase
        return match.group(0).replace(f"'{value}'", f"'{samples[0].upper()}'", 1)

    def _replace_unqualified_in_list(match):
        column, list_content = match.group(1), match.group(2)
        table = _nearest_preceding_table(sql, match.start())
        if not table:
            return match.group(0)
        samples = _cached_column_values(table, column)
        if not samples:
            return match.group(0)
        values = re.findall(r"'([^']*)'", list_content)
        samples_lower = {s.lower() for s in samples}
        if any(v.lower() in samples_lower for v in values):
            return match.group(0)
        replacement = ", ".join(f"'{v.upper()}'" for v in samples[:_MAX_IN_LIST_SUBSTITUTES])
        return match.group(0).replace(list_content, replacement, 1)

    sql = _ALIASED_IN_PREDICATE.sub(_replace_in_list, sql)
    sql = _ALIASED_EQUALITY_PREDICATE.sub(_replace_equality, sql)
    sql = _UNQUALIFIED_IN_PREDICATE.sub(_replace_unqualified_in_list, sql)
    sql = _UNQUALIFIED_EQUALITY_PREDICATE.sub(_replace_unqualified_equality, sql)
    return sql


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
