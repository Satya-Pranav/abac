# Refactor baseline

`databricks-baseline.txt` (git-ignored) is the reference output of the **pre-refactor** suite:
43 cases + 3 DR2 checks = **46**, all green. Phase 0 tasks (2–4) must reproduce it exactly.

Captured 2026-07-20 against `abac_tpcds.tpcds_1_delta`, after `sql/15_direct_rls.sql` was applied.
Summary line: `SUMMARY  ->  PASS 46   FAIL 0   INFO 0   ERROR 0`

## The normalizer

Two things in the output vary between identical runs, so a raw `diff` is useless:

1. **DR2b elapsed time** — `[swap->reflected in 12551 ms ...]` vs `12367 ms`.
2. **Databricks statement IDs** in error text — the four conflict cases (W/WP/WS, sqlState `42KDJ`)
   print `for statement [01f18428-d5c1-...]`, a fresh UUID per run. Found by the two-run check in
   Task 1; without this rule the Task 2 gate would show four false "regressions".

Use this definition **verbatim**, applied to BOTH sides:

```bash
norm() {
  sed -E -e 's/[0-9]+ ms/N ms/g' \
         -e 's/for statement \[[^]]*\]/for statement [ID]/g'
}
diff <(norm < baseline/databricks-baseline.txt) <(norm < /tmp/after.txt) && echo IDENTICAL
```

Verified: it rewrites exactly **5 lines** of a 46-check run, and leaves every row count, id list,
verdict, and the SUMMARY line untouched.

**Keep every rule narrow.** Never normalize bare numbers (`s/[0-9]+/N/g`) — that would erase the row
counts, which are precisely what the gate exists to protect. A normalizer that hides a real
regression is worse than no gate at all.

## Regenerating

Only when output changes intentionally. There is exactly one sanctioned regeneration in the plan:
**Task 5**, which adds `SKIP` to the summary line.

## Reproducing the baseline

```bash
java -cp JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar \
  com.abacpoc.AbacTestSuite > baseline/databricks-baseline.txt 2>&1
```
Requires `CLIENT_ID`, `CLIENT_SECRET`, `WORKSPACE_HOST`, `WAREHOUSE_ID`, and `sql/01`–`sql/15`
applied. Takes ~2 min (DR2 sleeps 10s deliberately).
