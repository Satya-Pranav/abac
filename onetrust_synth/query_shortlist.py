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
    # user must be a MEMBER of test_group_1 (a USER_GROUP subject), not the group's own name --
    # abac_row_filter's group-membership branch joins UserGroupMembers on ctx.user = ugm.memberId,
    # so a claim naming the group itself never matches. governance_sql.build_seed_principals_sql
    # seeds exactly this member (u.group.member@example.com) as test_group_1's member; the two
    # OnetrustCases.java assertions of the same underlying fact (functionalCases's OT-T5,
    # abacGroupCases's OT-A5) already use this claim correctly.
    "cmb_controlimplementation": '{"tenant":1,"user":"u.group.member@example.com","org":"100","mode":"ABAC","root":"CONTROL","permissions":[]}',
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


def _catalog_qualified_query(row: dict, catalog: str) -> str:
    """
    query_rewrite.build_modified_query returns the CSV's existing modified_query column verbatim,
    ignoring the catalog argument, whenever that column is already populated -- correct for Task 8's
    own scope (only the 307 originally-excluded rows, which always have an EMPTY modified_query and
    so always fall through to catalog_qualify()). But onetrust_sanity_run_annotated.csv's 50
    in_scope=yes rows each already carry a NON-empty modified_query, hardcoded against config.CATALOG
    ("abac_onetrust", Phase 1's catalog) -- confirmed directly against the real file, every occurrence
    a clean "abac_onetrust.<onetrust_sim|monitoring>." prefix. Left alone, all 50 of those rows'
    shortlisted queries would silently target the OLD catalog instead of the one under test, defeating
    the point of this shortlist (design doc section 7 step 3 / section 8: "query | catalog-qualified
    modified_query"). So re-point any already-baked-in old-catalog prefix to the target catalog here;
    a no-op for rows freshly derived via catalog_qualify(), which never contain the old prefix.
    """
    sql = build_modified_query(row, catalog)
    if catalog == config.CATALOG:
        return sql
    return sql.replace(f"{config.CATALOG}.", f"{catalog}.")


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
            "query": _catalog_qualified_query(row, catalog),
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
