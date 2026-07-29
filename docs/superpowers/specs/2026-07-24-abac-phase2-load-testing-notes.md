# ABAC Phase 2 — Load Testing (paused brainstorming notes)

**Status:** PAUSED mid-brainstorm (superpowers:brainstorming skill), before design doc write-up
or approval. Not a finished spec — resume from here, do not treat as approved.

**Why paused:** user wants to focus on getting Phase 1 actually running in the Azure Databricks
environment first. This file exists purely so the Phase 2 discussion isn't lost.

---

## Goal (as stated by user)

1. Load testing to define limits on number of concurrent users and number of policies e6data
   (and, per the decision below, Databricks first) can support for the ABAC feature.
2. Find how low the policy/user-info cache refresh interval can go under full/medium/low load,
   to give as guidance to clients (not just OneTrust — any future client).
3. Concurrency target: 300 (low) / 500 (medium) / 800 (high) concurrent simulated users.
4. Policy count: 11 (one per table, on the real OneTrust 11-table schema — not synthetic extra
   tables/policies).
5. A benchmark query (or query set) that covers multiple/all 11 tables with active claims/policies
   on each.

## Decisions made so far

- **e6data blocker (critical):** `JDBC/src/main/java/com/abacpoc/engine/E6DataEngine.java`
  `applyIdentity()` unconditionally throws — no working ABAC identity flow on e6data yet. All 7
  `E6-*` scenarios in the multi-engine suite are `SKIP` placeholders. `docs/e6data-defect-report.md`
  (2026-07-20 source read) also found governance is silently bypassed over the JDBC path (empty
  session id) and the OPA authorizer is disabled. **Decision: build the full load-test framework on
  Databricks first** (working ABAC path today), engine-pluggable via the existing `Engine` SPI so it
  points at e6data later with no redesign.
- **Refresh-interval split into 2a/2b:** e6data has a real tunable cache (policies + user/claims
  info) via cluster env var(s), currently defaulting to **2 minutes**. Databricks has no equivalent
  config knob (Unity Catalog policy/tag propagation is observable via `DR2`/`VP` scenario polling,
  but not tunable). Decision: **Phase 2a (Databricks, now)** = concurrency + policy-count scaling
  limits only, no refresh-interval work. **Phase 2b (later, e6data-gated)** = repeat the load test on
  e6data once its ABAC identity flow + cache land, AND sweep the cache-refresh-interval env var
  across load tiers. 2b is not designed yet — just reserved as the next spec once e6data is ready.
- **Data scale-up required first:** Phase 1's dataset is intentionally small (e.g.
  `ABAC_EntitySubjectAssignment` = 100K rows). Decision: **scale up to production-representative row
  counts** (target counts to be pulled from `abac_docs/customer_data/`, e.g. the ~600M–1B ESA
  rows/tenant figure already documented in `README.md`) before running the load test — otherwise
  concurrency/policy numbers are meaningless for a client evaluating real workloads. Reuse
  `onetrust_synth`'s existing `scale_factor` mechanism (`config.py`), no new generation code needed.
- **Compute target:** fixed-size Databricks SQL Warehouse, **no autoscaling** — isolates ABAC policy
  overhead from Databricks' own autoscaling behavior.
- **Load-generation mechanism:** extend the existing `JDBC/` Java suite (`DatabricksEngine` already
  does real OAuth custom-claim swapping per connection) with a concurrency layer — a thread pool of N
  virtual users per tier, each with a **distinct** synthetic claim (different `user`/`org`/
  `permissions`, not 800 real Databricks accounts needed), running the benchmark query set for a
  fixed duration against the fixed-size warehouse. Preferred over an external tool (Gatling/k6/JMeter
  don't natively support Databricks OAuth claim injection) or a fresh Python harness (would duplicate
  working Java auth logic). Also sets up Phase 2b's e6data run for free via the same `Engine` SPI.
- **Metrics & "limit" definition:** follow this repo's own established convention (`JDBC/`'s plan
  Global Constraints: *"Never assert on elapsed time. Measure it and print it."*) — report p50/p95/
  p99 latency, throughput (queries/sec), error rate per tier; "the limit" is a narrative call-out
  (where error rate turns non-zero / latency breaks from linear scaling), not a hardcoded threshold.

## Benchmark query set (proposed, not yet confirmed)

Five queries of different shapes instead of one `UNION ALL`, so the report can break latency down
per query shape, not just per tier. Each virtual user gets randomly assigned one per iteration.

| # | Shape | Tables touched | What it stresses |
|---|---|---|---|
| Q1 | `UNION ALL` across all governed entity tables | cmb_assessment, cmb_controlimplementation, cmb_template, cmb_v_inventoryaggregatedrisksummary, cmb_inventory, cmb_riskrelatedobjects, cmb_v_assessment_v4, entitylink_v3 | Broadest coverage — "everything I can see" cross-entity view |
| Q2 | Multi-JOIN drill-down | cmb_assessment → cmb_v_assessment_v4 → entitylink_v3 → cmb_controlimplementation | Row-filter evaluation combined with JOIN planning |
| Q3 | Aggregation | cmb_riskrelatedobjects JOIN cmb_v_inventoryaggregatedrisksummary, GROUP BY type | GROUP BY interacting with 2 independently-filtered tables |
| Q4 | JOIN | cmb_template ↔ cmb_controlimplementation (via entitylink_v3) | "which controls does this template require" |
| Q5 | Large-table stress | cmb_v_assessment_v4 (1.59M rows) + entitylink_v3 (1M rows) directly, filtered, no joins | Isolates raw row-filter subquery cost at real scale — likely where degradation first shows up, since filter cost scales with `ABAC_EntitySubjectAssignment` size |

## Real column findings (from `onetrust_table_profile_results.csv`, schema `auto_qa_e40yx52dkbjpcqazimno9yvh4k`)

Of the 7 currently-unpolicied tables (today only `cmb_assessment`, `cmb_controlimplementation`,
`cmb_template`, `cmb_v_inventoryaggregatedrisksummary` have policies):

- **Feasible, real columns confirmed:**
  - `cmb_riskrelatedobjects` — has REAL per-row `entityType` (ndv=9) and `organizationID` (ndv=6)
    columns. Full 3-tag pattern (id+type+org), like the one table that already has it.
  - `cmb_inventory` — has real `inventoryType` (ndv=3), already mapped via
    `config.py`'s `INVENTORY_TYPE_TO_OBJECT_TYPE`. `orgGroupID` is 100% null in the sample — use
    literal org like 3 of the 4 existing policies do.
  - `cmb_v_assessment_v4` — has real `orgID`/`parentOrgID` columns (ndv=3/4). `id` is NOT unique
    (ndv=2666 across 1.59M rows — same fan-out issue Task 11 already fixed with `dropDuplicates`).
    Literal type `'ASSESSMENT'` + real orgID column.
  - `entitylink_v3` — links two entities per row (`entityid1`/`entityid1typereference`,
    `entityid2`/`entityid2typereference`), no org column (use literal, like most existing policies).
    **Open call:** which side (entityid1 or entityid2) drives the filter — not yet decided.
- **Don't fit the entity-ABAC model — recommend excluding from row-level policies:**
  - `orghierarchy` — this IS the table the row filter's own RBAC_ABAC branch reads from
    (`OrgHierarchy.parentOrgId = ctx.org`) to resolve org subtrees. Policying it would filter the
    filter's own lookup table.
  - `reportingmoduletoentityreferencemapping_v` — only 2 columns (`reportingModule`,
    `entityTypeReference`), 21 rows, no `id` column. A static module→type lookup table, not an
    entity with per-row grants.
  - `entitygroupconfig` — 0 real rows, lives in the separate `monitoring` schema (already a special
    case per Phase 1's `MONITORING_TABLES`). Out of scope for the benchmark entirely.

## OPEN QUESTION (unanswered when paused)

Do we exclude `orghierarchy` and `reportingmoduletoentityreferencemapping_v` from row-level ABAC
(treat "11 tables" as 9 genuinely policy'd + 2 ungoverned reference tables, still joinable), or
force a policy onto them anyway (even if semantically hollow / always-true) so the count is
literally 11/11? User had not answered this when the session moved to Phase 1 execution.

## Resume point

Next step per the brainstorming skill flow: get the open question answered, confirm/adjust the
benchmark query set, then move to "Present design sections → write full spec doc → self-review →
user review → superpowers:writing-plans" for the Phase 2a implementation plan.
