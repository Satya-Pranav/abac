# OneTrust scale-testing catalog: 34-table dataset, 8 governed tables, query shortlist — design

**Date:** 2026-07-30
**Status:** approved by user section-by-section during brainstorming, self-reviewed, pending user sign-off on this written doc

## 1. Goal

Build a second, larger OneTrust-shaped catalog (`abac_onetrust_scale`) alongside the existing
`abac_onetrust`, at production-representative scale — most importantly a
`ABAC_EntitySubjectAssignment` table reaching the ~1B-row figure already documented in
`README.md` — covering more of the real OneTrust schema (34 non-ABAC tables instead of today's
11) and more governed tables (8 instead of today's 4), then shortlist which of the 357 real
OneTrust QA queries execute cleanly against it. Deliverable: the new catalog itself, plus a CSV
of every query (real + functional-test) paired with the claim it should be tested under.

This is explicitly a **functional-correctness-at-scale + broader table coverage** exercise, not
the load/concurrency testing scoped (and paused) in
`docs/superpowers/specs/2026-07-24-abac-phase2-load-testing-notes.md` — related motivation, same
underlying `scale_factor` mechanism, different goal. That paused doc's own open question (whether
`orghierarchy`/`reportingmoduletoentityreferencemapping_v` get hollow policies to round out "11")
is **not** revisited here; this design governs the 4 new tables the paused doc separately
identified as good real candidates instead.

## 2. Source data

| File | What it is |
| --- | --- |
| `onetrust/onetrust_remaining_table_profile_results2.csv` | Column-level profile for 40 distinct tables not in today's 11-table fixture |
| `onetrust/onetrust_table_samples_remaining/` | 37 sample CSVs, same corrupted-header convention as the original sample data (`sample_csv.py`'s existing header-reconstruction-by-profile-column-order logic applies here too — never trust these files' own header row) |
| `onetrust/onetrust_sanity_run_annotated.csv` | 357 real QA queries; 50 marked `in_scope=yes` with a working `modified_query`, 307 marked `in_scope=no` (empty `modified_query`), many specifically because they reference tables outside today's 11 |
| `docs/superpowers/specs/2026-07-24-abac-phase2-load-testing-notes.md` | Prior-art brainstorm: identified 4 real candidate tables for new row-filter policies, with the specific real columns each has |
| `onetrust_synth/` | The existing, tested Python/PySpark generator for the current `abac_onetrust` fixture — this design extends it rather than replacing it |

## 3. Table scope: 40 profiled → 23 registered

The 40 tables in the new profile CSV split as:

- **14 are empty in the real source** (`row_count=0`) — excluded entirely, nothing to model.
- **26 have real data.** Of those, **3 have no matching sample CSV** — `entityattributevalue_v3`
  (24.4M rows, the single largest table in the set), `cmb_v_riskattributevalue_v3` (1.35M rows),
  `cmb_v_inventorylinkattributemap` (1,344 rows). Dropped for this pass rather than generated from
  profile-stats-only (no realistic categorical sampling available) — can be revisited if sample
  data is provided later.
- **23 remain**, all registered. Every one of the 23 was checked against the profile CSV's
  `data_type` column: **none have struct/map/array/list columns**, so all 23 go through
  `main_tables.build_generic_table()`'s existing flat-dtype dispatch (id-like, boolean, numeric,
  temporal, categorical-from-samples) unmodified — no `nested_columns.py`-style bespoke code
  needed for any of them.

Combined with the existing 11, the new catalog has **34 non-ABAC tables** total.

## 4. Scale targets

**34 non-ABAC tables** — `scale_factor=5` via the existing deterministic-regeneration mechanism
(`config.scaled_row_count()`), not literal row duplication (duplicating real sample rows would
create exact-duplicate IDs, breaking uniqueness assumptions several UDFs/queries rely on). Every
table's target is `real_profiled_count × 5`, except the 2 verbatim tables (`orghierarchy`,
`cmb_v_inventoryaggregatedrisksummary`), which — same as today — stay at their real, unscaled
counts (real observed data, not synthesized).

**5 ABAC metadata tables** — `ABAC_EntitySubjectAssignment` hits the documented ~1B target
directly. The other 4 scale by **×100** (≈ √10,000, the ESA growth ratio) rather than the same
10,000× — they're closer to dimension tables (grant *definitions*) than fact tables (grant
*records*), and scaling them 1:1 with ESA would be semantically wrong, not just expensive.

| Table | Today | New target |
| --- | --- | --- |
| `ABAC_EntitySubjectAssignment` | 100,000 | **1,000,000,000** |
| `ABAC_Assignment` | 1,000 | 100,000 |
| `ABAC_AssignmentPermission` | 10,000 | 1,000,000 |
| `UserGroupMembers` | 5,000 | 500,000 |
| `ABAC_OrgHierarchy` (view) | 183 | unchanged (verbatim) |

**Registries** — also ×100, so per-user/per-group assignment counts land around ~5,000 on average
instead of exploding to ~500,000 if left fixed at Phase-1 sizes:

| Constant | Today | New target |
| --- | --- | --- |
| `SUBJECT_REGISTRY_USER_COUNT` | 2,000 | 200,000 |
| `SUBJECT_REGISTRY_GROUP_COUNT` | 300 | 30,000 |
| `STANDALONE_ENTITIES_PER_TYPE` | 100 | 10,000 |

**Operational flag, not a design change**: `abac_tables.build_abac_entity_subject_assignment()`
joins the full row-count DataFrame against `assignment_df` on the low-cardinality `objectType`
column before a per-row dedup pick (`abac_tables.py:110-118`) — at 1B rows this needs a properly
sized job cluster, not a small interactive one. No code change implied, just cluster sizing
awareness for whoever runs the notebook.

## 5. Governance: 8 tables

The original 4 policies replay unchanged at the new scale. 4 new tables get real row-filter
policies for the first time, using the real per-row columns each one has (verified against the
profile CSVs, not assumed):

| Table | id column | type source | org source | Pattern |
| --- | --- | --- | --- | --- |
| `cmb_assessment`, `cmb_controlimplementation`, `cmb_template`, `cmb_v_inventoryaggregatedrisksummary` | *(existing)* | *(existing)* | *(existing)* | unchanged |
| `cmb_riskrelatedobjects` | `riskId` | real `entityType` (ndv=9) | real `organizationID` (ndv=6) | full 3-tag |
| `cmb_inventory` | `id` | real `inventoryType` (ndv=3, via existing `INVENTORY_TYPE_TO_OBJECT_TYPE`) | literal (`orgGroupID` 100% null) | id + type-column + literal-org |
| `cmb_v_assessment_v4` | `id` (fan-out; `id` ndv=2,666 across 1.59M rows) | literal `'ASSESSMENT'` | real `orgID`/`parentOrgID` (ndv=3/4) | id + literal-type + org-column |
| `entitylink_v3` | `entityid1` | real `entityid1typereference` (ndv=5) | literal (no org column) | id + type-column + literal-org |

`cmb_v_assessment_v4`'s non-unique `id` is **already handled**, not a new problem:
`registries.py:63`'s `dropDuplicates(["entityId","objectType"])` exists specifically for this case
(comment references it explicitly). One assignment naturally grants access to every physical row
sharing that id — the same fan-out semantics TPC-DS's `A5` case already tests deliberately.

`entitylink_v3` needs one new piece of code (unlike the other 3, which are policy/tag SQL only):
it isn't in `config.ENTITY_SOURCE_TABLES` today, so `registries.build_entity_registry()` needs one
new source-table entry harvesting `(entityid1, entityid1typereference)` pairs — same dynamic-type
pattern already used for `cmb_inventory`'s `inventoryType` column, not a new mechanism.

**Seeded test principals**: extend `sql_onetrust/05_seed_test_principals.sql`'s existing pattern
(new assignment IDs `900006`+) — one known positive-grant test user per new governed table, picked
deterministically (`ORDER BY id LIMIT 1`) against one real entity, mirroring the
positive/negative-case pairing the original 4 tables already have. This guarantees every governed
table has at least one reliable, known-outcome claim to test against, independent of the ~1B rows
of bulk/dummy data surrounding it — the bulk data is volume/realism only, never what a test
asserts against.

## 6. Architecture

**Approach chosen: parameterize `onetrust_synth`, add a second notebook** (over: mutating
`config.CATALOG` in place — too easy to accidentally point at the wrong catalog on a later run; or
a fully duplicated `onetrust_synth_scale/` package — avoids that risk but drifts out of sync with
~18 duplicated files over time).

- `config.py`'s `CATALOG` and the table-registry dicts become real parameters (with today's values
  as defaults) threaded through `write_delta_table()`, `build_org_hierarchy_view_sql()`, and the
  tag/policy SQL builders — a call with no override behaves identically to today's Phase 1 run.
- New `databricks/phase2_scale_run.py` notebook: catalog/schema creation for
  `abac_onetrust_scale`, `build_all_main_tables(spark, catalog=..., scale_factor=5)`,
  `build_all_abac_tables(...)` with the §4 targets, the 8-table tag/policy SQL (adapted from
  `sql_onetrust/02-04`/`07`), and the extended seed-principals SQL from §5.
- `phase1_run_all.py` and the `abac_onetrust` catalog are never touched by this work.
- **Catalog name**: `abac_onetrust_scale`.
- **Execution**: this notebook is run by the user in their Databricks workspace (owner-level
  rights required for `CREATE CATALOG`/`CREATE POLICY`/`ALTER TABLE...SET TAGS`) — the
  query-scoped SP already in this repo cannot run it, and there's no Databricks SQL/Spark
  execution path available from this coding session (no `databricks-connect`, no admin credential).

## 7. Query shortlist

1. **Re-scope the 307 `in_scope=no` rows**: filter to ones where `reason` was specifically "table
   outside our set" (or similar) AND every table in `tables_used` now exists among the 34 —
   others (`different tenant schema`, `no real table reference`, other-schema references) stay
   excluded regardless of table coverage; scale doesn't fix those.
2. **Generate `modified_query` for the newly-eligible subset**: the existing 50 rows' `query` →
   `modified_query` transformation is mechanical (SQL reformatting + catalog-qualifying bare
   `schema.table` references, e.g. `monitoring.EntityGroupConfig` →
   `abac_onetrust_scale.monitoring.EntityGroupConfig`) — a small regex/parse-based script, not a
   hand-rewrite per query. Complex multi-schema queries may still fail; the execute-and-catch step
   below surfaces those for manual review rather than silently mis-including them.
3. **Execute**: extend `run_compatible_queries.py`'s existing execute-and-catch loop (already
   proven on the current 50) to run every candidate's `modified_query` against
   `abac_onetrust_scale`, recording pass/fail, row count, and error text per query.
4. **Claim pairing**: each query gets tested under **one claim** — the seeded owner's claim (§5)
   for the governed table in its `tables_used` (first/primary if it touches more than one); queries
   touching no governed table get the DISABLE/probe claim. This is a deliberate scope choice over
   testing every query against all 8 principals (bigger CSV, more thorough, not what was asked
   for here) or a placeholder claim with no real ABAC meaning.

## 8. Final CSV deliverable

| Column | Real queries | Functional tests |
| --- | --- | --- |
| `query_id` | `query_alias` | case ID (existing `OT-*`, or new IDs for the 4 new governed tables, e.g. `OT-RRO1`) |
| `source` | `real_query` | `functional_test` |
| `tables_used` | from source CSV | the governed table the case targets |
| `claim` | seeded owner's claim (§7.4) | the case's existing claim |
| `query` | catalog-qualified `modified_query` | the case's existing SQL |
| `expected_or_observed` | observed row count from the shortlist run (informational — no ground truth to assert against for arbitrary real queries) | the case's actual `Expect` (e.g. "1 row", "ALL rows") |
| `verified_status` | PASS/FAIL from the shortlist run | *(not re-verified here — already asserted correctness in the existing suite)* |

"Functional tests" = the existing `OnetrustCases.java` catalog (~119 cases, scale-agnostic — same
claims/expected outcomes regardless of data volume) plus the new seeded-principal cases for the 4
newly-governed tables from §5.

## 9. Validation / rollout order

1. Extend `validate.py`'s existing `validate_row_counts`/`validate_referential_integrity` to also
   cover the 4 new governed tables' entity/subject FK integrity, same join-ratio-check pattern
   already used for the original 4.
2. **Dry-run the entire new pipeline at `scale_factor=1` / `ESA=100,000` first** — new tables, new
   policies, `entitylink_v3` wiring, seeded principals, query shortlist, all the way through — to
   prove the code path end-to-end cheaply.
3. Only once the dry run passes clean, re-run at the real §4 targets (1B-row ESA write, properly
   sized job cluster per §4's operational note).

## 10. Explicitly out of scope for this pass

- The paused Phase-2 load/concurrency-testing work (`2026-07-24-abac-phase2-load-testing-notes.md`)
  — different goal, not resumed here.
- The 3 large tables lacking sample data (`entityattributevalue_v3`,
  `cmb_v_riskattributevalue_v3`, `cmb_v_inventorylinkattributemap`).
- The 14 genuinely-empty source tables.
- Testing every shortlisted query against all 8 governed-table principals (single-claim pairing
  only, per §7.4).
