"""
Classify every query in onetrust_sanity_run.csv against the 11-table synthetic-dataset scope
and, for the compatible ones, rewrite the schema qualifier so they run directly against the new
abac_onetrust catalog.

Design reference: docs/superpowers/specs/2026-07-23-onetrust-synthetic-dataset-design.md, section 6.

Output: onetrust/onetrust_sanity_run_annotated.csv — same rows as the source file, plus:
  in_scope                  yes/no
  reason                    why excluded, blank if in_scope
  tables_used               real table names referenced (comma-separated), blank if none
  references_nested_columns any of the 6 struct/map/list columns referenced, blank otherwise
  modified_query            schema-qualifier-rewritten query, populated only when in_scope=yes
"""
import csv

import sqlglot
from sqlglot import exp

SOURCE_CSV = "onetrust/onetrust_sanity_run.csv"
OUTPUT_CSV = "onetrust/onetrust_sanity_run_annotated.csv"

TARGET_SCHEMA = "auto_qa_e40yx52dkbjpcqazimno9yvh4k"
DEST_CATALOG = "abac_onetrust"
DEST_SCHEMA = "onetrust_sim"
DEST_MONITORING_SCHEMA = "monitoring"

IN_SCOPE_TABLES = {
    "cmb_assessment", "cmb_controlimplementation", "cmb_inventory",
    "cmb_riskrelatedobjects", "cmb_template", "cmb_v_assessment_v4",
    "cmb_v_inventoryaggregatedrisksummary", "entitylink_v3", "orghierarchy",
    "reportingmoduletoentityreferencemapping_v", "entitygroupconfig",
}

NESTED_COLUMNS = {
    "assessmentsectionreportinformations", "questionmap", "questionrootmap",
    "useridsassociatedwithassessment", "attributes", "personaldataobjects",
}


def clean_lines(f):
    for line in f:
        yield line.replace("\0", "")


def load_rows():
    with open(SOURCE_CSV, newline="", encoding="utf-8", errors="replace") as f:
        reader = csv.DictReader(clean_lines(f))
        return list(reader)


def real_tables(tree):
    """All base-table references, excluding CTE self-references and the $Table template alias."""
    cte_aliases = {c.alias.lower() for c in tree.find_all(exp.CTE) if c.alias}
    tables = []
    for t in tree.find_all(exp.Table):
        db = t.db or None
        name = t.name
        name_l = (name or "").lower()
        if not db and name_l in cte_aliases:
            continue
        if name_l == "$table" or name_l.strip("$") == "table":
            continue
        tables.append((db, name, t))
    return tables


def classify(query_text):
    """Returns (in_scope: bool, reason: str, tables_used: list[str], nested_cols: list[str], tree)."""
    try:
        tree = sqlglot.parse_one(query_text, read="databricks")
    except Exception as e:
        return False, f"sqlglot parse error: {e}", [], [], None

    tables = real_tables(tree)
    if not tables:
        return False, "no real table reference (BI/AAS plumbing query)", [], [], tree

    other_schema = []
    unknown = []
    used = []
    for db, name, _ in tables:
        db_l = (db or "").lower()
        name_l = (name or "").lower()
        if db_l == "monitoring" and name_l == "entitygroupconfig":
            used.append(name)
            continue
        if db_l and db_l != TARGET_SCHEMA.lower():
            other_schema.append(f"{db}.{name}")
            continue
        if name_l not in IN_SCOPE_TABLES:
            unknown.append(name)
            continue
        used.append(name)

    if other_schema:
        return False, f"references table(s) outside our 11, in another schema: {', '.join(other_schema)}", [], [], tree
    if unknown:
        return False, f"references table(s) outside our 11: {', '.join(unknown)}", [], [], tree

    cols = {c.name.lower() for c in tree.find_all(exp.Column)}
    nested_hits = sorted(cols & NESTED_COLUMNS)

    return True, "", sorted(set(used)), nested_hits, tree


def rewrite_schema(tree):
    """Mutates the AST in place: every real table reference gets the abac_onetrust catalog and
    the correct destination schema (monitoring for entitygroupconfig, onetrust_sim otherwise)."""
    cte_aliases = {c.alias.lower() for c in tree.find_all(exp.CTE) if c.alias}
    for t in tree.find_all(exp.Table):
        name_l = (t.name or "").lower()
        if not t.db and name_l in cte_aliases:
            continue
        if name_l == "$table" or name_l.strip("$") == "table":
            continue
        dest_schema = DEST_MONITORING_SCHEMA if name_l == "entitygroupconfig" else DEST_SCHEMA
        t.set("catalog", exp.to_identifier(DEST_CATALOG))
        t.set("db", exp.to_identifier(dest_schema))
    return tree


def main():
    rows = load_rows()
    out_rows = []
    in_scope_count = 0

    for row in rows:
        alias = row.get("query_alias", "")
        q = row.get("query", "") or ""

        if TARGET_SCHEMA.lower() not in q.lower():
            out_rows.append({
                "query_alias": alias,
                "query": q,
                "in_scope": "no",
                "reason": "different tenant schema",
                "tables_used": "",
                "references_nested_columns": "",
                "modified_query": "",
            })
            continue

        in_scope, reason, tables_used, nested_cols, tree = classify(q)
        modified_query = ""
        if in_scope:
            in_scope_count += 1
            rewritten = rewrite_schema(tree)
            modified_query = rewritten.sql(dialect="databricks", pretty=True)

        out_rows.append({
            "query_alias": alias,
            "query": q,
            "in_scope": "yes" if in_scope else "no",
            "reason": reason,
            "tables_used": ", ".join(tables_used),
            "references_nested_columns": ", ".join(nested_cols),
            "modified_query": modified_query,
        })

    with open(OUTPUT_CSV, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=[
            "query_alias", "query", "in_scope", "reason",
            "tables_used", "references_nested_columns", "modified_query",
        ])
        writer.writeheader()
        writer.writerows(out_rows)

    print(f"Total queries: {len(rows)}")
    print(f"In-scope (modified_query populated): {in_scope_count}")
    print(f"Output written to: {OUTPUT_CSV}")


if __name__ == "__main__":
    main()
