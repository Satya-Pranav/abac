"""
Pairs every shortlisted real query with a claim, then (when a live Spark/Unity Catalog session
is available — see run_shortlist) executes each and records pass/fail. Design doc section 7-8.
"""
import csv
import json

from onetrust_synth import config
from onetrust_synth.query_rewrite import (
    load_all_annotated_queries, is_now_eligible, build_modified_query, tables_referenced,
    extract_tables_from_query_schema_refs, normalize_double_quoted_identifiers,
    substitute_unreachable_literal_predicates, substitute_unreachable_locate_predicates,
)

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

# Table -> every real object_type value that table's type column can actually take in the
# SYNTHETIC data specifically (not the full real-world vocabulary where they differ -- bounded
# by the same limited real sample main_tables.build_generic_table draws categorical values
# from). Used by perf_claim_for_query to grant "see every row of this table" visibility via
# abac_row_filter's permissions branch (ctx.root <> object_type AND
# array_contains(ctx.permissions, object_type)) -- unlike SEEDED_CLAIMS_BY_TABLE's claims,
# which by design only ever make ONE seeded row visible (for ABAC-correctness testing), this
# is for performance testing, where a real query's own filters/grouping need actual row volume
# to run against. Single-literal-type tables need only their one type (matching _POLICY_SPEC's
# literal_type); per-row-varying tables need every value entity_type_to_object_type() can
# normalize their type column to -- cmb_riskrelatedobjects.entityType's real ndv=9 sample
# ('DATASETS','Engagement','GRA','INCIDENT','INVENTORY','Incident','MODELS','PIA','projects')
# collapses to 8 after uppercasing ('Incident'/'INCIDENT' merge); entitylink_v3's real
# vocabulary has 5 values but the same 500-row sample the generator itself draws from only
# ever captured 'ControlTemplate', so that's genuinely the only value the synthetic column
# contains (same caveat documented in registries.build_entity_registry).
_ALL_OBJECT_TYPES_BY_TABLE = {
    "cmb_assessment": ["ASSESSMENT"],
    "cmb_controlimplementation": ["CONTROL"],
    "cmb_template": ["TEMPLATE"],
    "cmb_v_inventoryaggregatedrisksummary": ["ASSETS", "VENDORS", "PROCESSING-ACTIVITIES"],
    "cmb_riskrelatedobjects": ["DATASETS", "ENGAGEMENT", "GRA", "INCIDENT", "INVENTORY", "MODELS", "PIA", "PROJECTS"],
    "cmb_inventory": ["ASSETS", "VENDORS", "PROCESSING-ACTIVITIES"],
    "cmb_v_assessment_v4": ["ASSESSMENT"],
    "entitylink_v3": ["CONTROLTEMPLATE"],
}
# Guaranteed to never equal any real object_type, so the permissions branch (not the root
# branch's explicit-assignment/RBAC_ABAC paths) is what grants visibility here.
_PERF_PROBE_ROOT = "__PERF_PROBE__"


def perf_claim_for_query(tables_used: str, query: str = "") -> str:
    """
    A "see every row" claim for performance testing. mode stays "ABAC" (not "DISABLE") so the
    row filter still evaluates the permissions branch's array_contains(...) per row --
    DISABLE short-circuits before that (and before the root branch's EXISTS join too), which
    would under-represent real query cost against a governed table.
    """
    tables = tables_referenced(tables_used) or extract_tables_from_query_schema_refs(query)
    for table in tables:
        types = _ALL_OBJECT_TYPES_BY_TABLE.get(table.lower()) or _ALL_OBJECT_TYPES_BY_TABLE.get(table)
        if types:
            return json.dumps({
                "tenant": 1, "user": "perf-test", "org": "100",
                "mode": "ABAC", "root": _PERF_PROBE_ROOT, "permissions": types,
            })
    return _DISABLE_PROBE_CLAIM


def claim_for_query(tables_used: str, query: str = "") -> str:
    # tables_used is often empty by construction, not just missing -- the 64 "different tenant
    # schema" shortlist rows (query_rewrite.is_now_eligible) never populate it, since that
    # exclusion reason's tables only ever show up in the raw query text itself (auto_qa_<hash>.
    # <table>/monitoring.<table> refs), never in the CSV's own tables_used column. Confirmed
    # live 2026-07-31: without this fallback, 74/115 shortlisted queries silently fell back to
    # the DISABLE probe claim instead of a real per-table claim, even though most of them do
    # reference a governed table -- just not somewhere claim_for_query was looking.
    tables = tables_referenced(tables_used) or extract_tables_from_query_schema_refs(query)
    for table in tables:
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
    sql = substitute_unreachable_locate_predicates(
        substitute_unreachable_literal_predicates(normalize_double_quoted_identifiers(build_modified_query(row, catalog)))
    )
    if catalog == config.CATALOG:
        return sql
    return sql.replace(f"{config.CATALOG}.", f"{catalog}.")


def build_shortlist_rows(catalog: str, claim_mode: str = "narrow") -> list[dict]:
    """
    claim_mode="narrow" (default): SEEDED_CLAIMS_BY_TABLE's claims, each making exactly one
    seeded row visible -- for ABAC-correctness testing (proving the row filter restricts
    correctly). claim_mode="broad": perf_claim_for_query's "see every row" claims -- for
    performance testing, where a real query's own filters/grouping need actual row volume.
    """
    if claim_mode not in ("narrow", "broad"):
        raise ValueError(f"claim_mode must be 'narrow' or 'broad', got {claim_mode!r}")
    claim_fn = perf_claim_for_query if claim_mode == "broad" else claim_for_query

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
            "claim": claim_fn(row.get("tables_used", ""), row.get("query", "")),
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
