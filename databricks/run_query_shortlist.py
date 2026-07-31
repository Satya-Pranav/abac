#!/usr/bin/env python3
# databricks/run_query_shortlist.py
#
# Runs the 115-query shortlist (onetrust_synth.query_shortlist.build_shortlist_rows) against a
# real Unity Catalog catalog through the OAuth-claim-bearing SQL Statement Execution API --
# same custom_claim token-mint mechanism as run_claim_query.sh/run_oauth_functions_via_sp.sh,
# batched here over every shortlisted query so each one actually runs AS its associated seeded
# test principal.
#
# Why not just spark.sql() in a notebook (query_shortlist.run_shortlist)? That runs as the
# notebook/workspace owner, and owners/metastore admins bypass row filters entirely (see
# docs/deployment/oauth-jdbc-flow.md section 5, rule 2) -- it would prove the SQL is
# syntactically valid against the real-scale catalog, but not that ABAC is actually enforcing
# anything. Going through a real claim-bearing token is the only way to test that.
#
# Each query is wrapped as `SELECT count(*) FROM (<query>) AS _shortlist_sub` to match
# run_shortlist's own semantics (an observed row count, not the full result set) and to keep
# every response small regardless of what the underlying query selects.
#
# Tokens are minted once per DISTINCT claim and reused across every query sharing it (7-8
# distinct claims across all 115 queries: one per governed table actually referenced + the
# DISABLE probe for queries that don't touch a governed table), not once per query.
#
# Also records per-query wall-clock duration (submit through terminal state, i.e. the full
# round trip a real caller experiences -- queueing + execution + network, not just server-side
# execution time) as a basic performance signal: min/max/avg + the slowest N queries printed at
# the end, and a duration_seconds column in the output CSV.
#
# Usage (same 4 env vars as run_claim_query.sh; CLIENT_ID must be the SP the table policies are
# bound TO, same as everywhere else in this project):
#   export CLIENT_ID=... CLIENT_SECRET=... WORKSPACE_HOST=... WAREHOUSE_ID=...
#   python3 databricks/run_query_shortlist.py abac_onetrust_scale --out shortlist_results.csv
#
# Requires no pip installs -- reuses onetrust_synth.query_shortlist for the claim-pairing/
# catalog-qualification logic already tested in that module, and shells out to curl for the
# actual HTTPS calls (subprocess, not urllib) -- macOS python.org framework builds don't trust
# the system keychain by default and fail with SSLCertVerificationError on networks with a
# TLS-inspecting proxy (confirmed live 2026-07-31), while curl already works fine here (same
# calls run_claim_query.sh/run_oauth_functions_via_sp.sh make successfully), so reuse that
# instead of fighting Python's SSL trust store on someone else's machine.

import argparse
import csv
import json
import os
import subprocess
import sys
import time

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from onetrust_synth.query_shortlist import build_shortlist_rows

_CSV_FIELDNAMES = [
    "query_id", "source", "tables_used", "claim", "query",
    "expected_or_observed", "verified_status", "duration_seconds",
]


def _curl_json(args: list) -> dict:
    # -S (not just -s) so curl still prints its own error line on failure instead of nothing --
    # -s alone suppresses that too, which is why the original version's error message here had
    # to guess at the cause.
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("catalog", help="e.g. abac_onetrust_scale")
    parser.add_argument("--out", default="query_shortlist_results.csv")
    parser.add_argument(
        "--claim-mode", choices=["narrow", "broad"], default="narrow",
        help="narrow (default): SEEDED_CLAIMS_BY_TABLE, each claim makes exactly one seeded "
             "row visible -- for ABAC-correctness testing. broad: perf_claim_for_query, each "
             "claim makes every row of the queried table visible -- for performance testing, "
             "where a real query's own filters/grouping need actual row volume to run against.",
    )
    args = parser.parse_args()

    client_id = os.environ["CLIENT_ID"]
    client_secret = os.environ["CLIENT_SECRET"]
    workspace_host = os.environ["WORKSPACE_HOST"]
    warehouse_id = os.environ["WAREHOUSE_ID"]

    rows = build_shortlist_rows(args.catalog, claim_mode=args.claim_mode)
    print(f"Loaded {len(rows)} shortlisted queries against {args.catalog}.")

    distinct_claims = sorted({row["claim"] for row in rows})
    print(f"Minting {len(distinct_claims)} distinct claim tokens (reused across all {len(rows)} queries)...")
    token_by_claim = {claim: mint_token(workspace_host, client_id, client_secret, claim) for claim in distinct_claims}
    print("Tokens minted.")

    passed = failed = 0
    for i, row in enumerate(rows, 1):
        token = token_by_claim[row["claim"]]
        wrapped = f"SELECT count(*) AS n FROM ({row['query'].rstrip().rstrip(';')}) AS _shortlist_sub"

        start = time.time()
        try:
            state, result = submit_statement(workspace_host, token, warehouse_id, wrapped)
        except (RuntimeError, json.JSONDecodeError) as e:
            state, result = "REQUEST_ERROR", {"status": {"error": {"message": str(e)[:300]}}}
        row["duration_seconds"] = f"{time.time() - start:.2f}"

        if state == "SUCCEEDED":
            data = result.get("result", {}).get("data_array") or [[None]]
            row["expected_or_observed"] = str(data[0][0])
            row["verified_status"] = "PASS"
            passed += 1
        else:
            err = result.get("status", {}).get("error", {}).get("message", str(result))[:300]
            row["expected_or_observed"] = ""
            row["verified_status"] = f"FAIL: {err}"
            failed += 1

        print(f"[{i}/{len(rows)}] {row['query_id']} ({row['duration_seconds']}s): {row['verified_status'][:100]}")

    with open(args.out, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=_CSV_FIELDNAMES)
        writer.writeheader()
        for row in rows:
            writer.writerow({k: row.get(k, "") for k in _CSV_FIELDNAMES})

    durations = sorted((float(row["duration_seconds"]), row["query_id"]) for row in rows)
    total = sum(d for d, _ in durations)
    print(f"\nDone: {passed} passed, {failed} failed. Results written to {args.out}")
    print(f"\nPerformance: total {total:.1f}s, avg {total / len(rows):.2f}s, "
          f"min {durations[0][0]:.2f}s, max {durations[-1][0]:.2f}s")
    print("Slowest 5:")
    for d, qid in durations[-5:][::-1]:
        print(f"  {d:.2f}s  {qid}")


if __name__ == "__main__":
    main()
