# OneTrust-shaped synthetic dataset + query extraction — design

**Date:** 2026-07-23 (revised after a query-extraction spike)
**Status:** approved by user, revised per feedback, pending re-review

## 1. Goal

Build a synthetic dataset on Azure Databricks that is shaped like the real OneTrust tenant
(`auto_qa_e40yx52dkbjpcqazimno9yvh4k`), for **performance testing of the same ABAC row-filter
mechanism** already built for the TPC-DS POC (`sql/00`–`sql/20`), but at real-life-shaped
volume — most importantly the ~600M–1B row `ABAC_EntitySubjectAssignment` table the row filter's
`EXISTS` subquery runs against.

Revised sequencing (per feedback on the first draft): validate correctness cheaply at small
scale — including the row-filter policies actually working against this data shape — **before**
committing to a billion-row generation run. See §8 for the phase split.

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
boundary observed in profiling (and confirmed live in query traffic — see §6).

**Scale factor:** every main-table row target is `target_rows = real_profiled_count ×
scale_factor`, default `scale_factor = 1` (i.e. the values below, unchanged from the original
profiled counts). This is a formal generator parameter, not a hardcoded constant — it lets the
same pipeline produce a small dataset for iteration or the full profiled volume without code
changes. Even at `scale_factor = 1` the main tables top out at 1.59M rows, cheap enough to
regenerate often; the expensive scale decision lives entirely on the ABAC side (§4).

| Table | Real row_count (scale_factor=1 target) | Notes |
| --- | --- | --- |
| `cmb_assessment` | 4,984 | Has 4 struct/map/list columns with no flat stats; 2 of them are genuinely queried — see §5.3 |
| `cmb_controlimplementation` | 2,573 | Mostly-null columns (many `null_count` ≈ `row_count`) — generate the real null-rate, don't force values |
| `cmb_inventory` | 8,750 | Has 2 struct/map/list columns (`attributes`, `personalDataObjects`) — neither referenced by any compatible query, see §5.3 |
| `cmb_riskrelatedobjects` | 1,100 | `riskId`/`valueId` feed `entity_registry` |
| `cmb_template` | 5,235 | `id` feeds `entity_registry` (type `Template`) |
| `cmb_v_assessment_v4` | 1,591,030 | Largest main table; despite the `_v` name this is NOT a simple view over `cmb_assessment` (2,666 distinct `id`s fan out to 1.59M rows — looks like a per-event/history table) — generate independently from its own profiled stats, do not derive via SQL join |
| `cmb_v_inventoryaggregatedrisksummary` | 14 | Tiny; use real sample rows near-verbatim (15 sample rows exist). **The single most-queried table in the compatible-query set (39 of 50) — prioritize this table's realism.** |
| `entitylink_v3` | 1,007,335 | Generic cross-entity link table; `entityid1typereference`/`entityid2typereference` values must come from the same 20-value pool as `reportingmoduletoentityreferencemapping_v.entityTypeReference` |
| `orghierarchy` | 183 | **Reused directly as `org_registry`** (see §6.1 of the original design / §4 below) — real profiled data, ancestor-closure shape (68 distinct orgs, several parent rows each), not a fabricated tree |
| `reportingmoduletoentityreferencemapping_v` | 21 | Small reference/lookup table — its `entityTypeReference` column is the authoritative list of valid entity types used to drive `entity_registry.objectType` and the ABAC tables' `objectType` values |
| `entitygroupconfig` (schema `monitoring`) | 0 | Real profiled count is 0 — generate as an empty, schema-only table. Do not invent rows for a table that is empirically empty in the source. **Despite 0 rows, this table is real, live query traffic** — 9 of the 50 compatible queries reference it, so the schema must exist and be queryable even though it's empty. |

Full column lists/types/null-rates for all 11 come from
`onetrust_table_profile_results.csv` directly — the implementation plan will read that CSV
programmatically to build per-table column specs rather than hand-transcribing every column
here.

Two profiled tables are explicitly **excluded**: `cmb_v_inventoryattributevalue_v3` (236,810
rows, EAV-style) and `cmb_v_riskrelatedobjects` (7 rows) — both have profiled stats but no
sample file, so there's no real-value calibration source for them. (Neither is referenced by any
compatible query either — see §6.)

## 4. The 5 ABAC tables — small first, then scale up

Schema: `abac_onetrust.onetrust_sim` (same schema as the main tables — they're profiled under
the same real schema hash, and `OrgHierarchy` is shared between the two groups).

Authoritative schema = the RTF DDL in `abac_docs/customer_data/`, cross-checked against
`onetrust_abac_table_profile_results.csv` where the two overlap (3 tables) since the CSV
reflects the currently-deployed columns, which have drifted slightly from the RTF template (e.g.
`ABAC_Assignment` in the CSV has `createdBy`/`updatedBy` audit columns the RTF DDL doesn't list).

### Phase 1 (this phase) — small, for correctness validation

Row targets are deliberately small — big enough to exercise real multi-subject/multi-group
fan-out per entity and the `EXISTS`-join pattern the row filter depends on, small enough to
generate, query, and iterate on in seconds rather than hours:

| Table | Phase-1 row target | Partitioning |
| --- | --- | --- |
| `ABAC_Assignment` | ~1,000 | `PARTITIONED BY (objectType)` — matches real DDL, exercised at small scale now so the partition-pruning behavior is already correct before Phase 2 scales it up |
| `ABAC_AssignmentPermission` | ~10,000 | none (real DDL has none) |
| `ABAC_EntitySubjectAssignment` | ~100,000 | `PARTITIONED BY (objectType)` — same rationale |
| `UserGroupMembers` | ~5,000 | none |
| `OrgHierarchy` | 183 (= `orghierarchy`, reused) | n/a — it's a view, see below |

Phase 1 deliverable also includes: tagging these + the main tables, creating the row-filter
policies/UDFs (adapted from `sql/04`–`sql/09`, pointed at `abac_onetrust`), running the 50
compatible queries (§6) against the policy-active dataset, and executing 5–10 basic ABAC
functional test cases in the style of `docs/testing/jdbc-cases.md` (e.g. explicit-assignment
visibility, RBAC_ABAC org-subtree access via the reused real `orghierarchy`, group-membership
grant via `UserGroupMembers`, `DISABLE` mode showing everything, `isDeleted`/`isActive`
exclusion). This is the gate before Phase 2.

### Phase 2 (deferred, separate follow-on) — full documented production scale

| Table | Row target | Basis |
| --- | --- | --- |
| `ABAC_Assignment` | ~500,000 | `abac_docs/customer_data/README.md` §2 per-tenant estimate |
| `ABAC_AssignmentPermission` | ~5,000,000 | same |
| `ABAC_EntitySubjectAssignment` | ~600,000,000–1,000,000,000 | same — the table the row filter's `EXISTS` runs against per governed row |
| `UserGroupMembers` | ~100,000 | same |
| `OrgHierarchy` | 183 (unchanged) | real profiled data, not an estimate — see note below |

Both phases use the **same generation code**, just a different scale target — Phase 1 is not a
throwaway prototype, it's the same pipeline run small first. The actual performance benchmark
(filtered vs. unfiltered query timing at full scale) only happens after Phase 2's data exists,
and is itself deferred beyond this design (§8).

`OrgHierarchy`'s row target intentionally does **not** grow between phases and does **not**
follow the README's ~100K estimate: the profiled 183-row/68-org table is real observed data, and
real data is preferred over an estimate even though it's smaller. Flag if 68 orgs turns out too
shallow once Phase 1's RBAC_ABAC test cases run.

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
  (Phase-1: ~5K rows; Phase-2: ~100K): a pool of individual user UUIDs plus group UUIDs.
- **`assignment_registry`** = the generated `ABAC_Assignment` table itself (its `id` is the FK
  target for `ABAC_AssignmentPermission.assignmentId` and `ABAC_EntitySubjectAssignment.assignmentId`).

Dependency order: `entity_registry` requires the 11 main tables' `id` columns to exist first, so
main-table generation happens before `entity_registry` is finalized, even though `org_registry`
and `subject_registry` have no such dependency and can be built immediately.

### 5.2 Generation engine

**`dbldatagen`** (Databricks Labs' synthetic data library), used uniformly for every table —
main and ABAC — instead of splitting tools by table size. It's purpose-built for fact-tables-
referencing-dimension-tables generation at scale, supports weighted categorical distributions
and template-based string/UUID generation, and gives one consistent config language instead of
two separate code paths that each have to get FK-consistency right independently.

Column specs per table are derived programmatically from the profile CSVs (type, ndv → cardinality,
null_count/row_count → null rate) plus the 500-row samples (categorical value pools, realistic
string/date patterns) where a sample file exists. Since Phase 1 only needs ~100K rows for the
largest ABAC table (not 600M–1B), the "does dbldatagen scale to a billion rows with FK joins"
question is now a **Phase 2** risk, not a Phase 1 blocker — Phase 1 is comfortably within range
of straightforward broadcast-join-based PySpark generation even without `dbldatagen`, so the
tooling choice can be validated cheaply before it matters at scale.

### 5.3 Struct/map/list columns — corrected after checking real query usage

Original plan (first draft of this doc) was to treat all 6 struct/map/list columns as
low-effort, mostly-null placeholders, since none have flat profiled stats. **Checking this
against the actual query-extraction results (§6) overturned half of that plan:**

| Column | Table | Referenced by a compatible query? | Treatment |
| --- | --- | --- | --- |
| `questionRootMap` | `cmb_assessment` | **Yes** — `element_at(a1_0.questionRootMap, '<uuid>')` in a `SELECT` list | Needs real, well-typed `MAP<STRING, STRUCT<questionType, dataType, state, maturityScaleAllowed, questionDetailedInfo, responses:LIST<...>, responseType>>` data — see note below |
| `userIdsAssociatedWithAssessment` | `cmb_assessment` | **Yes** — `array_contains(a1_0.userIdsAssociatedWithAssessment, '<uuid>')` in a `WHERE` clause | Needs a real `LIST<STRING>` of UUID-shaped values per row |
| `assessmentSectionReportInformations` | `cmb_assessment` | No | Low-effort/mostly-null placeholder, as originally planned |
| `questionMap` | `cmb_assessment` | No | Low-effort/mostly-null placeholder |
| `attributes` | `cmb_inventory` | No | Low-effort/mostly-null placeholder |
| `personalDataObjects` | `cmb_inventory` | No | Low-effort/mostly-null placeholder |

Note on `questionRootMap`: the compatible query that uses it looks up one **specific, literal**
key (a UUID that was a real question ID in the source tenant's actual assessment template
configuration). Synthetic data has no way to predict or reproduce that literal key — the
realistic, correct outcome is that this lookup returns `NULL` against synthetic data (a valid
empty result, not an error), same as it would for any assessment in real data that doesn't have
that specific question answered. What generation must guarantee is that the column is
**well-typed and queryable** (a valid map with some representative keys/values), not that this
exact literal lookup returns non-null. This is a materially bigger engineering lift than the
other struct columns — it's a map of structs containing a list of structs — and should be
budgeted as such in the implementation plan.

### 5.4 Validation gate

After generation, before calling a run complete:

- Row counts match target (main tables: `real_profiled_count × scale_factor`; ABAC tables: the
  phase's target)
- Column-level ndv and null-rate roughly track the profiled CSV values for the 11 main tables
- Referential integrity: the fraction of `ABAC_EntitySubjectAssignment` rows whose `entityId` /
  `subjectId` / `assignmentId` resolve against the registries, and whose `objectType` is
  consistent between the assignment and the entity it points at
- **Phase 1 only:** the 50 compatible queries (§6) run without error against the policy-active
  dataset, and the 5–10 basic ABAC test cases pass

This is a required pipeline stage, not a manual follow-up step.

### 5.5 Orchestration

A Databricks Jobs multi-task DAG: registries → main tables (parallel per table) → ABAC tables
(respecting FK order: `ABAC_Assignment` before `ABAC_AssignmentPermission`/`ABAC_EntitySubjectAssignment`)
→ (Phase 1 only) policy/tag wiring → validation. Not a set of notebooks run manually in sequence
— the DAG gives retries, parallelism, and observability, and removes the risk of running stages
out of order. Phase 2 reruns the same DAG with `scale_factor`/row-target parameters changed.

## 6. Query extraction — validated with a working spike, not just designed

Unlike the rest of this document, this section is no longer speculative — it was actually run
against `onetrust_sanity_run.csv` using `sqlglot` (dialect: `databricks`) as a spike, and the
numbers below are real output, not estimates. The spike script went through 3 real bugs before
these numbers were trustworthy (recorded here so the implementation plan doesn't repeat them):

1. **A query with zero real table references trivially passed the "no bad tables found" filter.**
   `onetrust_sanity_run.csv` turns out to be a **Power BI / Azure Analysis Services query log**
   (confirmed by the `PremiumCapacityName`/`WorkspaceDisplayName`/`PBIESubscriptionId` fields in
   every query's header comment), not a raw OneTrust application query log. Many "candidates"
   are AAS plumbing calls like `SELECT ... FROM (SELECT monitoring.get_default_catalog('auto_qa_e40y...') AS catalog) AS _`
   where the tenant hash appears only as a **string literal argument**, not a table qualifier.
   Fix: a query must have at least one real table reference to count as in-scope.
2. **CTE aliases were misclassified as unknown external tables.** A query like
   `WITH cte0 (...) AS (SELECT ... FROM CMB_Assessment ...) SELECT ... FROM cte0 ...` was being
   flagged as referencing an unknown table `cte0`, wrongly excluding an otherwise fully
   in-scope query. Fix: collect `WITH`-clause CTE aliases and exclude self-references to them
   before checking table names against the in-scope list. This fix is what surfaced the
   `questionRootMap`/`userIdsAssociatedWithAssessment` usage in §5.3 — that query had been
   wrongly excluded before the fix, so its column references were never even checked.
3. **`monitoring.EntityGroupConfig` was classified as an out-of-scope other-schema reference.**
   Correct per §3 — `entitygroupconfig` genuinely lives in a different schema than the other 10
   main tables. Fix: special-case `monitoring.entitygroupconfig` as in-scope; every other
   `monitoring.*` reference stays out-of-scope (one query legitimately references
   `monitoring.dbxtenantschemaversion`, which is correctly excluded).

### Results (357 total queries)

- **76** mention the target tenant schema (`auto_qa_e40yx52dkbjpcqazimno9yvh4k`) anywhere in the
  query text.
- Of those, **50 are in-scope**: every real table reference resolves to one of the 11 tables (or
  the `monitoring.entitygroupconfig` exception). 0 `sqlglot` parse failures across all 76.
- The other **26** break down as:
  - **22** — zero real table references (AAS/Power BI plumbing: `get_default_catalog(...)`,
    `get_decryptedId(...)`, etc.)
  - **3** — reference real OneTrust tables outside our 11:
    `CMB_InventoryPersonalDataAssociation`, `CMB_InventoryRelatedAttributeMap`,
    `DSAR_ColumnMetadataDSAR`
  - **1** — references `monitoring.dbxtenantschemaversion`, a different `monitoring`-schema
    table
- **Table coverage among the 50 compatible queries is narrow**: `cmb_v_inventoryaggregatedrisksummary`
  (39), `entitygroupconfig` (9), `cmb_assessment` (2, joined with `orghierarchy`), `orghierarchy`
  (2). The other 7 of the 11 tables aren't touched by any compatible query in this dataset. This
  is a property of `onetrust_sanity_run.csv` being a QA sanity log rather than a coverage suite —
  worth stating plainly so the "run the queries" validation gate in Phase 1 isn't mistaken for
  full 11-table coverage. It validates 4 tables meaningfully; the other 7 are validated only by
  the stats/referential-integrity checks in §5.4, not by real query execution.
- **2 queries reference nested struct/map/list columns** — see §5.3.

### Pipeline (confirmed working, ready to formalize as the implementation)

1. Parse all 357 rows of `onetrust_sanity_run.csv` (handle embedded NUL bytes and multi-line
   quoted query text).
2. Pre-filter to the 76 queries whose text mentions the target tenant schema.
3. Parse each with `sqlglot` (`read="databricks"`); collect `WITH`-clause CTE aliases and exclude
   self-references to them; exclude the `$Table` report-template placeholder alias.
4. A query is in-scope only if it has ≥1 real table reference and every real table reference is
   either in the target schema and one of the 11 known tables, or is the
   `monitoring.entitygroupconfig` exception.
5. Track column references per in-scope query to flag the 6 nested-type columns.
6. Rewrite schema-qualified references from the real hashed schema name to
   `abac_onetrust.onetrust_sim` (and `abac_onetrust.monitoring` for `entitygroupconfig`), via
   `sqlglot` AST mutation (not string replace) — this also normalizes non-standard constructs
   like ODBC scalar-function escapes (`{ fn locate(...) }` → `LOCATE(...)`) for free.
7. Output: **implemented and committed** — `onetrust/extract_compatible_queries.py` produces
   `onetrust/onetrust_sanity_run_annotated.csv` (all 357 source rows, plus `in_scope`, `reason`,
   `tables_used`, `references_nested_columns`, `modified_query` columns). This supersedes the
   originally-planned standalone `.sql` file — a CSV with an added column was more useful for
   auditing *why* each query was included/excluded, and integrates the same information in one
   place. Verified: all 50 `modified_query` values re-parse cleanly, no leftover ODBC syntax, no
   leftover schema-hash references outside harmless provenance comments.

Target engine for this phase is **Databricks only**. (The profiling job's own error traces show
this environment also runs `e6data`, which has documented gaps with array/map/struct types and
no subquery row-filter support — relevant context for later phases, out of scope now.) Not wired
into the existing dual-engine JDBC harness (`JDBC/src/main/java/com/abacpoc/`) for this phase —
that integration is explicitly deferred (§8).

## 7. Catalog/schema naming

- Catalog: **`abac_onetrust`** (new, dedicated — does not reuse `abac_tpcds`)
- Schema `abac_onetrust.onetrust_sim`: the 10 main tables (all but `entitygroupconfig`) + the 5
  ABAC tables
- Schema `abac_onetrust.monitoring`: `entitygroupconfig` only, preserving the real schema
  boundary observed in both profiling and live query traffic

## 8. Phases and explicit scope

### Phase 1 (this design's implementation target)

- Registries (§5.1)
- 11 main tables at `scale_factor = 1` (parameterized, can be run smaller)
- 5 ABAC tables at Phase-1 small scale (§4)
- Tagging + row-filter policy/UDF wiring on the Phase-1 dataset (adapted from `sql/04`–`sql/09`)
- Running the 50 compatible queries (§6) against the policy-active Phase-1 dataset
- 5–10 basic ABAC functional test cases
- The validation gate (§5.4)

### Phase 2 (deferred, separate follow-on — gated on Phase 1 passing)

- Re-run the same pipeline with ABAC row targets scaled to full documented production volume
  (§4), main tables optionally scaled via `scale_factor`
- The actual performance benchmark (filtered vs. unfiltered query timing at full scale)

### Out of scope regardless of phase

- e6data compatibility / dual-engine validation
- Wiring the extracted queries into the existing JDBC test harness (`JDBC/`)
- The 4-table "policy" ABAC schema (`abac_policyentity`, `abac_policyentitysubject`,
  `abac_policyentitysubjectexclusion`, `abac_policysubject`) — no sample data exists for these
  anywhere; flagged as a possible future phase if OneTrust's newer policy model becomes relevant

## 9. Open risks

- **`questionRootMap` generation** (§5.3) is a map-of-struct-containing-a-list-of-structs with no
  sample data to calibrate against — the biggest single data-shape unknown in Phase 1, now that
  it's confirmed load-bearing rather than skippable.
- **`dbldatagen` at billion-row scale with FK joins** is Phase 2's biggest technical unknown, no
  longer a Phase 1 blocker (§5.2) — needs an early spike before committing to Phase 2's
  implementation plan.
- **Cluster sizing / cost** for the Phase 2 ~600M–1B row generation + write job is unestimated —
  needs a concrete cluster spec once Phase 1 validates the approach.
- **`orgHierarchy` at 68 orgs** may be too shallow for Phase 1's RBAC_ABAC test cases — will be
  known once those test cases actually run, not before.
- **Query coverage is narrow** (§6): the Phase 1 "run the compatible queries" gate meaningfully
  exercises only 4 of the 11 main tables. The other 7 rely entirely on the stats/referential-
  integrity checks, not on real query execution, for this phase.
