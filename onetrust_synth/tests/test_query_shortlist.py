import csv
import os
import tempfile

from onetrust_synth import config
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
