# Refactor baseline

`databricks-baseline.txt` (git-ignored) is the reference output of the **pre-refactor** suite:
43 cases + 3 DR2 checks = **46**, all green. Phase 0 tasks (2–4) must reproduce it exactly.

Captured 2026-07-20 against `abac_tpcds.tpcds_1_delta`, after `sql/15_direct_rls.sql` was applied.
Summary line (post-Task 5): `SUMMARY  ->  PASS 46   FAIL 0   SKIP 0   INFO 0   ERROR 0`

Task 5 added the `SKIP` counter (capability gating: a case whose `requires()` includes a
capability the engine doesn't `supports()` reports SKIP instead of running). Against Databricks,
which supports every `Capability`, this is always `SKIP 0` — the counter only moves for an engine
with narrower support (e.g. e6data).

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

> **The original capture can no longer be regenerated.** It was produced by
> `com.abacpoc.AbacTestSuite`, which Task 4 deleted once its contents had moved into
> `Runner`/`Cases`/`Dr2HotSwap`. The pre-refactor output is preserved verbatim as
> `pre-refactor-baseline.txt` — that file is the only remaining record of it.

To run the suite as it exists now (a superset: 60 cases + 8 scenarios):

```bash
java -cp JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar \
  com.abacpoc.Runner > /tmp/run.txt 2>&1
```
Requires `CLIENT_ID`, `CLIENT_SECRET`, `WORKSPACE_HOST`, `WAREHOUSE_ID`, and `sql/01`–`sql/15`
applied (`sql/16`–`20` add the newer groups). Takes ~2 min (DR2 sleeps 10s deliberately).

`ENGINE=e6data` selects the other engine; see `docs/deployment/runbook.md`.
