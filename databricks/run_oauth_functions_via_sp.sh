#!/usr/bin/env bash
# databricks/run_oauth_functions_via_sp.sh
#
# Creates get_user_context()/abac_row_filter_wrapper_oauth() -- the 2 UDFs whose bodies
# reference current_oauth_custom_identity_claim() -- via the Databricks SQL Statement
# Execution API, authenticated as a service principal's OAuth M2M token.
#
# Why this exists: CREATE FUNCTION bodies referencing current_oauth_custom_identity_claim()
# are eagerly analyzed at creation time (Databricks' InlineUserInfoExpressions Catalyst
# rule), which throws OAUTH_CUSTOM_IDENTITY_CLAIM_NOT_PROVIDED when run from a notebook
# cluster session (no per-connection OAuth-claim-bearing token) -- confirmed live 2026-07-31
# against abac_onetrust_scale.
#
# Merely switching to an M2M service-principal session is NOT enough on its own -- confirmed
# live 2026-07-31: a plain client_credentials token (no custom_claim) throws the exact same
# OAUTH_CUSTOM_IDENTITY_CLAIM_NOT_PROVIDED. Per docs/deployment/oauth-jdbc-flow.md section 2,
# current_oauth_custom_identity_claim() only resolves when the token was minted with a
# `custom_claim` request parameter on /oidc/v1/token -- that's the actual mechanism, not the
# identity type. CREATE FUNCTION never executes the body, so the eager check only needs the
# claim to be PRESENT (any struct-shaped JSON), not meaningful -- BOOTSTRAP_CLAIM below is a
# throwaway value for that sole purpose; it has no bearing on the function's real runtime
# behavior for later per-user callers.
#
# The other 23 build_oauth_wiring_sql statements (8 policy re-points + 15 grants) do NOT
# define new function bodies, so they're expected to run fine from the notebook via
# spark.sql() as normal -- only these 2 need this path. If that assumption turns out wrong,
# extend STMT_INDICES below rather than routing everything through curl.
#
# Usage (run from the repo root, either your own terminal with this repo checked out, or a
# Databricks %sh cell -- see the two "How to run" blocks at the bottom of this file):
#   export CLIENT_ID=<privileged SP's application id>       # needs CREATE FUNCTION rights
#   export CLIENT_SECRET=<its secret>                        # never hard-code -- from a secret store
#   export WORKSPACE_HOST=<workspace>.azuredatabricks.net
#   export WAREHOUSE_ID=<a running SQL warehouse id>
#   ./databricks/run_oauth_functions_via_sp.sh abac_onetrust_scale onetrust_sim <service_principal_bound_in_policies>

set -euo pipefail

CATALOG="${1:?Usage: $0 <catalog> <schema> <service_principal>}"
SCHEMA="${2:?Usage: $0 <catalog> <schema> <service_principal>}"
SERVICE_PRINCIPAL="${3:?Usage: $0 <catalog> <schema> <service_principal>}"

: "${CLIENT_ID:?CLIENT_ID env var required}"
: "${CLIENT_SECRET:?CLIENT_SECRET env var required}"
: "${WORKSPACE_HOST:?WORKSPACE_HOST env var required}"
: "${WAREHOUSE_ID:?WAREHOUSE_ID env var required}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "Minting OAuth token for ${CLIENT_ID}..."
BOOTSTRAP_CLAIM='{"tenant":0,"user":"schema-bootstrap","org":"0","mode":"DISABLE","root":"","permissions":[]}'
TOKEN=$(curl -sf -u "${CLIENT_ID}:${CLIENT_SECRET}" \
  -X POST "https://${WORKSPACE_HOST}/oidc/v1/token" \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "scope=all-apis" \
  --data-urlencode "custom_claim=${BOOTSTRAP_CLAIM}" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

if [ -z "$TOKEN" ]; then
  echo "Failed to mint token -- check CLIENT_ID/CLIENT_SECRET/WORKSPACE_HOST." >&2
  exit 1
fi
echo "Token minted."

echo "Generating the 2 OAuth-dependent function statements from governance_sql.py..."
STATEMENTS_FILE=$(mktemp)
python3 - "$CATALOG" "$SCHEMA" "$SERVICE_PRINCIPAL" > "$STATEMENTS_FILE" <<'PYEOF'
import sys, json
from onetrust_synth.governance_sql import build_oauth_wiring_sql

catalog, schema, sp = sys.argv[1:4]
stmts = build_oauth_wiring_sql(catalog, schema, sp)
# statements 0-1 (get_user_context, abac_row_filter_wrapper_oauth) are the only ones whose
# CREATE FUNCTION body references current_oauth_custom_identity_claim() -- see the header
# comment above for why only these two need this path.
for s in stmts[:2]:
    print(json.dumps(s))
PYEOF

i=0
while IFS= read -r line; do
  i=$((i+1))
  STMT=$(python3 -c "import json,sys; print(json.loads(sys.argv[1]))" "$line")
  echo "[$i/2] Submitting..."

  PAYLOAD=$(python3 -c "
import json, sys
print(json.dumps({'warehouse_id': sys.argv[1], 'statement': sys.argv[2], 'wait_timeout': '30s'}))
" "$WAREHOUSE_ID" "$STMT")

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
    echo "  FAILED (state=$STATE)"
    echo "$RESPONSE" | python3 -m json.tool
    rm -f "$STATEMENTS_FILE"
    exit 1
  fi
  echo "  OK"
done < "$STATEMENTS_FILE"

rm -f "$STATEMENTS_FILE"
echo "Both functions created successfully."
echo "Now go back to the notebook's Step 7 loop and skip statements 1-2 (i <= 2), let the rest run via spark.sql() as normal."

# ── How to run ─────────────────────────────────────────────────────────────────────────
#
# Option 1 -- your own local terminal (this repo checked out, same commit as the Databricks
# Repo, network access to the workspace):
#   cd /path/to/this/repo
#   export CLIENT_ID=... CLIENT_SECRET=... WORKSPACE_HOST=... WAREHOUSE_ID=...
#   ./databricks/run_oauth_functions_via_sp.sh abac_onetrust_scale onetrust_sim <sp>
#
# Option 2 -- a %sh cell in the SAME Databricks notebook (no separate checkout needed,
# since the Repo is already on the driver's filesystem):
#   %sh
#   export CLIENT_ID=... CLIENT_SECRET=... WORKSPACE_HOST=... WAREHOUSE_ID=...
#   bash databricks/run_oauth_functions_via_sp.sh abac_onetrust_scale onetrust_sim <sp>
#   # if "no such file" -- run `%sh pwd` first to confirm you're at the repo root; Databricks
#   # Repos usually land %sh cells there already, but cd into it explicitly if not.
