# Task 5 Report: Tier A — RBAC group (OT-R1..OT-R4, OT-ODEL, OT-OLIVE) + 5th seed identity

## What I Did

1. **Seeded the 5th identity** in `sql_onetrust/05_seed_test_principals.sql`
   - Added after Task 2's `u.template.owner` block (verbatim from the brief):
     - `seed_assets_entity` temp view: picks one real `cmb_v_inventoryaggregatedrisksummary` row
       whose `inventoryType` is `'Assets'` (deterministic via `ORDER BY entityID LIMIT 1`, same
       idiom as `seed_assessment_entity`/`seed_control_entity`/`seed_template_entity`).
     - Assignment `900005` (`ABAC_Assignment`, `objectType='ASSETS'`) + its
       `ABAC_EntitySubjectAssignment` row granting `u.assets.owner@example.com` direct `USER_ID`
       access to that entity.
   - Updated the trailing expected-count comment from 4 to 5.

2. **Added `rbacGroupCases()`** to `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`
   - Inserted after `permGroupCases()` (previously ending at old line 235).
   - Contains 6 cases: `OT-R1`, `OT-R2`, `OT-R3`, `OT-R4`, `OT-ODEL`, `OT-OLIVE` — mirroring
     TPC-DS's R1-R4 + ODEL/OLIVE (`Cases.java` lines 121-242), consuming the `SUITE_ORG`/
     `SUITE_EMPTY`/`DEL_ORG`/`LIVE_ORG` org-hierarchy fixture from Task 1 (`Runner.java`
     `onetrustFixtureInserts()`).
   - `OT-R1` is the brief's documented honest adaptation (org-subtree count unaffected by an
     overlapping explicit grant, rather than TPC-DS's true-additivity demonstration, since all 14
     real `cmb_v_inventoryaggregatedrisksummary` rows share one real org).
   - All 6 cases transcribed verbatim from the brief; zero deviations.

3. **Updated `all()`** to fold in `rbacGroupCases()`
   - Added `cs.addAll(rbacGroupCases());` between `permGroupCases()` and `compatibleQueryCases()`,
     exactly as specified in Step 3 of the brief.

4. **Verified compilation** with `cd JDBC && mvn -q package` → clean, and `mvn package` (verbose)
   confirmed `BUILD SUCCESS`.

5. **Validated case counts** with a throwaway smoke test (compiled/run against
   `mvn dependency:build-classpath`, then deleted — nothing committed):
   - `rbacGroupCases().size() == 6` ✓
   - `all().size() == 77` (71 existing + 6 new) ✓
   - Also checked the 6 new ids appear in the expected order (`OT-R1, OT-R2, OT-R3, OT-R4,
     OT-ODEL, OT-OLIVE`).

## Build Output

```
$ cd JDBC && mvn -q package
(no output — success)

$ mvn package
...
[INFO] --- jar:3.4.1:jar (default-jar) @ jdbc-client ---
[INFO] --- assembly:3.7.1:single (default) @ jdbc-client ---
[INFO] Building jar: .../JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
```

## Smoke Test Output

Scratch file `Task5Smoke.java` (compiled with `mvn dependency:build-classpath` output +
`target/classes` on the classpath, run, then deleted along with the classpath file):

```
rbacGroupCases().size() = 6
all().size() = 77
SMOKE TEST PASSED
```

## Self-Review Notes

**RBAC_ORG_ID consistency check (explicitly requested by the task):**
The brief's new `rbacGroupCases()` claims do NOT reference the `RBAC_ORG_ID` Java constant
directly — they pass the symbolic org names `"SUITE_ORG"`, `"SUITE_EMPTY"`, `"DEL_ORG"`,
`"LIVE_ORG"` to `Cases.claim(...)`, matching how OT-T8's `rbacClaim` and the TPC-DS mirror
(`Cases.java` `SUITE_ORG`/`SUITE_EMPTY` constants) work: the *user's claimed org* is the symbolic
name, and the org-hierarchy fixture (Task 1, already implemented) is what links those symbolic
orgs to the one real org id as parent/child.

What actually ties this together is `Runner.java`'s `onetrustFixtureInserts()`
(`ONETRUST_REAL_ASSETS_ORG`, line 188) seeding `SUITE_ORG`/`DEL_ORG`/`LIVE_ORG` as
parent of that real org — so I diffed it against `OnetrustCases.java`'s existing `RBAC_ORG_ID`
constant (line 40, used by OT-T8 and `compatibleQueryCases()`):

- `Runner.java:188` — `ONETRUST_REAL_ASSETS_ORG = "b99df4a4-2bf5-4c08-9483-bd636470bc11"`
- `OnetrustCases.java:40` — `RBAC_ORG_ID = "b99df4a4-2bf5-4c08-9483-bd636470bc11"`

Identical literal. No second, possibly-inconsistent copy of the org id was introduced by this
task — I did not add any new org-id literal at all; the new cases rely entirely on the existing
constant/fixture pairing already wired up by earlier tasks. Confirmed by direct string comparison
of both files, not just inspection.

**Code quality:**
- Diff is purely additive: `git diff --stat` shows only the new SQL block (+19/-1, the -1 being
  the "4"→"5" comment line) and the new Java method + one `all()` line (+71/-0). No existing
  lines in either file were altered beyond that single comment.
- Consistent formatting with existing code (indentation, JavaDoc style, `q()` helper usage,
  `NEEDS_CLAIM_SWAP` capability set on every case, matching every other group in this file).
- SQL block re-read once after editing: uses the same `CREATE OR REPLACE TEMPORARY VIEW` +
  deterministic `ORDER BY ... LIMIT 1` idiom, the same INSERT column lists/ordering, and the same
  `'phase1-test-seed'` tenantHash/staticIdentifier convention as the other four seed blocks in
  the file. Could not be live-verified (no Databricks credentials in this environment), per the
  task's stated constraint.

**Test coverage / integration:**
- OT-R1: overlapping org-subtree + explicit grant → org-subtree count unaffected (10)
- OT-R2: empty org (3a empty) + explicit grant (3b) → 1 (clean additivity proof)
- OT-R3: RBAC_ABAC doesn't leak into non-root tables → 0
- OT-R4: org-driven visibility with no assignment → 10
- OT-ODEL: soft-deleted org-hierarchy edge excluded → 0
- OT-OLIVE: same org, live edge → 10 (isolates the isDeleted flag against OT-ODEL)
- Folded into `all()` without disrupting `functionalCases()`, `abacGroupCases()`,
  `permGroupCases()`, or `compatibleQueryCases()` — the CSV-backed 50-query group still loads and
  counts correctly (confirmed via the `all().size() == 77` assertion, which requires
  `compatibleQueryCases()` to also succeed).

## Commit Hash

```
(see final message)
```

## Status Summary

COMPLETE. Seeded the 5th identity (`u.assets.owner@example.com`, assignment 900005 on a real
`ASSETS`-type entity) in `sql_onetrust/05_seed_test_principals.sql`, and added 6 new RBAC-group
test cases (`OT-R1..OT-R4`, `OT-ODEL`, `OT-OLIVE`) to `OnetrustCases.java`, folded into `all()`.
Build succeeds (`BUILD SUCCESS`), smoke test passes (`rbacGroupCases(): 6`, `all(): 77`).
`RBAC_ORG_ID` consistency double-checked against `Runner.java`'s fixture constant — identical,
no second copy introduced. Existing 71 cases unaffected (diff is purely additive except one
expected-count comment).

## Fix Report

**Bug fixed:** OT-R3 case in `rbacGroupCases()` had incorrect `root` value in `assetsOwnerAbacClaim`.

**Root cause:** `assetsOwnerAbacClaim` was initialized with `root="CONTROL"` (line 247), but the case
queries `cmb_controlimplementation` which binds object_type `'CONTROL'`. This made `ctx.root = object_type`
TRUE, contradicting the case's documented intent: "RBAC_ABAC does not help non-root tables: 3a lives
only inside root=object_type -> 0" — the case was supposed to prove that when root does NOT match the
queried table's bound object_type, branch 3 stays closed.

**Fix applied:** Changed line 247 in `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`:
- **Before:** `String assetsOwnerAbacClaim = Cases.claim("u.assets.owner@example.com", "SUITE_ORG", "RBAC_ABAC", "CONTROL", "[]");`
- **After:** `String assetsOwnerAbacClaim = Cases.claim("u.assets.owner@example.com", "SUITE_ORG", "RBAC_ABAC", "ASSETS", "[]");`

This aligns with OT-R1/OT-R2/OT-R4's root value (`"ASSETS"`), correctly making root ("ASSETS") mismatch
the queried table's actual bound type ("CONTROL"), so branch 3's gate now genuinely stays closed for
the intended reason: `ctx.root != object_type`.

**Verification:**
- Fixed line re-read: ✓ `assetsOwnerAbacClaim` now has `root="ASSETS"`
- Build: ✓ `cd JDBC && mvn -q package` → clean, jar created at `target/jdbc-client-1.0-SNAPSHOT.jar`
- No other changes: ✓ Only the one `root` argument changed; case id, purpose, description, sql,
  Expect, NEEDS_CLAIM_SWAP, and all other variables unchanged.
