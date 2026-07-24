"""
sql_onetrust/oauth_validate.py

Runs the Phase 1 ABAC functional test cases (T1-T8, the OAuth equivalents of
06_test_cases.sql's owner-side checks) AND all 50 real compatible queries
(onetrust/onetrust_sanity_run_annotated.csv) through REAL OAuth custom-claim
identities, authenticated as the service principal via the SQL Statements API --
the same mechanism proven by hand with curl, see docs/deployment/oauth-jdbc-flow.md.

This is an OPERATOR-run script, not part of the Databricks notebook or the
onetrust_synth package: it needs the SP's CLIENT_SECRET, which must live only
in your own shell environment -- never commit it, never paste it into a chat,
never put it in a notebook cell/widget. Prerequisite: sql_onetrust/07_oauth_wiring.sql
must already be applied (get_user_context / abac_row_filter_wrapper_oauth / the
4 repointed policies / SP grants).

Usage:
    export CLIENT_ID="<SP application id>"
    export CLIENT_SECRET="<SP secret -- from your own secret store>"
    export WORKSPACE_HOST="<workspace-host>.azuredatabricks.net"
    export WAREHOUSE_ID="<SQL warehouse id>"
    python3 sql_onetrust/oauth_validate.py
"""
import base64
import csv
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
from onetrust_synth.config import ANNOTATED_QUERIES_CSV

CATALOG_SCHEMA = "abac_onetrust.onetrust_sim"


def _env(name):
    value = os.environ.get(name)
    if not value:
        raise SystemExit(f"Missing required env var: {name}")
    return value


def mint_token(client_id, client_secret, workspace_host, claim):
    url = f"https://{workspace_host}/oidc/v1/token"
    body = urllib.parse.urlencode({
        "grant_type": "client_credentials",
        "scope": "all-apis",
        "custom_claim": json.dumps(claim),
    }).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", "application/x-www-form-urlencoded")
    auth = f"{client_id}:{client_secret}"
    req.add_header("Authorization", "Basic " + base64.b64encode(auth.encode()).decode())
    with urllib.request.urlopen(req, timeout=30) as resp:
        payload = json.loads(resp.read())
    if "access_token" not in payload:
        raise RuntimeError(f"Token mint failed: {payload}")
    return payload["access_token"]


def run_sql(access_token, workspace_host, warehouse_id, statement):
    url = f"https://{workspace_host}/api/2.0/sql/statements"
    body = json.dumps({
        "warehouse_id": warehouse_id,
        "statement": statement,
        "wait_timeout": "30s",
    }).encode("utf-8")
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", f"Bearer {access_token}")
    try:
        with urllib.request.urlopen(req, timeout=45) as resp:
            result = json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return {"status": {"state": "FAILED"}, "error_detail": e.read().decode()}

    # Poll if the statement is still running (should be rare for these small queries).
    statement_id = result.get("statement_id")
    state = result.get("status", {}).get("state")
    while state in ("PENDING", "RUNNING") and statement_id:
        time.sleep(1)
        poll_req = urllib.request.Request(
            f"{url}/{statement_id}", method="GET"
        )
        poll_req.add_header("Authorization", f"Bearer {access_token}")
        with urllib.request.urlopen(poll_req, timeout=30) as resp:
            result = json.loads(resp.read())
        state = result.get("status", {}).get("state")
    return result


def first_row(result):
    data = result.get("result", {}).get("data_array")
    return data[0] if data else None


# --- T1-T8: the OAuth equivalents of 06_test_cases.sql's owner-side checks ---
# Reformulated as count-based SELECTs (no assert_true) so the harness can check
# the returned value in Python. Uses abac_row_filter_wrapper_oauth (the policy's
# live target, see 07_oauth_wiring.sql), not the original test-only wrapper.

CLAIM_OWNER = {"tenant": 1, "user": "u.assessment.owner@example.com", "org": "100",
               "mode": "ABAC", "root": "ASSESSMENT", "permissions": ["TEMPLATE"]}
CLAIM_GROUP_MEMBER = {"tenant": 1, "user": "u.group.member@example.com", "org": "100",
                      "mode": "ABAC", "root": "CONTROL", "permissions": []}
CLAIM_INACTIVE = {"tenant": 1, "user": "u.inactive.grant@example.com", "org": "100",
                   "mode": "ABAC", "root": "ASSESSMENT", "permissions": []}
CLAIM_DISABLED = {"tenant": 1, "user": "u.disabled.mode@example.com", "org": "100",
                   "mode": "DISABLE", "root": "ASSESSMENT", "permissions": []}
CLAIM_DISABLE_PROBE = {"tenant": 1, "user": "u.probe@example.com", "org": "100",
                        "mode": "DISABLE", "root": "ASSETS", "permissions": []}


def build_test_cases(rbac_org_id):
    return [
        dict(
            id="T1", desc="root type, explicit assignment -> seeded assessment IS visible",
            claim=CLAIM_OWNER,
            sql=f"""SELECT count(*) FROM {CATALOG_SCHEMA}.cmb_assessment
                    WHERE id = (SELECT entityId FROM {CATALOG_SCHEMA}.ABAC_EntitySubjectAssignment
                                WHERE subjectId = 'u.assessment.owner@example.com' AND objectType = 'ASSESSMENT' LIMIT 1)
                    AND {CATALOG_SCHEMA}.abac_row_filter_wrapper_oauth(id, 'ASSESSMENT', '100')""",
            check=lambda r: int(r[0]) == 1,
        ),
        dict(
            id="T2", desc="root type, no assignment -> a DIFFERENT assessment is NOT visible",
            claim=CLAIM_OWNER,
            sql=f"""SELECT count(*) FROM {CATALOG_SCHEMA}.cmb_assessment
                    WHERE id != (SELECT entityId FROM {CATALOG_SCHEMA}.ABAC_EntitySubjectAssignment
                                 WHERE subjectId = 'u.assessment.owner@example.com' AND objectType = 'ASSESSMENT' LIMIT 1)
                    AND id NOT IN (SELECT entityId FROM {CATALOG_SCHEMA}.ABAC_EntitySubjectAssignment
                                   WHERE subjectId = 'u.assessment.owner@example.com')
                    AND {CATALOG_SCHEMA}.abac_row_filter_wrapper_oauth(id, 'ASSESSMENT', '100')""",
            check=lambda r: int(r[0]) == 0,
        ),
        dict(
            id="T3", desc="non-root type, IN permissions array -> ALL cmb_template rows visible",
            claim=CLAIM_OWNER,
            sql=f"""SELECT count(*), count(*) FILTER (WHERE {CATALOG_SCHEMA}.abac_row_filter_wrapper_oauth(id, 'TEMPLATE', '100'))
                    FROM {CATALOG_SCHEMA}.cmb_template""",
            check=lambda r: int(r[0]) == int(r[1]),
        ),
        dict(
            id="T4", desc="non-root type, NOT in permissions array -> ZERO controls visible",
            claim=CLAIM_OWNER,
            sql=f"""SELECT count(*) FROM {CATALOG_SCHEMA}.cmb_controlimplementation
                    WHERE {CATALOG_SCHEMA}.abac_row_filter_wrapper_oauth(id, 'CONTROL', '100')""",
            check=lambda r: int(r[0]) == 0,
        ),
        dict(
            id="T5", desc="group membership -> a member of test_group_1 sees the group-assigned control",
            claim=CLAIM_GROUP_MEMBER,
            sql=f"""SELECT count(*) FROM {CATALOG_SCHEMA}.cmb_controlimplementation
                    WHERE id = (SELECT entityId FROM {CATALOG_SCHEMA}.ABAC_EntitySubjectAssignment
                                WHERE subjectId = 'test_group_1' AND objectType = 'CONTROL' LIMIT 1)
                    AND {CATALOG_SCHEMA}.abac_row_filter_wrapper_oauth(id, 'CONTROL', '100')""",
            check=lambda r: int(r[0]) == 1,
        ),
        dict(
            id="T6", desc="isActive=false assignment -> must NOT grant visibility",
            claim=CLAIM_INACTIVE,
            sql=f"""SELECT count(*) FROM {CATALOG_SCHEMA}.cmb_assessment
                    WHERE id = (SELECT entityId FROM {CATALOG_SCHEMA}.ABAC_EntitySubjectAssignment
                                WHERE subjectId = 'u.inactive.grant@example.com' LIMIT 1)
                    AND {CATALOG_SCHEMA}.abac_row_filter_wrapper_oauth(id, 'ASSESSMENT', '100')""",
            check=lambda r: int(r[0]) == 0,
        ),
        dict(
            id="T7", desc="DISABLE mode -> everything visible regardless of assignments",
            claim=CLAIM_DISABLED,
            sql=f"""SELECT count(*), count(*) FILTER (WHERE {CATALOG_SCHEMA}.abac_row_filter_wrapper_oauth(id, 'ASSESSMENT', '100'))
                    FROM {CATALOG_SCHEMA}.cmb_assessment""",
            check=lambda r: int(r[0]) == int(r[1]),
        ),
        dict(
            id="T8", desc="RBAC_ABAC over the real orgHierarchy ancestor closure",
            claim={"tenant": 1, "user": "u.rbac.viewer@example.com", "org": rbac_org_id,
                   "mode": "RBAC_ABAC", "root": "ASSETS", "permissions": []},
            sql=f"""SELECT count(*) FROM {CATALOG_SCHEMA}.cmb_v_inventoryaggregatedrisksummary
                    WHERE upper(inventoryType) = 'ASSETS'
                    AND {CATALOG_SCHEMA}.abac_row_filter_wrapper_oauth(entityID, 'ASSETS', orgID)""",
            check=lambda r: int(r[0]) >= 1,
        ),
    ]


def load_compatible_queries():
    with open(ANNOTATED_QUERIES_CSV, newline="", encoding="utf-8", errors="replace") as f:
        reader = csv.DictReader(f)
        return [row for row in reader if row.get("in_scope") == "yes"]


def main():
    client_id = _env("CLIENT_ID")
    client_secret = _env("CLIENT_SECRET")
    workspace_host = _env("WORKSPACE_HOST").rstrip("/")
    warehouse_id = _env("WAREHOUSE_ID")

    failures = 0

    # T8 needs a real orgID from the live data -- fetch it unfiltered via a DISABLE claim
    # (a legitimate, real claim value; DISABLE bypasses the filter by design, see abac_row_filter).
    print("Resolving a real orgID for T8 (via a DISABLE-mode probe)...")
    probe_token = mint_token(client_id, client_secret, workspace_host, CLAIM_DISABLE_PROBE)
    probe_result = run_sql(
        probe_token, workspace_host, warehouse_id,
        f"SELECT orgID FROM {CATALOG_SCHEMA}.cmb_v_inventoryaggregatedrisksummary LIMIT 1",
    )
    row = first_row(probe_result)
    if not row:
        print(f"  Could not resolve orgID, aborting T8 setup: {probe_result}")
        sys.exit(1)
    rbac_org_id = row[0]
    print(f"  orgID = {rbac_org_id}")

    print("\n=== T1-T8: ABAC functional test cases via real OAuth claims ===")
    for case in build_test_cases(rbac_org_id):
        token = mint_token(client_id, client_secret, workspace_host, case["claim"])
        result = run_sql(token, workspace_host, warehouse_id, case["sql"])
        if result.get("status", {}).get("state") != "SUCCEEDED":
            print(f"[{case['id']}] ERROR - {case['desc']}\n         {result}")
            failures += 1
            continue
        row = first_row(result)
        ok = bool(row) and case["check"](row)
        print(f"[{case['id']}] {'PASS' if ok else 'FAIL'} - {case['desc']} (row={row})")
        if not ok:
            failures += 1

    print("\n=== 50 real compatible queries, run as the SP under a live claim ===")
    queries = load_compatible_queries()
    print(f"Loaded {len(queries)} in-scope queries (claim: u.assessment.owner@example.com)")
    token = mint_token(client_id, client_secret, workspace_host, CLAIM_OWNER)
    query_failures = 0
    for i, row in enumerate(queries, start=1):
        alias = row["query_alias"]
        result = run_sql(token, workspace_host, warehouse_id, row["modified_query"])
        state = result.get("status", {}).get("state")
        if state != "SUCCEEDED":
            error = result.get("status", {}).get("error", result.get("error_detail", result))
            print(f"[{i}/{len(queries)}] FAIL {alias}: {error}")
            query_failures += 1
        else:
            row_count = result.get("manifest", {}).get("total_row_count")
            print(f"[{i}/{len(queries)}] OK   {alias}: {row_count} rows")
    failures += query_failures

    print(f"\n=== Summary: T1-T8 + 50 queries -> {failures} failing checks ===")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
