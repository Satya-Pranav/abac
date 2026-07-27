# OneTrust ⟷ TPC-DS JDBC Suite Replication — Design

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replicate the entire TPC-DS JDBC functional test suite (61 cases across 16 groups + 8
scenarios, `JDBC/src/main/java/com/abacpoc/`) onto the OneTrust deployment
(`abac_onetrust.onetrust_sim`), so OneTrust has full parity with what's already proven live
against TPC-DS.

**Architecture:** Split into two tiers by what each case actually depends on. Tier A (23 cases)
validates the real row-filter logic against OneTrust's actual seeded assignment data — needs
richer seed data, not new mechanism. Tier B (38 cases + 8 scenarios) validates Unity Catalog ABAC
*mechanism* itself (policy scope, tag binding, row-filter conflicts, UDF arity, malformed claim
shapes) via dedicated throwaway schemas that don't depend on which dataset they're attached to —
mostly a mechanical port of `sql/12-21` with the catalog prefix swapped.

**Tech Stack:** Same as the existing OneTrust JDBC work — Java 17, Maven, the `Engine`/`Case`/
`Scenario` framework already in `JDBC/`, `commons-csv` for the annotated-queries CSV, plain SQL
files under `sql_onetrust/`.

## Global Constraints

- **Isolated schemas for Tier B**, not OneTrust's real 11+5 tables — confirmed with the user.
  Mirrors TPC-DS's own pattern (`abac_tpcds.abac_scope`, `abac_tpcds.abac_tags`, etc.), just under
  the `abac_onetrust` catalog instead (`abac_onetrust.abac_scope`, `abac_onetrust.abac_tags`, ...).
- **Duplicate scenario classes for OneTrust**, don't generalize the existing TPC-DS ones —
  confirmed with the user. Zero risk to the already-"confirmed live" TPC-DS scenario code.
- **Never break what's already shipped:** the existing TPC-DS suite (60 cases + 8 scenarios,
  `INCLUDE_ONETRUST` defaults false), OneTrust's `OT-T1`..`OT-T8` + 50 real-query cases
  (`OnetrustCases.java`), and the `ONETRUST_CLIENT_ID`/`ONETRUST_CLIENT_SECRET` second-principal
  wiring in `Runner.java` must all keep working byte-for-byte unless a task explicitly changes them.
- **Never commit an SP secret.** Application ids only, same as everywhere else in this repo.
- **Reuse real harvested data wherever a case is data-dependent** (Tier A) — entity ids, org ids,
  and their relationships must trace back to actual generated main-table rows, the same bar
  established by the earlier data-integrity verification (`entityId`/`entityOrganizationId` in
  `ABAC_EntitySubjectAssignment` must exist in the real source tables/org hierarchy; `subjectId`
  is synthetic by design since no real OneTrust table contains subject/user records).
- **Never assert on elapsed time.** Same project-wide policy as the TPC-DS suite (`Dr2HotSwap`-
  equivalent): measure and print propagation latency, never PASS/FAIL on a threshold.

---

## Tier A — data-dependent cases (23)

These validate `abac_row_filter`'s actual branches against OneTrust's real seeded assignment
data — the same category `OT-T1`..`OT-T8` already cover (8 of the ~23), just narrower in scope.
Full replication means matching TPC-DS's breadth across 5 groups:

| TPC-DS group | Count | What it proves | OneTrust equivalent needs |
|---|---|---|---|
| **ABAC** (A1-A9) | 9 | Root-type explicit assignment: allow/deny by user, empty user, wrong root, non-root-via-permissions (branch 2) | Already have A2/A6/A7/A8-equivalents in `OT-T1`/`OT-T2`; need A9's branch-2 "all rows of a related type" case for a 2nd table, and the remaining deny/edge variants |
| **PERM** (B1-B4) | 4 | The `permissions` (branch 2, middle) path in isolation: grants table-wide read of a related type, denies when the type is omitted, denies on wrong format | `OT-T3`/`OT-T4` cover 2 of these; need the "wrong format" (dot-notation vs object-type string) case |
| **RBAC** (R1-R4 + ODEL + OLIVE) | 6 | RBAC_ABAC org-subtree is ADDITIVE with per-row assignment (not a replacement); org-driven-only case; soft-deleted org-hierarchy edges are excluded | `OT-T8` covers one RBAC_ABAC case; need the additive-with-assignment proof and the soft-deleted-edge pair (needs 2 real org-hierarchy rows differing only by `isDeleted`) |
| **TENANT** (T1-T2) | 2 | `ctx.tenant` is never read by the filter — same claim, different tenant, identical result | Pure re-assertion of filter logic already proven generic (same filter template) — reuses `OT-T1`'s claim/result with `tenant` changed |
| **ORG** (O1-O2) | 2 | `ctx.org` is inert in plain ABAC mode (only read inside the RBAC_ABAC branch) | Reuses `OT-T1`'s claim with `org` changed + a mirror of the RBAC additive case with an empty org |

**New seed data needed:** a self-seeding fixture mirroring `Runner.java`'s TPC-DS
`fixtureInserts`/`fixtureDeletes` (`SUITE_ORG`/`SUITE_EMPTY`-equivalent), inserted into
`abac_onetrust.onetrust_sim.OrgHierarchyBase` with real child org rows harvested from actual
seeded entities, namespaced so teardown never touches the real seed. Also needs the ODEL/OLIVE
soft-deleted-edge pair: the same real org id as both a live and a soft-deleted child of two
throwaway parent orgs.

## Tier B — mechanism-only cases (38) + 8 scenarios

These test Unity Catalog ABAC behavior itself, independent of which dataset is attached, via
dedicated throwaway schemas/tables under `abac_onetrust` (mirroring `abac_tpcds.abac_scope` etc.):

| TPC-DS group | Count | What it proves | New isolated objects (OneTrust side) |
|---|---|---|---|
| **EDGE** (C1-C8) | 8 | Claim parsing/case-sensitivity: lowercase mode/root, missing/extra/malformed claim fields, empty claim fails closed, case-sensitive user match | None — reuses `abac_onetrust.onetrust_sim`'s real seeded assignment (`OT-T1`'s entity), just varies the claim |
| **CONFLICT** (W1, WP1, WP2, WS1) | 4 | `UC_ABAC_MULTIPLE_ROW_FILTERS`: two row filters on one table (different or same bindings) always errors, table-wide, regardless of query columns | 2 new throwaway tables (mirrors `warehouse`, `web_page`/`web_site`) with deliberately conflicting policies |
| **META** (N1-N4) | 4 | Onboarding a brand-new governed table: soft-deleted assignment, group-membership grant, inactive assignment, soft-deleted assignment record | 4 new throwaway tables (one assignment condition each, mirroring `promotion`/`store`/`call_center`/`ship_mode`) |
| **THRESH** (TH1-TH3) | 3 | A *separate* range row filter (`>=` not `=`) — the deployed exact-match filter is unaffected | 1 new throwaway table + a dedicated threshold row-filter function |
| **RLS** (DR1) | 1 | Classic `ALTER TABLE ... SET ROW FILTER` (no tags, no policy) still filters | 1 new throwaway table |
| **V** (V1-V3) | 3 | Row filters (classic and ABAC) propagate through views, including aggregates | 2 new views over the DR1/DR2-equivalent throwaway tables |
| **SC** (SC1-SC4) | 4 | `ON SCHEMA` policy scope: governs every matching member, not just the first; untagged tables are ungoverned; schema+table-level filters conflict (no precedence) | `abac_onetrust.abac_scope` schema, 4 throwaway tables |
| **TG** (TG1-TG3) | 3 | Tag-binding edge cases: `has_tag_value()` value discrimination; ambiguous same-tag-two-columns refuses to bind; a matching-nothing `MATCH COLUMNS` silently fails open | `abac_onetrust.abac_tags` schema, throwaway tables |
| **UC** (UC2) | 1 | A declared-type UDF param bound to a differently-typed column is coerced, not rejected | `abac_onetrust.abac_udf` schema |
| **XT** (XT1) | 1 | Classic RLS + ABAC policy on the same table hits the same one-row-filter-per-table conflict | `abac_onetrust.abac_xmech` schema |
| **EX** (EX1-EX2) | 2 | `TO ... EXCEPT` principal exemption | `abac_onetrust.abac_gaps` schema |
| **CL** (CL1-CL4) | 4 | Malformed/null-shaped claim JSON: missing `mode` key entirely, explicit `null` user, `permissions` as the wrong JSON type, a null element inside `permissions` — distinct from EDGE's case-sensitivity/extra-field tests | None — pure claim-shape variation against the real seeded assignment, same as EDGE |

**Scenarios (8, all duplicated as `Onetrust*` classes):**

| Scenario | What it proves |
|---|---|
| `OnetrustDr2HotSwap` | Measures real policy-change propagation latency (poll-until-reflected, then revert) — needs its own small hot-swappable throwaway table+UDF, same shape as TPC-DS's `income_band` |
| `OnetrustViewPolicySwap` | A view over a policy-governed base table reflects a live UDF swap |
| `OnetrustSecretInvariance` | The same SP with a different secret sees identical results — SKIPs cleanly without the env var, same as TPC-DS's |
| `OnetrustSecondPrincipal` | A principal NOT in a policy's `TO` set sees the table unfiltered (governance completeness) |
| `OnetrustTokenExpiry` | An expired token fails closed at authentication, before any query |
| `OnetrustE6Scenarios` (×7 placeholders) | Mechanical port — all `SKIP` pending the e6data ABAC identity flow, same as TPC-DS's originals |

---

## File structure

- `sql_onetrust/`'s own sequence currently runs `01`-`07`; Tier B's mechanism setup continues it
  rather than reusing TPC-DS's `12`-`21` numbers: `08_row_filter_conflict.sql`,
  `09_onboard_new_tables.sql`, `10_threshold_filter.sql`, `11_direct_rls_and_dr2.sql`,
  `12_views.sql`, `13_policy_scope.sql`, `14_tag_binding.sql`, `15_udf_contract.sql`,
  `16_cross_mechanism.sql`, `17_except_and_defaults.sql` — ported from `sql/12-21` 1:1 by theme,
  catalog prefix swapped, teardown blocks included (matches `sql/`'s pattern). CL needs no new SQL
  file (no DDL — pure claim-shape variation, same as TPC-DS's original).
- Tier A's expanded seed (additional named identities, group memberships, the ODEL/OLIVE
  soft-deleted-edge pair) extends `sql_onetrust/05_seed_test_principals.sql` directly — same
  concern as what's already there, not a new theme, so no new file number.
- `JDBC/.../cases/OnetrustCases.java` — grows to include Tier A's ~23 cases (or splits into
  `OnetrustCasesTierA.java`/`OnetrustCasesTierB.java` if the single file gets unwieldy — a call for
  whoever implements it, guided by file-size, not decided here).
- `JDBC/.../scenario/OnetrustDr2HotSwap.java`, `OnetrustViewPolicySwap.java`,
  `OnetrustSecretInvariance.java`, `OnetrustSecondPrincipal.java`, `OnetrustTokenExpiry.java`,
  `OnetrustE6Scenarios.java` — new, duplicated from their TPC-DS counterparts.
- `Runner.java` — `runOnetrustCases()` extended to also run scenarios (mirroring `runAll()`'s
  scenario loop) and to seed/drop the Tier A fixture, analogous to `setUpFixture`/`dropFixture`.

## Testing / validation approach

Same bar as everything else built this session: every new case and scenario gets run live against
a real Databricks workspace (the operator, since this needs real credentials) before being
considered done — no case ships on "should work" reasoning alone. Given the scale, this plan
should execute via `subagent-driven-development`, one task per case group (roughly matching the
table rows above), each with its own task-scoped review before moving to the next.

## Out of scope

- Masking (`abac_should_mask_column`) — already out of scope for both suites; the real customer
  masks at the app layer, not via policy.
- Any new e6data-specific work — `OnetrustE6Scenarios` stays `SKIP`-by-default placeholders, same
  status as TPC-DS's, pending the e6data ABAC identity flow (tracked separately, see the paused
  Phase 2 load-testing notes).
