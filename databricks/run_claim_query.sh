#!/usr/bin/env bash
# databricks/run_claim_query.sh
#
# Runs one SQL statement against Unity Catalog, authenticated as a service principal's OAuth
# M2M token minted with a specific custom_claim -- same mint mechanism as
# run_oauth_functions_via_sp.sh (see that script's header for why custom_claim is required at
# all), but here the claim's *content* matters: it drives get_user_context() ->
# abac_row_filter_wrapper_oauth -> abac_row_filter, so this is how you validate ABAC row
# filtering as a specific simulated identity from outside a JDBC client. See
# docs/deployment/oauth-jdbc-flow.md sections 2 and 6 for the claim shape and the seeded test
# principals to exercise it against.
#
# Usage (same 4 env vars as run_oauth_functions_via_sp.sh; CLIENT_ID must be the SP the target
# table's policy is bound TO, or the row filter never even applies -- owners/admins bypass it):
#   export CLIENT_ID=<the SP bound TO the table policies>
#   export CLIENT_SECRET=<its secret>                        # never hard-code -- from a secret store
#   export WORKSPACE_HOST=<workspace>.azuredatabricks.net
#   export WAREHOUSE_ID=<a running SQL warehouse id>
#   ./databricks/run_claim_query.sh '<claim JSON>' "<SQL statement>"
#
# Example (allow-test against abac_onetrust_scale, per governance_sql.py's phase2-test-seed --
# u.assessment.owner@example.com has an explicit ESA grant on one cmb_assessment row):
#   ./databricks/run_claim_query.sh \
#     '{"tenant":1,"user":"u.assessment.owner@example.com","org":"100","mode":"ABAC","root":"ASSESSMENT","permissions":[]}' \
#     "SELECT count(*) AS visible_rows FROM abac_onetrust_scale.onetrust_sim.cmb_assessment"
#
# Deny variant: swap "user" for someone not in the seed -> expect 0.
# DISABLE sanity: set "mode":"DISABLE" -> expect the table's full row count (filter bypassed).

set -euo pipefail

CLAIM="${1:?Usage: $0 '<claim JSON>' \"<SQL statement>\"}"
STATEMENT="${2:?Usage: $0 '<claim JSON>' \"<SQL statement>\"}"

: "${CLIENT_ID:?CLIENT_ID env var required}"
: "${CLIENT_SECRET:?CLIENT_SECRET env var required}"
: "${WORKSPACE_HOST:?WORKSPACE_HOST env var required}"
: "${WAREHOUSE_ID:?WAREHOUSE_ID env var required}"

echo "Minting OAuth token for ${CLIENT_ID} with claim: ${CLAIM}" >&2
TOKEN=$(curl -sf -u "${CLIENT_ID}:${CLIENT_SECRET}" \
  -X POST "https://${WORKSPACE_HOST}/oidc/v1/token" \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "scope=all-apis" \
  --data-urlencode "custom_claim=${CLAIM}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

if [ -z "$TOKEN" ]; then
  echo "Failed to mint token -- check CLIENT_ID/CLIENT_SECRET/WORKSPACE_HOST." >&2
  exit 1
fi
echo "Token minted." >&2

PAYLOAD=$(python3 -c "
import json, sys
print(json.dumps({'warehouse_id': sys.argv[1], 'statement': sys.argv[2], 'wait_timeout': '30s'}))
" "$WAREHOUSE_ID" "$STATEMENT")

echo "Submitting statement..." >&2
RESPONSE=$(curl -sf -X POST "https://${WORKSPACE_HOST}/api/2.0/sql/statements" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d "$PAYLOAD")

STATE=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',{}).get('state','UNKNOWN'))")
STMT_ID=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('statement_id',''))")

while [ "$STATE" = "PENDING" ] || [ "$STATE" = "RUNNING" ]; do
  sleep 2
  RESPONSE=$(curl -sf "https://${WORKSPACE_HOST}/api/2.0/sql/statements/${STMT_ID}" -H "Authorization: Bearer ${TOKEN}")
  STATE=$(echo "$RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('status',{}).get('state','UNKNOWN'))")
done

if [ "$STATE" != "SUCCEEDED" ]; then
  echo "FAILED (state=$STATE)" >&2
  echo "$RESPONSE" | python3 -m json.tool
  exit 1
fi

echo "$RESPONSE" | python3 -c "
import sys, json
r = json.load(sys.stdin)
cols = [c['name'] for c in r['manifest']['schema']['columns']]
rows = r.get('result', {}).get('data_array', [])
print('\t'.join(cols))
for row in rows:
    print('\t'.join(str(v) for v in row))
print(f'({len(rows)} row(s))', file=sys.stderr)
"
# Note: this script doesn't page through chunked results (manifest.total_chunk_count > 1) --
# fine for small test/count queries; for a query returning many rows, use the JDBC client
# (docs/deployment/oauth-jdbc-flow.md section 3) instead.
