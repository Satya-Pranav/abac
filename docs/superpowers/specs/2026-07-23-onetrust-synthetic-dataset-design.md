# OneTrust-shaped synthetic dataset + query extraction — design

**Date:** 2026-07-23
**Status:** approved by user, pending spec review

## 1. Goal

Build a synthetic dataset on Azure Databricks that is shaped like the real OneTrust tenant
(`auto_qa_e40yx52dkbjpcqazimno9yvh4k`), for **performance testing of the same ABAC row-filter
mechanism** already built for the TPC-DS POC (`sql/00`–`sql/20`), but at real-life-shaped
volume — most importantly the ~600M–1B row `ABAC_EntitySubjectAssignment` table the row filter's
`EXISTS` subquery runs against.

This phase's deliverable is **data generation + a curated set of validated queries** — not
policy/tag wiring, not a benchmark harness, not JDBC-harness integration. Those are explicitly
follow-on work (see §8).

## 2. Source data (what informed this design)

| File | What it is |
| --- | --- |
| `onetrust/onetrust_sample_data/onetrust_table_profile_results.csv` | Column-level profile (type, row_count, ndv, null_count, min/max) for 13 distinct tables across 2 schemas (`auto_qa_e40yx52dkbjpcqazimno9yvh4k`, `monitoring`) |
| `onetrust/onetrust_sample_data/sample_*.csv` | 500-ish-row real samples for 11 of those 13 tables |
| `onetrust/onetrust_sample_data/onetrust_abac_table_profile_results.csv` | Column-level profile for 7 `abac_`-prefixed tables, all **0 rows** in this environment (a newer "policy" model schema, not what this design targets — see §4) |
| `abac_docs/customer_data/*.rtf` | Real `CREATE TABLE` DDL for the 5 legacy ABAC metadata tables (authoritative schema per `abac_docs/customer_data/README.md`) |
| `abac_docs/customer_data/ABAC related tables sample data & estimates.xlsx` | One sample-data sheet per legacy ABAC table + per-tenant production row-count estimates |
| `onetrust/onetrust_sanity_run.csv` | 357 real QA queries (`query_alias`, `query`) spanning 11 different tenant schemas |

Key discrepancies resolved during brainstorming (recorded here so the "11" and "5" in this doc
are unambiguous — see conversation for full reasoning):

- The profile CSV has **13** tables; only **11** have a matching sample file. This design uses
  those 11 (see §3).
- "5 ABAC tables" refers to the **legacy assignment-model** tables documented in
  `abac_docs/customer_data/` (`ABAC_Assignment`, `ABAC_AssignmentPermission`,
  `ABAC_EntitySubjectAssignment`, `UserGroupMembers`, `OrgHierarchy`) — the same 5 the existing
  TPC-DS POC (`sql/02_metadata_tables.sql`) already models — **not** the 7-table "policy" schema
  in `onetrust_abac_table_profile_results.csv`, which is a different/newer model with zero
  sample data anywhere and is out of scope.

## 3. The 11 main tables

Schema: `abac_onetrust.onetrust_sim` (see §7 for catalog/schema naming), except
`entitygroupconfig` which stays in `abac_onetrust.monitoring` to preserve the real schema
boundary observed in profiling.

| Table | Real row_count (generation target) | Notes |
| --- | --- | --- |
| `cmb_assessment` | 4,984 | Has 4 struct/map/list columns with no flat stats — see §5.3 |
| `cmb_controlimplementation` | 2,573 | Mostly-null columns (many `null_count` ≈ `row_count`) — generate the real null-rate, don't force values |
| `cmb_inventory` | 8,750 | Has 2 struct/map/list columns (`attributes`, `personalDataObjects`) — see §5.3 |
| `cmb_riskrelatedobjects` | 1,100 | `riskId`/`valueId` feed `entity_registry` |
| `cmb_template` | 5,235 | `id` feeds `entity_registry` (type `Template`) |
| `cmb_v_assessment_v4` | 1,591,030 | Largest main table; despite the `_v` name this is NOT a simple view over `cmb_assessment` (2,666 distinct `id`s fan out to 1.59M rows — looks like a per-event/history table) — generate independently from its own profiled stats, do not derive via SQL join |
| `cmb_v_inventoryaggregatedrisksummary` | 14 | Tiny; use real sample rows near-verbatim, only 15 sample rows exist |
| `entitylink_v3` | 1,007,335 | Generic cross-entity link table; `entityid1typereference`/`entityid2typereference` values must come from the same 20-value pool as `reportingmoduletoentityreferencemapping_v.entityTypeReference` |
| `orghierarchy` | 183 | **Reused directly as `org_registry`** (see §6.1) — real profiled data, ancestor-closure shape (68 distinct orgs, several parent rows each), not a fabricated tree |
| `reportingmoduletoentityreferencemapping_v` | 21 | Small reference/lookup table — its `entityTypeReference` column is the authoritative list of valid entity types used to drive `entity_registry.objectType` and the ABAC tables' `objectType` values |
| `entitygroupconfig` (schema `monitoring`) | 0 | Real profiled count is 0 — generate as an empty, schema-only table. Do not invent rows for a table that is empirically empty in the source. |

Full column lists/types/null-rates for all 11 come from
`onetrust_table_profile_results.csv` directly — the implementation plan will read that CSV
programmatically to build per-table column specs rather than hand-transcribing every column
here.

Two profiled tables are explicitly **excluded**: `cmb_v_inventoryattributevalue_v3` (236,810
rows, EAV-style) and `cmb_v_riskrelatedobjects` (7 rows) — both have profiled stats but no
sample file, so there's no real-value calibration source for them.

## 4. The 5 ABAC tables

Schema: `abac_onetrust.onetrust_sim` (same schema as the main tables — they're profiled under
the same real schema hash, and `OrgHierarchy` is shared between the two groups).

Authoritative schema = the RTF DDL in `abac_docs/customer_data/`, cross-checked against
`onetrust_abac_table_profile_results.csv` where the two overlap (3 tables) since the CSV
reflects the currently-deployed columns, which have drifted slightly from the RTF template (e.g.
`ABAC_Assignment` in the CSV has `createdBy`/`updatedBy` audit columns the RTF DDL doesn't list).

| Table | Row target | Basis | Partitioning |
| --- | --- | --- | --- |
| `ABAC_Assignment` | ~500,000 | `abac_docs/customer_data/README.md` §2 per-tenant estimate | `PARTITIONED BY (objectType)` — matches real DDL |
| `ABAC_AssignmentPermission` | ~5,000,000 | same | none (real DDL has none) |
| `ABAC_EntitySubjectAssignment` | ~600,000,000–1,000,000,000 | same — this is the table the row filter's `EXISTS` runs against per governed row | `PARTITIONED BY (objectType)` — matches real DDL, and per `abac_docs/customer_data/README.md` §2 this is "the single most important optimization" for the row filter's query pattern. **Load-bearing for the performance-testing goal** — an unpartitioned billion-row table would make the resulting perf numbers meaningless. |
| `UserGroupMembers` | ~100,000 | same | none |
| `OrgHierarchy` | 183 (= `orghierarchy`, reused) | real profiled data, not an estimate | n/a — it's a view (`SELECT * FROM OrgHierarchyBase WHERE isDeleted IS NOT TRUE`) per the real DDL; generate `OrgHierarchyBase` as the physical table and `OrgHierarchy` as the view |

`OrgHierarchy`'s row target intentionally does **not** follow the README's ~100K estimate:
the profiled 183-row/68-org table is real observed data, and real data is preferred over an
estimate even though it's smaller. This is a conscious tradeoff — flag if it turns out
insufficient once policy-wiring (a later phase) needs a deeper subtree for RBAC_ABAC testing.

## 5. Generation architecture

### 5.1 Layer 1 — Registries (generated first)

Small reference tables everything else joins against, so IDs are consistent across tables
instead of independently random:

- **`entity_registry`** — harvested `id`-like columns from the 11 main tables (`cmb_assessment.id`
  → `Assessment`, `cmb_inventory.id` → `Asset`/`Vendor` per `inventoryType`,
  `cmb_controlimplementation.id` → `Control`, `cmb_riskrelatedobjects.riskId` → `Risk`,
  `cmb_template.id` → `Template`, etc.), tagged with an `objectType` drawn from
  `reportingmoduletoentityreferencemapping_v`'s 20 real entity-type values. Entity types with no
  corresponding table in our 11 (e.g. `AIAGENTS`, `WORKPAPER`) get standalone synthetic entity
  IDs not tied to a governed row — expected, since not every OneTrust entity type has a table in
  this slice.
- **`org_registry`** = `orghierarchy` reused as-is (§3, §4).
- **`subject_registry`** — synthetic user/group UUID pools sized to back `UserGroupMembers`
  (~100K membership rows): a pool of individual user UUIDs plus group UUIDs.
- **`assignment_registry`** = the generated `ABAC_Assignment` table itself (its `id` is the FK
  target for `ABAC_AssignmentPermission.assignmentId` and `ABAC_EntitySubjectAssignment.assignmentId`).

Dependency order: `entity_registry` requires the 11 main tables' `id` columns to exist first, so
main-table generation happens before `entity_registry` is finalized, even though `org_registry`
and `subject_registry` have no such dependency and can be built immediately.

### 5.2 Generation engine

**`dbldatagen`** (Databricks Labs' synthetic data library), used uniformly for every table —
main and ABAC — instead of splitting tools by table size. It's purpose-built for fact-tables-
referencing-dimension-tables generation at this exact scale (hundreds of millions to billions of
rows), supports weighted categorical distributions and template-based string/UUID generation,
and gives one consistent config language instead of two separate code paths that each have to
get FK-consistency right independently.

Column specs per table are derived programmatically from the profile CSVs (type, ndv → cardinality,
null_count/row_count → null rate) plus the 500-row samples (categorical value pools, realistic
string/date patterns) where a sample file exists. If `dbldatagen` turns out not to cleanly
support broadcast-join-based FK generation at billion-row scale, the fallback is hand-rolled
PySpark using explicit broadcast joins against the (small) registry DataFrames — noted here as
an implementation-time risk, not resolved by this design.

### 5.3 Struct/map/list columns

6 columns across 2 tables have no flat profiled stats (`assessmentSectionReportInformations`,
`questionMap`, `questionRootMap`, `userIdsAssociatedWithAssessment` in `cmb_assessment`;
`attributes`, `personalDataObjects` in `cmb_inventory`) — the profiling job itself failed to
compute min/max for these (nested types aren't supported by the query engine that ran the
profiling). No sample-based ground truth exists for their internal shape.

These get **minimal, mostly-null placeholders** rather than faithful reconstruction — low
engineering investment is a deliberate choice, not a shortcut: there's no stats to calibrate
against, and it keeps the generator's complexity budget focused on the parts that matter for the
actual goal (row-filter performance at scale).

### 5.4 Validation gate

After generation, before calling a run complete:

- Row counts match target (main tables: profiled count; ABAC tables: the estimate/registry size)
- Column-level ndv and null-rate roughly track the profiled CSV values for the 11 main tables
- Referential integrity: the fraction of `ABAC_EntitySubjectAssignment` rows whose `entityId` /
  `subjectId` / `assignmentId` resolve against the registries, and whose `objectType` is
  consistent between the assignment and the entity it points at

This is a required pipeline stage, not a manual follow-up step.

### 5.5 Orchestration

A Databricks Jobs multi-task DAG: registries → main tables (parallel per table) → ABAC tables
(respecting FK order: `ABAC_Assignment` before `ABAC_AssignmentPermission`/`ABAC_EntitySubjectAssignment`)
→ validation. Not a set of notebooks run manually in sequence — the DAG gives retries,
parallelism, and observability, and removes the risk of running stages out of order.

## 6. Query extraction

Standalone Python script (does not need Databricks compute), using `sqlglot` for real SQL
parsing rather than substring/regex matching (which both under- and over-counts — it would miss
the `$Table`-wrapped subquery pattern present in 57 of the 357 queries, and false-positive on
comments/string literals).

Pipeline:

1. Parse all 357 rows of `onetrust_sanity_run.csv` (handle embedded NUL bytes and multi-line
   quoted query text).
2. Filter to the 76 queries scoped to the target tenant schema
   (`auto_qa_e40yx52dkbjpcqazimno9yvh4k`) — the other 281 belong to different tenants/schemas
   entirely and are out of scope.
3. Extract each query's actual table references via `sqlglot` (not substring match), and keep
   only queries where **every** referenced table is one of the 11 in scope.
4. Rewrite schema-qualified references from the real hashed schema name to
   `abac_onetrust.onetrust_sim` (and `abac_onetrust.monitoring` for `entitygroupconfig`).
5. Output a curated, standalone `.sql` file of compatible, catalog-rewritten queries — this
   phase's deliverable. **Not** wired into the existing dual-engine JDBC harness
   (`JDBC/src/main/java/com/abacpoc/`) — that integration is explicitly deferred (§8).

Target engine for this phase is **Databricks only**. (The profiling job's own error traces show
this environment also runs `e6data`, which has documented gaps with array/map/struct types and
no subquery row-filter support — relevant context for later phases, out of scope now.)

## 7. Catalog/schema naming

- Catalog: **`abac_onetrust`** (new, dedicated — does not reuse `abac_tpcds`)
- Schema `abac_onetrust.onetrust_sim`: the 11 main tables (minus `entitygroupconfig`) + the 5
  ABAC tables
- Schema `abac_onetrust.monitoring`: `entitygroupconfig` only, preserving the real schema
  boundary observed in profiling

## 8. Explicitly out of scope (this phase)

- Tagging the new tables / `CREATE POLICY` / grants (the sql/04-09-style wiring) — a follow-on
  phase, reusing the existing TPC-DS POC's templates pointed at `abac_onetrust`
- Running an actual performance benchmark (filtered vs. unfiltered query timing) — depends on
  the policy wiring above
- e6data compatibility / dual-engine validation
- Wiring the extracted queries into the existing JDBC test harness (`JDBC/`)
- The 4-table "policy" ABAC schema (`abac_policyentity`, `abac_policyentitysubject`,
  `abac_policyentitysubjectexclusion`, `abac_policysubject`) — no sample data exists for these
  anywhere; flagged as a possible future phase if OneTrust's newer policy model becomes relevant

## 9. Open risks

- **`dbldatagen` at billion-row scale with FK joins** is the single biggest technical unknown in
  this design — needs an early spike/proof-of-concept before committing the full implementation
  plan to it (see §5.2 fallback).
- **Cluster sizing / cost** for a ~600M–1B row generation + write job is unestimated — needs a
  concrete cluster spec once the generation approach is validated at small scale.
- **`orgHierarchy` at 68 orgs** may be too shallow once policy-wiring (a later phase) needs a
  deeper subtree for RBAC_ABAC testing — noted in §4, not resolved here.
