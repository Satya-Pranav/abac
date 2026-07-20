# JDBC client — reproduces the customer's real auth mechanism

This is a standalone Java reproduction of `../abac_docs/Java/config/DatabricksConnectionProxy.java`'s
auth flow, using the real `com.databricks:databricks-jdbc` driver (verified: this compiles
and packages successfully against v3.4.1 — every class/method name here is real, not guessed).

It exists alongside the `curl` + SQL Statement Execution API commands in
[`../docs/deployment/runbook.md`](../docs/deployment/runbook.md), not instead of them (full write-up in
[`../docs/deployment/oauth-jdbc-flow.md`](../docs/deployment/oauth-jdbc-flow.md)). Use whichever fits what you're checking:
- the `curl` commands — fast, no JVM/Maven needed, good for iterating through test scenarios.
- This JDBC client — actually exercises the customer's real two-step auth mechanism
  (open a JDBC connection with standard OAuth M2M properties, then hot-swap the live
  access token to one carrying `custom_claim`) via the real driver, not a raw HTTP call.

## What it does, matching DatabricksConnectionProxy.java step for step

1. Opens a normal JDBC connection using the driver's documented OAuth M2M properties:
   `AuthMech=11`, `Auth_Flow=1`, `OAuth2ClientId`, `OAuth2Secret`. This mints an initial
   token with no `custom_claim`.
2. Unwraps the connection to the driver-internal `DatabricksConnection`, builds a new
   `OAuthM2MServicePrincipalCredentialsProvider` whose `configure()` is overridden to
   inject `custom_claim` into the OIDC token request (identical structure to the
   customer's anonymous subclass), and hot-swaps the connection's live access token via
   `client.resetAccessToken(...)`.
3. Runs whatever SQL statement you pass in and prints the result.

## Build

```bash
cd JDBC
mvn package
```

Produces `target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar`.

## Run

Same environment variables as the `curl` commands in `../docs/deployment/runbook.md` — reuse the same exports:

```bash
export CLIENT_ID="76d5804d-d302-4014-a1d3-d846f02c84ef"   # the SP application id
export CLIENT_SECRET="<your oauth secret>"
export WORKSPACE_HOST="adb-xxxx.azuredatabricks.net"       # no trailing slash
export WAREHOUSE_ID="<warehouse id>"

java -jar target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar \
  '{"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[]}' \
  "SELECT * FROM abac_tpcds.tpcds_1_delta.customer ORDER BY 1"
```

`claim.user` must equal a dummy `subjectID` seeded in `ABAC_EntitySubjectAssignment`
(`u.analyst1@example.com` / `u.vendor.mgr@example.com` / `u.developer@example.com`) or the row
filter denies everything — you impersonate a tester by choosing that string. Full case catalog:
[`../docs/testing/jdbc-cases.md`](../docs/testing/jdbc-cases.md). Expect the same result as the equivalent `curl` SQL
Statements API call — this just proves the real driver mechanism matches the raw REST approach.

## Run the full test suite (all cases at once) — the final program

`Runner` runs **every** case from [`../docs/testing/jdbc-cases.md`](../docs/testing/jdbc-cases.md) (ABAC, permissions,
RBAC_ABAC, and ctx-edge tinkering) through this same OAuth token hot-swap — re-minting a token with
a fresh `custom_claim` **per case**. It **auto-detects** whether the deployed `abac_row_filter` is
2-branch or 3-branch, checks each case against the matching expectation, and logs per case: the
purpose, the claim, the SQL, the expected result, the actual result, and PASS/FAIL.

**Self-seeding.** At start it inserts a **namespaced fixture** — real entity ids
(`2012`/`3006`/`118144`) + the dummy emails, but via `suite_a_*` assignment ids and a `SUITE_ORG`
org parent — runs the cases, then **drops exactly those rows** in a `finally` block. Teardown only
touches the `suite_*` / `SUITE_ORG` rows, so your real seed (`sql/03`) is never disturbed, and it's
idempotent (clears leftovers from an aborted run first). This needs the SP to have **`MODIFY`** on
`ABAC_Assignment` / `ABAC_EntitySubjectAssignment` / `orgHierarchy` (uncomment the grants in
`sql/09`). Without `MODIFY` the suite **skips seeding** and runs against whatever is already seeded —
the header line tells you which happened.

```bash
cd JDBC && mvn -q package        # same env vars as above
java -cp target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar com.abacpoc.Runner
```

Per-case output, then a summary:

```
[A2] (ABAC) Baseline: branch 3 EXISTS matches analyst -> Customer entity 2012.
   claim  : {"tenant":1,"user":"u.analyst1@example.com","org":"100","mode":"ABAC","root":"Customer","permissions":[]}
   sql    : SELECT count(*) FROM abac_tpcds.tpcds_1_delta.customer
   expect : 1 row
   actual : 1
   verdict: PASS

[A9] (ABAC) Non-root table: 2-branch has no permissions branch -> 0; 3-branch -> ALL.
   ...
   expect : 0 rows   (3-branch would be: ALL rows)
   actual : 0
   verdict: PASS
...
 SUMMARY (filter: 2-branch)  ->  PASS 23   FAIL 0   INFO 2   ERROR 0
```

(Exact counts depend on the live filter + your seed. `INFO` = observe-only, e.g. A3 lists the
visible ids; C6 prints how `from_json` treats a type-mismatched field.) The `AbacJdbcClient`
single-shot command above stays for ad-hoc one-off claims.

## If the driver version needs to change

This POC pins `com.databricks:databricks-jdbc:3.4.1` in `pom.xml`. If the customer's own
codebase pins a different version, matching that exactly (check their `pom.xml`/build
file if you get access to it) will maximize the chance that class/method signatures line
up — the driver's internal API surface (`com.databricks.jdbc.*`,
`com.databricks.internal.sdk.core.oauth.*`) isn't a stable public contract the way the
JDBC interface itself is, so it can change between versions.
