import csv
import json
import os
import tempfile

from onetrust_synth import config
from onetrust_synth.query_shortlist import (
    SEEDED_CLAIMS_BY_TABLE, claim_for_query, perf_claim_for_query, build_shortlist_rows, write_shortlist_csv,
)


def test_seeded_claims_cover_all_8_governed_tables():
    assert set(SEEDED_CLAIMS_BY_TABLE) == {
        "cmb_assessment", "cmb_controlimplementation", "cmb_template",
        "cmb_v_inventoryaggregatedrisksummary", "cmb_riskrelatedobjects",
        "cmb_inventory", "cmb_v_assessment_v4", "entitylink_v3",
    }
    assert '"user":"u.assessment.owner@example.com"' in SEEDED_CLAIMS_BY_TABLE["cmb_assessment"].replace(" ", "")


def test_cmb_controlimplementation_claim_names_a_group_member_not_the_group_itself():
    # Regression guard: abac_row_filter's group-membership branch requires ctx.user to be a
    # MEMBER of the USER_GROUP subject (joined via UserGroupMembers.memberId), not the group's
    # own subjectId -- a claim naming "test_group_1" itself would never match. The seed
    # (governance_sql.build_seed_principals_sql) enrolls u.group.member@example.com as
    # test_group_1's member, same claim OnetrustCases.java's OT-T5/OT-A5 already assert correctly.
    claim = SEEDED_CLAIMS_BY_TABLE["cmb_controlimplementation"]
    assert '"user":"u.group.member@example.com"' in claim.replace(" ", "")
    assert '"user":"test_group_1"' not in claim.replace(" ", "")


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


def test_build_shortlist_rows_requalifies_stale_catalog_in_reused_modified_query():
    """
    Real-CSV regression check (not a synthetic fixture): all 50 in_scope=yes rows in
    onetrust_sanity_run_annotated.csv carry a non-empty modified_query already hardcoded against
    config.CATALOG ("abac_onetrust", Phase 1's catalog). query_rewrite.build_modified_query returns
    that verbatim regardless of the catalog argument (correct for Task 8's own narrower scope, where
    the newly-eligible subset always has an EMPTY modified_query). Left un-requalified here, every one
    of those 50 rows' shortlisted "query" would silently point at the OLD catalog instead of the one
    under test, defeating the point of the shortlist (design doc section 7 step 3 / section 8).
    """
    rows = build_shortlist_rows("abac_onetrust_scale")
    real_query_rows = [r for r in rows if r["source"] == "real_query" and r["query"].strip()]
    assert len(real_query_rows) >= 50
    for r in real_query_rows:
        assert f"{config.CATALOG}.onetrust_sim." not in r["query"]
        assert f"{config.CATALOG}.monitoring." not in r["query"]


def test_perf_claim_for_query_grants_broad_access_via_permissions_branch():
    # For a single-literal-type table, the perf claim's root must NOT equal that type (else
    # the root branch's narrow explicit-assignment path would apply instead of the intended
    # permissions branch), and permissions must contain exactly that type.
    claim = json.loads(perf_claim_for_query("cmb_assessment"))
    assert claim["mode"] == "ABAC"
    assert claim["root"] != "ASSESSMENT"
    assert claim["permissions"] == ["ASSESSMENT"]


def test_perf_claim_for_query_covers_every_synthetic_value_for_per_row_type_table():
    # cmb_riskrelatedobjects.entityType varies per row (not a single literal) -- the perf
    # claim needs every value entity_type_to_object_type() can normalize it to, or rows of
    # types not listed would still be filtered out despite the "broad access" intent.
    claim = json.loads(perf_claim_for_query("cmb_riskrelatedobjects"))
    assert set(claim["permissions"]) == {
        "DATASETS", "ENGAGEMENT", "GRA", "INCIDENT", "INVENTORY", "MODELS", "PIA", "PROJECTS",
    }


def test_perf_claim_for_query_falls_back_to_disable_when_ungoverned():
    claim = perf_claim_for_query("orghierarchy")
    assert claim == '{"tenant":1,"user":"probe","org":"100","mode":"DISABLE","root":"Customer","permissions":[]}'


def test_build_shortlist_rows_broad_mode_uses_perf_claims_not_seeded_claims():
    rows = build_shortlist_rows("abac_onetrust_scale", claim_mode="broad")
    seeded_claim_values = set(SEEDED_CLAIMS_BY_TABLE.values())
    governed_rows = [r for r in rows if json.loads(r["claim"]).get("permissions")]
    assert governed_rows  # sanity: at least one shortlisted query hits a governed table
    for r in governed_rows:
        assert r["claim"] not in seeded_claim_values


def test_build_shortlist_rows_rejects_unknown_claim_mode():
    try:
        build_shortlist_rows("abac_onetrust_scale", claim_mode="bogus")
        assert False, "expected ValueError"
    except ValueError:
        pass


def test_build_shortlist_rows_normalizes_double_quoted_identifiers():
    """
    Real-CSV regression check: Q894769-192 (a "different tenant schema" newly-eligible row)
    uses a BI-tool-generated derived-table alias literally named "$Table", double-quoted
    throughout -- Databricks' default SQL dialect parses double quotes as string literals,
    not identifiers, so this failed with PARSE_SYNTAX_ERROR when actually run against a real
    warehouse (confirmed live 2026-07-31). Locks in that build_shortlist_rows converts these
    to backtick-quoted identifiers instead.
    """
    rows = build_shortlist_rows("abac_onetrust_scale")
    row = next(r for r in rows if r["query_id"] == "Q894769-192-20260518.123121.785")
    assert '"' not in row["query"]
    assert "`$Table`" in row["query"]


def test_build_shortlist_rows_substitutes_unreachable_uuid_predicates():
    """
    Real-CSV regression check: Q894769-192 hardcodes entity_v3.parentOrgID =
    'c862237b-9c3e-49fa-bfd1-8161f4245f3e' -- a real customer's org id from whichever tenant
    this query was originally captured against, never generated into this project's synthetic
    data. Confirmed live 2026-07-31: this exact pattern made 47+ shortlisted queries return 0
    rows regardless of ABAC claim. Locks in that build_shortlist_rows swaps it for a value
    confirmed present in entity_v3.parentOrgID's real local sample data.
    """
    rows = build_shortlist_rows("abac_onetrust_scale")
    row = next(r for r in rows if r["query_id"] == "Q894769-192-20260518.123121.785")
    assert "c862237b-9c3e-49fa-bfd1-8161f4245f3e" not in row["query"]
    assert "b99df4a4-2bf5-4c08-9483-bd636470bc11" in row["query"]


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
