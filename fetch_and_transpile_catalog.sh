#!/bin/bash
#bash /Users/satyapranav/Desktop/PycharmProjects/abac/fetch_and_transpile_catalog.sh -u https://adb-536294184698298.18.azuredatabricks.net -t <DATABRICKS_PAT> -c abac_onetrust -p e6data-support-cluster-6-transpiler-green-746c8dc458-4gh49

usage() {
    echo "Usage: $0 -u <databricks_url> -t <token> -c <catalog_name> -p <pod> [-f <existing_file>]"
    echo "  -u  Databricks workspace URL (e.g. https://adb-xxx.azuredatabricks.net)"
    echo "  -t  Databricks PAT token"
    echo "  -c  Catalog name"
    echo "  -p  Transpiler pod name"
    echo "  -f  (optional) .txt file of already-processed functions (one full_name per line,"
    echo "      e.g. 'catalog.schema.func'); JSONL with a full_name field is also accepted."
    echo "      Any function present in this file will be skipped."
    exit 1
}

EXISTING_FILE=""

while getopts "u:t:c:p:f:" opt; do
    case $opt in
        u) DB_URL="$OPTARG" ;;
        t) TOKEN="$OPTARG" ;;
        c) CATALOG="$OPTARG" ;;
        p) POD="$OPTARG" ;;
        f) EXISTING_FILE="$OPTARG" ;;
        *) usage ;;
    esac
done

[ -z "$DB_URL" ] || [ -z "$TOKEN" ] || [ -z "$CATALOG" ] || [ -z "$POD" ] && usage

if [ -n "$EXISTING_FILE" ] && [ ! -f "$EXISTING_FILE" ]; then
    echo "Error: existing-functions file '$EXISTING_FILE' not found." >&2
    exit 1
fi

OUTPUT_JSONL="${CATALOG}-all-functions_28Jul.txt"
FAILED_LOG="${CATALOG}-failed-functions_28Jul.txt"
> "$FAILED_LOG"

# Do not reset output file if it already exists — allows resuming interrupted runs
if [ ! -f "$OUTPUT_JSONL" ]; then
    > "$OUTPUT_JSONL"
fi

# ── Port-forward management ────────────────────────────────────────────────────

PF_PID=""

start_port_forward() {
    kill "$PF_PID" 2>/dev/null
    pkill -f "port-forward.*8100" 2>/dev/null
    sleep 1
    kubectl port-forward -n e6data "$POD" 8100:8100 > /dev/null 2>&1 &
    PF_PID=$!
    # Wait up to 10s for port to become ready
    for i in $(seq 1 10); do
        if curl -s --max-time 2 http://localhost:8100/health > /dev/null 2>&1; then
            echo "  Port-forward ready (pid $PF_PID)"
            return 0
        fi
        sleep 1
    done
    echo "  Warning: Port-forward may not be ready yet, proceeding anyway." >&2
}

ensure_port_forward() {
    # Check process alive AND port actually responding
    if [ -n "$PF_PID" ] && kill -0 "$PF_PID" 2>/dev/null; then
        if curl -s --max-time 2 http://localhost:8100/health > /dev/null 2>&1; then
            return 0
        fi
    fi
    echo "  Port-forward down. Restarting..."
    start_port_forward
}

# ── Databricks API fetch with retry ───────────────────────────────────────────

db_get() {
    local url="$1"
    local out_file="$2"
    local max_retries=6
    local attempt

    for attempt in $(seq 1 $max_retries); do
        HTTP_CODE=$(curl -s --max-time 30 -o "$out_file" -w "%{http_code}" \
            -X GET "$url" -H "Authorization: Bearer ${TOKEN}")

        if [ "$HTTP_CODE" = "200" ]; then
            return 0
        elif [ "$HTTP_CODE" = "429" ]; then
            WAIT=$(( 2 ** attempt ))
            echo "    Rate limited. Retrying in ${WAIT}s (attempt $attempt/$max_retries)..." >&2
            sleep $WAIT
        elif [ "$HTTP_CODE" = "000" ]; then
            echo "    Curl timeout/no response. Retrying in 5s (attempt $attempt/$max_retries)..." >&2
            sleep 5
        else
            echo "    HTTP $HTTP_CODE from Databricks. Retrying in 5s (attempt $attempt/$max_retries)..." >&2
            sleep 5
        fi
    done

    echo "    ERROR: Gave up after $max_retries attempts for $url" >&2
    return 1
}

# ── Transpile with retry and port-forward recovery ────────────────────────────

transpile() {
    local routine_file="$1"
    local out_file="$2"
    local max_retries=5
    local attempt

    for attempt in $(seq 1 $max_retries); do
        ensure_port_forward

        HTTP_CODE=$(curl -s --max-time 60 -o "$out_file" -w "%{http_code}" \
            -X POST http://localhost:8100/convert-query \
            -F "query=<$routine_file" \
            -F "from_sql=databricks" \
            -F "to_sql=e6")

        if [ "$HTTP_CODE" = "200" ] && [ -s "$out_file" ]; then
            # Verify it's valid JSON
            python3 -c "import json; json.load(open('$out_file'))" 2>/dev/null && return 0
            echo "    Transpiler returned invalid JSON. Retrying (attempt $attempt/$max_retries)..." >&2
        elif [ "$HTTP_CODE" = "000" ]; then
            echo "    Transpiler timeout/no response. Restarting port-forward and retrying (attempt $attempt/$max_retries)..." >&2
            start_port_forward
        else
            echo "    Transpiler HTTP $HTTP_CODE. Retrying (attempt $attempt/$max_retries)..." >&2
            sleep 3
        fi
    done

    return 1
}

# ── Main ──────────────────────────────────────────────────────────────────────

echo "Starting port-forward..."
start_port_forward

# Fetch all schemas
echo "Fetching schemas for catalog '$CATALOG'..."
SCHEMAS_FILE=$(mktemp)
if ! db_get "${DB_URL}/api/2.1/unity-catalog/schemas?catalog_name=${CATALOG}" "$SCHEMAS_FILE"; then
    echo "Fatal: Could not fetch schemas." >&2
    kill "$PF_PID" 2>/dev/null
    exit 1
fi

SCHEMAS=$(python3 -c "
import json
with open('$SCHEMAS_FILE') as f:
    data = json.load(f)
for s in data.get('schemas', []):
    print(s['name'])
")
rm -f "$SCHEMAS_FILE"

if [ -z "$SCHEMAS" ]; then
    echo "Error: No schemas found for catalog '$CATALOG'." >&2
    kill "$PF_PID" 2>/dev/null
    exit 1
fi

TOTAL_SCHEMAS=$(echo "$SCHEMAS" | wc -l | tr -d ' ')
echo "Found $TOTAL_SCHEMAS schema(s)."

SCHEMA_NUM=0

while IFS= read -r SCHEMA; do
    SCHEMA_NUM=$(( SCHEMA_NUM + 1 ))
    echo ""
    echo "==> [$SCHEMA_NUM/$TOTAL_SCHEMAS] Schema: $SCHEMA"

    # Skip the entire schema if it is already represented in the existing-functions file.
    # A schema is "present" if any line either equals or contains "${CATALOG}.${SCHEMA}."
    # (covers both plain .txt lists of full_names and JSONL with a full_name field).
    SCHEMA_PREFIX="${CATALOG}.${SCHEMA}."
    if [ -n "$EXISTING_FILE" ] && { \
            grep -Fq "\"full_name\": \"${SCHEMA_PREFIX}" "$EXISTING_FILE" 2>/dev/null || \
            grep -Fq "${SCHEMA_PREFIX}" "$EXISTING_FILE" 2>/dev/null; }; then
        echo "  Schema already present in '$EXISTING_FILE'. Skipping entire schema."
        continue
    fi

    FUNCTIONS_FILE=$(mktemp)
    if ! db_get "${DB_URL}/api/2.1/unity-catalog/functions?catalog_name=${CATALOG}&schema_name=${SCHEMA}" "$FUNCTIONS_FILE"; then
        echo "  Skipping schema '$SCHEMA' after repeated failures." >&2
        rm -f "$FUNCTIONS_FILE"
        continue
    fi

    FUNC_NAMES=$(python3 -c "
import json
with open('$FUNCTIONS_FILE') as f:
    data = json.load(f)
for fn in data.get('functions', []):
    print(fn['name'])
" 2>/dev/null)
    rm -f "$FUNCTIONS_FILE"

    if [ -z "$FUNC_NAMES" ]; then
        echo "  No functions found."
        continue
    fi

    FUNC_COUNT=$(echo "$FUNC_NAMES" | wc -l | tr -d ' ')
    echo "  Found $FUNC_COUNT function(s)."
    FUNC_NUM=0

    while IFS= read -r FUNC; do
        FUNC_NUM=$(( FUNC_NUM + 1 ))
        FUNC_FULL="${CATALOG}.${SCHEMA}.${FUNC}"
        echo "  [$FUNC_NUM/$FUNC_COUNT] Processing: $FUNC_FULL"

        # Skip if already in output (resume support)
        if grep -q "\"full_name\": *\"${FUNC_FULL}\"" "$OUTPUT_JSONL" 2>/dev/null; then
            echo "    Already processed. Skipping."
            continue
        fi

        # Skip if already present in the externally-provided existing-functions file.
        # Accepts either a plain .txt file (one full_name per line) or JSONL with a full_name field.
        if [ -n "$EXISTING_FILE" ] && { \
                grep -Fxq "$FUNC_FULL" "$EXISTING_FILE" 2>/dev/null || \
                grep -q "\"full_name\": *\"${FUNC_FULL}\"" "$EXISTING_FILE" 2>/dev/null; }; then
            echo "    Already present in '$EXISTING_FILE'. Skipping."
            continue
        fi

        # Fetch function definition
        RESPONSE_FILE=$(mktemp)
        if ! db_get "${DB_URL}/api/2.1/unity-catalog/functions/${FUNC_FULL}" "$RESPONSE_FILE"; then
            echo "    Failed to fetch. Logging to failed list." >&2
            echo "$FUNC_FULL" >> "$FAILED_LOG"
            rm -f "$RESPONSE_FILE"
            continue
        fi

        # Extract routine_definition
        ROUTINE_FILE=$(mktemp)
        python3 -c "
import json
with open('$RESPONSE_FILE') as f:
    data = json.load(f, strict=False)
with open('$ROUTINE_FILE', 'w') as f:
    f.write(data.get('routine_definition', ''))
" 2>/dev/null

        if [ ! -s "$ROUTINE_FILE" ]; then
            echo "    No routine_definition. Logging to failed list."
            echo "$FUNC_FULL [no routine_definition]" >> "$FAILED_LOG"
            rm -f "$RESPONSE_FILE" "$ROUTINE_FILE"
            continue
        fi

        # Transpile
        TRANSPILED_FILE=$(mktemp)
        if transpile "$ROUTINE_FILE" "$TRANSPILED_FILE"; then
            python3 -c "
import json

with open('$RESPONSE_FILE') as f:
    data = json.load(f, strict=False)
with open('$TRANSPILED_FILE') as f:
    transpiled = json.load(f)

data['routine_definition'] = transpiled.get('converted_query', data.get('routine_definition', ''))

with open('$OUTPUT_JSONL', 'a') as out:
    out.write(json.dumps(data) + '\n')
"
            echo "    Done."
        else
            echo "    Transpiler failed after retries. Keeping original definition." >&2
            echo "$FUNC_FULL [transpile failed]" >> "$FAILED_LOG"
            python3 -c "
import json
with open('$RESPONSE_FILE') as f:
    data = json.load(f, strict=False)
with open('$OUTPUT_JSONL', 'a') as out:
    out.write(json.dumps(data) + '\n')
"
        fi

        rm -f "$RESPONSE_FILE" "$ROUTINE_FILE" "$TRANSPILED_FILE"
    done <<< "$FUNC_NAMES"

done <<< "$SCHEMAS"

kill "$PF_PID" 2>/dev/null
echo ""
echo "Done. Results written to '$OUTPUT_JSONL'."
echo "Failed functions (if any) logged to '$FAILED_LOG'."
