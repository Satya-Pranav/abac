#!/usr/bin/env python3
# databricks/run_gen_esa_queries.py
#
# Runs every GEN-* row (query_id prefix) in "abac shortilited queries - non_zero_rows.csv"
# against a real Unity Catalog catalog through the OAuth-claim-bearing SQL Statement Execution
# API -- same mechanism as run_claim_query.sh/run_query_shortlist.py. Covers two families:
#   - GEN-ESA-*  (9 rows): cmb_v_assessmentquestionresponse_v3, now real-policy-governed --
#     see governance_sql.py's _TAG_SPEC/_POLICY_SPEC extension and
#     databricks/deploy_gen_big_tables_extension.py, which MUST be run first.
#   - GEN-BIG-*  (9 rows): complex 3-4 table joins with aggregation across the big
#     assessment-family tables, >=2 of which have active row filters.
# Fills in the CSV's `m` (observed row count), `verified_status`, and `duration_seconds`
# columns in place for just those 18 rows; the other rows are read back and rewritten unchanged.
#
# Each query is wrapped as `SELECT count(*) FROM (<query>) AS _sub` to keep the response small
# and get a single observed row count, same convention as run_query_shortlist.py.
#
# Usage:
#   export CLIENT_ID=d5a89628-289b-472c-bf22-33a4eeaea220
#   export CLIENT_SECRET=...       # never hard-code -- from a secret store
#   export WORKSPACE_HOST=<workspace>.azuredatabricks.net
#   export WAREHOUSE_ID=<a running SQL warehouse id>
#   python3 databricks/run_gen_esa_queries.py

import csv
import json
import os
import subprocess
import sys
import time

_CSV_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "abac shortilited queries - non_zero_rows.csv"
)
_CSV_FIELDNAMES = ["query_id", "source", "tables_used", "claim", "query", "m", "verified_status", "duration_seconds"]


def _curl_json(args: list) -> dict:
    result = subprocess.run(["curl", "-sSf"] + args, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"curl failed (exit {result.returncode}): {result.stderr.strip()}")
    return json.loads(result.stdout)


def mint_token(workspace_host: str, client_id: str, client_secret: str, custom_claim: str) -> str:
    result = _curl_json([
        "-u", f"{client_id}:{client_secret}",
        "-X", "POST", f"https://{workspace_host}/oidc/v1/token",
        "--data-urlencode", "grant_type=client_credentials",
        "--data-urlencode", "scope=all-apis",
        "--data-urlencode", f"custom_claim={custom_claim}",
    ])
    return result["access_token"]


def _get_json(url: str, token: str) -> dict:
    return _curl_json(["-H", f"Authorization: Bearer {token}", url])


def submit_statement(workspace_host: str, token: str, warehouse_id: str, statement: str) -> tuple:
    payload = json.dumps({"warehouse_id": warehouse_id, "statement": statement, "wait_timeout": "30s"})
    result = _curl_json([
        "-X", "POST", f"https://{workspace_host}/api/2.0/sql/statements",
        "-H", f"Authorization: Bearer {token}",
        "-H", "Content-Type: application/json",
        "-d", payload,
    ])

    statement_id = result.get("statement_id", "")
    state = result.get("status", {}).get("state", "UNKNOWN")
    while state in ("PENDING", "RUNNING"):
        time.sleep(2)
        result = _get_json(f"https://{workspace_host}/api/2.0/sql/statements/{statement_id}", token)
        state = result.get("status", {}).get("state", "UNKNOWN")
    return state, result


def main():
    client_id = os.environ["CLIENT_ID"]
    client_secret = os.environ["CLIENT_SECRET"]
    workspace_host = os.environ["WORKSPACE_HOST"]
    warehouse_id = os.environ["WAREHOUSE_ID"]

    with open(_CSV_PATH, newline="", encoding="utf-8") as f:
        all_rows = list(csv.DictReader(f))

    target_rows = [r for r in all_rows if r["query_id"].startswith("GEN-")]
    print(f"Found {len(target_rows)} GEN-* rows out of {len(all_rows)} total.")

    distinct_claims = sorted({row["claim"] for row in target_rows})
    print(f"Minting {len(distinct_claims)} distinct claim tokens...")
    token_by_claim = {claim: mint_token(workspace_host, client_id, client_secret, claim) for claim in distinct_claims}
    print("Tokens minted.")

    passed = failed = 0
    for i, row in enumerate(target_rows, 1):
        token = token_by_claim[row["claim"]]
        wrapped = f"SELECT count(*) AS n FROM ({row['query'].rstrip().rstrip(';')}) AS _sub"

        start = time.time()
        try:
            state, result = submit_statement(workspace_host, token, warehouse_id, wrapped)
        except (RuntimeError, json.JSONDecodeError) as e:
            state, result = "REQUEST_ERROR", {"status": {"error": {"message": str(e)[:300]}}}
        row["duration_seconds"] = f"{time.time() - start:.2f}"

        if state == "SUCCEEDED":
            data = result.get("result", {}).get("data_array") or [[None]]
            row["m"] = str(data[0][0])
            row["verified_status"] = "PASS"
            passed += 1
        else:
            err = result.get("status", {}).get("error", {}).get("message", str(result))[:300]
            row["m"] = ""
            row["verified_status"] = f"FAIL: {err}"
            failed += 1

        print(f"[{i}/{len(target_rows)}] {row['query_id']} ({row['duration_seconds']}s) "
              f"-> m={row['m']}: {row['verified_status'][:100]}")

    with open(_CSV_PATH, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=_CSV_FIELDNAMES)
        writer.writeheader()
        for row in all_rows:
            writer.writerow({k: row.get(k, "") for k in _CSV_FIELDNAMES})

    print(f"\nDone: {passed} passed, {failed} failed. Results written back into {_CSV_PATH}")


if __name__ == "__main__":
    main()
