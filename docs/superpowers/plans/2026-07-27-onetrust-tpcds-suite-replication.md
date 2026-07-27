# OneTrust ⟷ TPC-DS JDBC Suite Replication Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replicate the TPC-DS JDBC functional test suite (61 cases across 16 groups + 8 scenarios) onto the OneTrust deployment (`abac_onetrust.onetrust_sim`), reaching full parity with what's already proven live against `abac_tpcds`.

**Architecture:** Two tiers. Tier A (23 cases: ABAC/PERM/RBAC/TENANT/ORG) validates the real row-filter logic against OneTrust's actual seeded data — needs expanded real seed data, not new mechanism. Tier B (38 cases: EDGE/CONFLICT/META/THRESH/RLS/V/SC/TG/UC/XT/EX/CL + 8 scenarios) validates Unity Catalog ABAC mechanism itself via isolated throwaway schemas under `abac_onetrust`, mostly a mechanical port of `sql/12-21`.

**Tech Stack:** Java 17, Maven, the existing `Engine`/`Case`/`Scenario` framework in `JDBC/`, plain SQL under `sql_onetrust/`.

Reference spec: `docs/superpowers/specs/2026-07-27-onetrust-tpcds-suite-replication-design.md`.

## Global Constraints

- **Isolated schemas for Tier B**, never OneTrust's real 11+5 tables: `abac_onetrust.abac_scope`, `abac_onetrust.abac_tags`, `abac_onetrust.abac_udf`, `abac_onetrust.abac_xmech`, `abac_onetrust.abac_gaps` (mirrors `abac_tpcds.abac_scope` etc. 1:1, just a different catalog).
- **Duplicate scenario classes** (`Onetrust*.java`), never generalize the existing TPC-DS ones (`Dr2HotSwap`, `ViewPolicySwap`, `SecretInvariance`, `SecondPrincipal`, `TokenExpiry`) — zero risk to already-"confirmed live" code.
- **Never break what's shipped:** the TPC-DS suite (`Cases.all(engine)`, 60 cases + 8 scenarios via `runAll`), OneTrust's `OT-T1`..`OT-T8` + 50 real-query cases (`OnetrustCases.functionalCases()`/`compatibleQueryCases()`), and `Runner.java`'s `ONETRUST_CLIENT_ID`/`ONETRUST_CLIENT_SECRET` second-principal wiring (`runOnetrustCases`) must all keep working byte-for-byte unless a task explicitly changes them. Every task ends by running `mvn -q package` and confirming the shaded jar still builds (`JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar`), plus the standalone case-construction smoke test pattern already used for `OnetrustCases` (build the case list in isolation, assert counts/claims, no live credentials needed) before any live-credential verification.
- **Never commit an SP secret.** Application ids only, matching every other file in this repo.
- **Real data grounding for Tier A:** every claim/assertion must trace to actual generated OneTrust data — real entity ids, real orgs (from `abac_onetrust.onetrust_sim.OrgHierarchyBase`/`ABAC_OrgHierarchy`), documented explicitly where the real data's shape forces an honest adaptation from TPC-DS's exact case (e.g. R1's additive-union demonstration — see Task 4).
- **Never assert on elapsed time.** `OnetrustDr2HotSwap` measures and prints propagation latency; it never PASS/FAILs on a duration.
- **The SP the suite authenticates as (OneTrust side):** wherever a task shows `<ONETRUST_SP>`, substitute the same service principal application id already used for `07_oauth_wiring.sql` and `ONETRUST_CLIENT_ID` (the operator's real value — never hardcode a literal id into committed SQL/Java; the existing files use the same placeholder convention, see `sql_onetrust/04_policies.sql`).
- **SQL file numbering** continues `sql_onetrust/`'s own sequence (currently `01`-`07`), not TPC-DS's `12`-`21` numbers.
- **Build/verify commands:** `cd JDBC && mvn -q package` (compile + shaded jar). Live run: `INCLUDE_ONETRUST=true ONETRUST_CLIENT_ID=... ONETRUST_CLIENT_SECRET=... java -jar target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar` (needs `CLIENT_ID`/`CLIENT_SECRET`/`WORKSPACE_HOST`/`WAREHOUSE_ID` too, for the primary TPC-DS connection `main()` always opens first). SQL setup files are applied by the **operator** in the Databricks SQL editor as owner (matching the existing `sql_onetrust/07_oauth_wiring.sql` pattern) — the implementing agent cannot run these live; each task's SQL step says so explicitly and the operator runs it before that task's Java case can be live-verified.

---

## File Structure

| File | Responsibility |
|---|---|
| `JDBC/src/main/java/com/abacpoc/Runner.java` | Modified: `onetrustFixtureInserts`/`onetrustFixtureDeletes` (Tier A self-seeding org fixture) called from `runOnetrustCases`; scenario loop added to `runOnetrustCases` (mirrors `runAll`'s scenario loop) |
| `sql_onetrust/05_seed_test_principals.sql` | Modified: one more explicit real assignment (a 3rd named identity, different root type, for the ABAC/PERM group's "different table, same mechanism" cases) |
| `sql_onetrust/08_row_filter_conflict.sql` .. `sql_onetrust/17_except_and_defaults.sql` | New: ported 1:1 from `sql/12-21`, catalog/SP swapped |
| `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java` | Modified: grows to include all Tier A + Tier B cases (`abacGroupCases()`, `permGroupCases()`, `rbacGroupCases()`, `tenantOrgGroupCases()`, `conflictGroupCases()`, `metaGroupCases()`, `threshGroupCases()`, `rlsGroupCases()`, `viewGroupCases()`, `scGroupCases()`, `tgGroupCases()`, `ucGroupCases()`, `xtGroupCases()`, `exGroupCases()`, `clGroupCases()`, all folded into `all()`) |
| `JDBC/src/main/java/com/abacpoc/scenario/OnetrustDr2HotSwap.java` | New |
| `JDBC/src/main/java/com/abacpoc/scenario/OnetrustViewPolicySwap.java` | New |
| `JDBC/src/main/java/com/abacpoc/scenario/OnetrustSecretInvariance.java` | New |
| `JDBC/src/main/java/com/abacpoc/scenario/OnetrustSecondPrincipal.java` | New |
| `JDBC/src/main/java/com/abacpoc/scenario/OnetrustTokenExpiry.java` | New |
| `JDBC/src/main/java/com/abacpoc/scenario/OnetrustE6Scenarios.java` | New |

---

### Task 1: Tier A foundation — self-seeding org fixture in Runner.java

**Files:**
- Modify: `JDBC/src/main/java/com/abacpoc/Runner.java`

**Interfaces:**
- Produces: `Runner.onetrustFixtureInserts()` / `Runner.onetrustFixtureDeletes()` (static, no args — mirrors the existing `fixtureInserts(Engine e)`/`fixtureDeletes(Engine e)` pattern but hardcodes the `abac_onetrust.onetrust_sim` prefix directly, matching `OnetrustCases`'s existing `q()` helper convention), called from `runOnetrustCases` before/after the case loop.

**Context:** TPC-DS's `Runner.setUpFixture`/`dropFixture` insert namespaced rows (`SUITE_ORG`, 5 real customer addresses as its live children) at the start of every run and drop them at the end, giving R1-R4/T1-T2/O1-O2 a `SUITE_ORG`/`SUITE_EMPTY` to test against without touching the real seed. OneTrust needs the same pattern, but grounded in `abac_onetrust.onetrust_sim.OrgHierarchyBase`'s real data.

**Real data this depends on:** `cmb_v_inventoryaggregatedrisksummary` is the only currently-policied OneTrust table with a real per-row org column (the other 3 — `cmb_assessment`/`cmb_controlimplementation`/`cmb_template` — use a literal `'100'` org, making org-subtree testing meaningless on them). All 14 of its real verbatim rows share **one** real `orgID`: `b99df4a4-2bf5-4c08-9483-bd636470bc11` (already used by `OT-T8`, confirmed live: 10 of the 14 rows are `ASSETS`-type). So `SUITE_ORG`'s child set must deliberately include this exact org id, not an arbitrary sample.

- [ ] **Step 1: Add the fixture insert/delete methods**

In `JDBC/src/main/java/com/abacpoc/Runner.java`, add these two methods near the existing `fixtureInserts`/`fixtureDeletes` (same file, after `fixtureDeletes`):

```java
    // ---- OneTrust self-seeding fixture (namespaced; inserted at start, dropped at end of
    // runOnetrustCases). Mirrors fixtureInserts/fixtureDeletes above but hardcodes the OneTrust
    // schema directly (OnetrustCases does the same -- see its q() helper) since abac_onetrust and
    // abac_tpcds are different catalogs and Engine.qualify() is locked to the TPC-DS prefix.
    //
    // SUITE_ORG's child MUST include b99df4a4-2bf5-4c08-9483-bd636470bc11 -- the one real orgID
    // all 14 verbatim cmb_v_inventoryaggregatedrisksummary rows carry (confirmed live via OT-T8:
    // 10 of the 14 are ASSETS-type). A generic "first N orgs" sample would very likely miss it
    // entirely, since cmb_v_inventoryaggregatedrisksummary's real org has no special ordering
    // relationship to OrgHierarchyBase's other 67 real orgs.
    private static final String ONETRUST_SCHEMA = "abac_onetrust.onetrust_sim";
    private static final String ONETRUST_SUITE_ORG = "SUITE_ORG";
    private static final String ONETRUST_SUITE_EMPTY = "SUITE_EMPTY";
    private static final String ONETRUST_REAL_ASSETS_ORG = "b99df4a4-2bf5-4c08-9483-bd636470bc11";

    static String[] onetrustFixtureInserts() {
        return new String[] {
            "INSERT INTO " + ONETRUST_SCHEMA + ".OrgHierarchyBase "
                + "(rootOrgId, rootOrgName, orgId, orgName, parentOrgId, parentOrgName, eventTime, recModifiedTime, isDeleted, tenantHash) "
                + "SELECT rootOrgId, rootOrgName, orgId, orgName, '" + ONETRUST_SUITE_ORG + "', '" + ONETRUST_SUITE_ORG + "', "
                + "current_timestamp(), current_timestamp(), false, tenantHash "
                + "FROM " + ONETRUST_SCHEMA + ".OrgHierarchyBase "
                + "WHERE isDeleted = false AND orgId = '" + ONETRUST_REAL_ASSETS_ORG + "' LIMIT 1",
            // DEL_ORG / LIVE_ORG: the SAME real org id as both a soft-deleted and a live child --
            // isolates the isDeleted flag exactly like TPC-DS's ODEL/OLIVE pair (sql/13 Part E).
            "INSERT INTO " + ONETRUST_SCHEMA + ".OrgHierarchyBase "
                + "(rootOrgId, rootOrgName, orgId, orgName, parentOrgId, parentOrgName, eventTime, recModifiedTime, isDeleted, tenantHash) "
                + "SELECT rootOrgId, rootOrgName, orgId, orgName, 'DEL_ORG', 'DEL_ORG', "
                + "current_timestamp(), current_timestamp(), true, tenantHash "
                + "FROM " + ONETRUST_SCHEMA + ".OrgHierarchyBase "
                + "WHERE isDeleted = false AND orgId = '" + ONETRUST_REAL_ASSETS_ORG + "' LIMIT 1",
            "INSERT INTO " + ONETRUST_SCHEMA + ".OrgHierarchyBase "
                + "(rootOrgId, rootOrgName, orgId, orgName, parentOrgId, parentOrgName, eventTime, recModifiedTime, isDeleted, tenantHash) "
                + "SELECT rootOrgId, rootOrgName, orgId, orgName, 'LIVE_ORG', 'LIVE_ORG', "
                + "current_timestamp(), current_timestamp(), false, tenantHash "
                + "FROM " + ONETRUST_SCHEMA + ".OrgHierarchyBase "
                + "WHERE isDeleted = false AND orgId = '" + ONETRUST_REAL_ASSETS_ORG + "' LIMIT 1",
        };
        // SUITE_EMPTY needs no insert at all -- an org nothing references as parentOrgId is
        // already "no children" by construction (same as TPC-DS's SUITE_EMPTY).
    }

    static String[] onetrustFixtureDeletes() {
        return new String[] {
            "DELETE FROM " + ONETRUST_SCHEMA + ".OrgHierarchyBase "
                + "WHERE parentOrgId IN ('" + ONETRUST_SUITE_ORG + "', 'DEL_ORG', 'LIVE_ORG')",
        };
    }
```

- [ ] **Step 2: Wire fixture setup/teardown into `runOnetrustCases`**

In the same file, `runOnetrustCases` currently opens the second connection and calls `runCases` directly inside the `try (onetrustConn)` block. Change it to seed the fixture first and drop it in a `finally`:

```java
        try (onetrustConn) {
            for (String sql : onetrustFixtureInserts()) Jdbc.exec(onetrustConn, sql);
            try {
                List<Case> cases = OnetrustCases.all();
                System.out.println(" " + cases.size() + " cases");
                System.out.println("================================================================");
                int[] r = runCases(engine, onetrustConn, cases);
                System.out.println();
                System.out.println("================================================================");
                System.out.println(" ONETRUST SUMMARY  ->  PASS " + r[0]
                                 + "   FAIL " + r[1] + "   SKIP " + r[2]
                                 + "   INFO " + r[3] + "   ERROR " + r[4]);
                System.out.println("================================================================");
            } finally {
                for (String sql : onetrustFixtureDeletes()) Jdbc.exec(onetrustConn, sql);
            }
        }
```

This replaces the existing body of the `try (onetrustConn) { ... }` block (the `List<Case> cases = OnetrustCases.all(); ...` lines) — keep everything else in `runOnetrustCases` (the `instanceof` check, `requireEnv` calls, `connectAs`) unchanged. Note the "58 cases (8 functional + 50 real compatible queries)" log line changes to a generic count since Tier A/B additions make the fixed breakdown stale — that's intentional, not a regression.

- [ ] **Step 3: Compile and verify no regression**

```bash
cd JDBC && mvn -q package
```

Expected: `BUILD SUCCESS`, `target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar` produced. This task adds unused-until-later methods (`OnetrustCases.all()` still only has the existing 58 cases), so no behavior change yet — the compile passing is the only bar for this task.

- [ ] **Step 4: Commit**

```bash
git add JDBC/src/main/java/com/abacpoc/Runner.java
git commit -m "feat: OneTrust self-seeding org fixture (SUITE_ORG/SUITE_EMPTY/DEL_ORG/LIVE_ORG)"
```

---

### Task 2: Tier A — expand seed data for a 3rd real explicit-assignment identity

**Files:**
- Modify: `sql_onetrust/05_seed_test_principals.sql`

**Interfaces:**
- Produces: a 4th named seed identity, `u.template.owner@example.com`, with a real explicit `USER_ID` assignment on one seeded `cmb_template` entity (assignment id `900004`). Later Tier A tasks (ABAC group) consume this identity's claim: `{"tenant":1,"user":"u.template.owner@example.com","org":"100","mode":"ABAC","root":"TEMPLATE","permissions":[]}`.

**Context:** The existing seed (`u.assessment.owner`/ASSESSMENT, `u.inactive.grant`/ASSESSMENT inactive, `test_group_1`+`u.group.member`/CONTROL) covers 2 of the 4 policied tables' root-type explicit-assignment mechanism. TPC-DS's A4 ("Item tester — same mechanism, different root/table") needs a 3rd table represented by a real, direct `USER_ID` assignment (not via a group) so Tier A's ABAC group can demonstrate the SAME mechanism on a 3rd table, matching A2/A4's pattern of "identical logic, different table" rather than reusing an already-covered table.

- [ ] **Step 1: Add the 4th seed identity to `sql_onetrust/05_seed_test_principals.sql`**

Read the current file first — it ends with the `-- Expected: ...` comment after the `UserGroupMembers` insert. Insert this new block **before** that final comment, right after the existing `UserGroupMembers` INSERT statement:

```sql
-- one real cmb_template id, picked the same deterministic way as seed_assessment_entity/
-- seed_control_entity above.
CREATE OR REPLACE TEMPORARY VIEW seed_template_entity AS
  SELECT id AS entity_id FROM abac_onetrust.onetrust_sim.cmb_template ORDER BY id LIMIT 1;

-- assignment 900004: explicit grant on the seeded template to u.template.owner (3rd real
-- explicit-assignment identity, direct USER_ID -- not via a group, unlike test_group_1/CONTROL --
-- so the ABAC-group cases have a same-mechanism-different-table pair independent of the group path).
INSERT INTO abac_onetrust.onetrust_sim.ABAC_Assignment
  (id, guid, staticIdentifier, name, objectType, sourceType, isActive, createdBy, createDT, updatedBy, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900004, uuid(), 'phase1-test-seed', 'Owner', 'TEMPLATE', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false;

INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900004, NULL, entity_id, NULL, 'u.template.owner@example.com', 'USER_ID', 'TEMPLATE', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
FROM seed_template_entity;
```

Also update the leading `DELETE FROM ... WHERE staticIdentifier = 'phase1-test-seed'` at the top of the file — it already deletes by `staticIdentifier`/`tenantHash` = `'phase1-test-seed'`, which covers assignment `900004` too (same namespacing as `900001`-`900003`), so **no change needed there** — confirm this by reading the file's `DELETE` statements before editing (they filter by the shared marker, not by explicit id list).

Update the trailing comment's expected count from 3 to 4:

```sql
-- Expected: no error; SELECT count(*) FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
-- WHERE tenantHash = 'phase1-test-seed' returns 4.
```

- [ ] **Step 2: Apply the updated SQL against the live workspace (operator step)**

This cannot run in this task's automated verification — it requires a live Databricks connection as owner. Note in the task's completion report that the operator must run the updated `05_seed_test_principals.sql` (idempotent — it deletes prior `phase1-test-seed` rows first) before Task 3's live case verification can pass. `SELECT count(*) FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-test-seed'` should return 4 after applying.

- [ ] **Step 3: Commit**

```bash
git add sql_onetrust/05_seed_test_principals.sql
git commit -m "feat: seed a 4th real explicit-assignment identity (u.template.owner, cmb_template)"
```

---

### Task 3: Tier A — ABAC group (9 cases, `OT-A1`..`OT-A9`)

**Files:**
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Consumes: `Cases.claim(...)` (existing helper, unchanged), the 4 real seeded identities from `05_seed_test_principals.sql` (`u.assessment.owner@example.com`/`cmb_assessment`, `u.template.owner@example.com`/`cmb_template`, `test_group_1`+`u.group.member@example.com`/`cmb_controlimplementation`), the real known id `cmb_assessment_0` (confirmed live earlier this session via curl — the seeded assessment's actual generated id).
- Produces: `OnetrustCases.abacGroupCases()` returning `List<Case>`, folded into `all()`.

**Context:** Mirrors TPC-DS's A1-A9 (`Cases.java` lines 62-97) — root-type explicit assignment: baseline allow, exact-id-list, a 2nd table with the same mechanism, deny-wrong-user/empty-user/wrong-root, and the branch-2 (permissions) related-type case. **A5 is honestly adapted**: TPC-DS's A5 demonstrates one assignment granting visibility into MANY physical rows sharing one entity id (`store_sales` fan-out); no currently-policied OneTrust table has that shape (each is ~1 real row per real id), so `OT-A5` instead uses the group-membership mechanism (distinct from A2/A4's direct `USER_ID` grants) to keep the group's mechanism coverage non-redundant.

- [ ] **Step 1: Add `abacGroupCases()` to `OnetrustCases.java`**

Add this method after `functionalCases()` (before `compatibleQueryCases()`):

```java
    /**
     * Mirrors TPC-DS's A1-A9 (Cases.java) -- root-type explicit assignment: baseline allow,
     * exact-id-list, same mechanism on a 2nd/3rd table, deny variants, branch-2 permissions.
     * OT-A5 is an honest adaptation, not a 1:1 port -- see the class-level note below.
     */
    public static List<Case> abacGroupCases() {
        String ownerClaim = Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "ASSESSMENT", "[]");
        String disabledClaim = Cases.claim("u.disabled.mode@example.com", "100", "DISABLE", "ASSESSMENT", "[]");
        String templateOwnerClaim = Cases.claim("u.template.owner@example.com", "100", "ABAC", "TEMPLATE", "[]");
        String groupMemberClaim = Cases.claim("u.group.member@example.com", "100", "ABAC", "CONTROL", "[]");
        String emptyUserClaim = Cases.claim("", "100", "ABAC", "ASSESSMENT", "[]");
        String permissionsClaim = Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "ASSESSMENT", "[\"CONTROL\"]");

        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-A1", "ABAC", "DISABLE -> branch 1 fires; show all cmb_assessment rows, identity ignored.",
            "Mirrors TPC-DS A1.",
            disabledClaim, "SELECT count(*) FROM " + q("cmb_assessment"), Expect.all(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A2", "ABAC", "Baseline: branch 3b EXISTS matches the seeded assessment -> 1.",
            "Mirrors TPC-DS A2 (the baseline everything else in this group contrasts against).",
            ownerClaim,
            "SELECT count(*) FROM " + q("cmb_assessment")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.assessment.owner@example.com' AND objectType = 'ASSESSMENT' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A3", "ABAC", "The visible id list is exactly [cmb_assessment_0] (asserted; observed run: cmb_assessment_0).",
            "Mirrors TPC-DS A3. Same claim/evaluation as OT-A2, but projects id instead of count(*) -- "
                + "proves the analyst sees precisely their assigned entity and no other id leaks.",
            ownerClaim, "SELECT id FROM " + q("cmb_assessment") + " ORDER BY id",
            Expect.exactIds("cmb_assessment_0"), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A4", "ABAC", "Template tester -> same mechanism, different table (cmb_template).",
            "Mirrors TPC-DS A4. u.template.owner's real explicit assignment (seeded Task 2) on the same "
                + "direct-USER_ID mechanism as OT-A2, on a different table.",
            templateOwnerClaim,
            "SELECT count(*) FROM " + q("cmb_template")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.template.owner@example.com' AND objectType = 'TEMPLATE' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A5", "ABAC",
            "Group tester -> same table-count mechanism via GROUP membership, not direct USER_ID (adapted from TPC-DS A5).",
            "TPC-DS A5 demonstrates one assignment granting MANY physical rows sharing an entity id "
                + "(store_sales fan-out) -- no currently-policied OneTrust table has that shape (each is "
                + "~1 real row per real id). Adapted to keep this group's mechanism coverage non-redundant: "
                + "OT-A5 is the GROUP-membership grant (distinct from OT-A2/OT-A4's direct USER_ID grants).",
            groupMemberClaim,
            "SELECT count(*) FROM " + q("cmb_controlimplementation")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'test_group_1' AND objectType = 'CONTROL' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A6", "ABAC", "Deny wrong user: template tester has no ASSESSMENT assignment.",
            "Mirrors TPC-DS A6. u.template.owner is assigned only on cmb_template (TEMPLATE); querying "
                + "cmb_assessment under root=ASSESSMENT finds no matching grant.",
            templateOwnerClaim, "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A7", "ABAC", "Deny empty user: '' matches no real subjectID.",
            "Mirrors TPC-DS A7.",
            emptyUserClaim, "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A8", "ABAC", "Deny wrong root: root != the queried table's object type.",
            "Mirrors TPC-DS A8. u.assessment.owner's claim but root=CONTROL while querying cmb_assessment.",
            Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "CONTROL", "[]"),
            "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-A9", "ABAC", "Non-root table via branch 2: CONTROL in permissions -> ALL cmb_controlimplementation rows.",
            "Mirrors TPC-DS A9. root=ASSESSMENT but permissions=[CONTROL]; branch 2 fires because "
                + "root<>object_type AND array_contains(permissions,'CONTROL') -- coarse, "
                + "assignment-independent access to the whole related table (contrast OT-A5's per-row grant).",
            permissionsClaim, "SELECT count(*) FROM " + q("cmb_controlimplementation"), Expect.all(), NEEDS_CLAIM_SWAP));

        return cs;
    }
```

- [ ] **Step 2: Fold into `all()`**

Change `OnetrustCases.all()` from:

```java
    public static List<Case> all() {
        List<Case> cs = new ArrayList<>();
        cs.addAll(functionalCases());
        cs.addAll(compatibleQueryCases());
        return cs;
    }
```

to:

```java
    public static List<Case> all() {
        List<Case> cs = new ArrayList<>();
        cs.addAll(functionalCases());
        cs.addAll(abacGroupCases());
        cs.addAll(compatibleQueryCases());
        return cs;
    }
```

(Later tasks each add one more `cs.addAll(...)` line here, in the order the groups appear in this plan — keeps `all()`'s diff-per-task small and reviewable.)

- [ ] **Step 3: Compile and smoke-test case construction (no live credentials needed)**

```bash
cd JDBC && mvn -q package
```

Then, mirroring the standalone smoke-test pattern already used for `OnetrustCases` (see the session's earlier verification of `functionalCases()`/`compatibleQueryCases()`): build a small scratch `SmokeTest.java` that calls `OnetrustCases.abacGroupCases()` and asserts `size() == 9`, and that `OT-A3`'s case has `exp().ids.equals(List.of("cmb_assessment_0"))`. Compile it against `target/classes` plus the Maven-resolved classpath (`mvn -q dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt`), run it, confirm the output, then delete the scratch file — this validates the Java compiles and the case list is well-formed before any live run is attempted.

Expected: `BUILD SUCCESS`; smoke test prints 9 cases with the `OT-A1`..`OT-A9` ids.

- [ ] **Step 4: Commit**

```bash
git add JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier A ABAC group (OT-A1..OT-A9) for the OneTrust JDBC suite"
```

---

### Task 4: Tier A — PERM group (4 cases, `OT-B1`..`OT-B4`)

**Files:**
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Consumes: `Cases.claim(...)`, `q()` (both already used by `abacGroupCases()`), `u.assessment.owner@example.com`.
- Produces: `OnetrustCases.permGroupCases()` returning `List<Case>`, folded into `all()`.

**Context:** Mirrors TPC-DS's B1-B4 (`Cases.java` lines 100-118) — the `permissions` (branch 2) path in isolation: one claim grants table-wide read of multiple related types at once, denies when a type is omitted, denies on wrong string format (dot-notation vs. real object-type string).

- [ ] **Step 1: Add `permGroupCases()` to `OnetrustCases.java`**

Add after `abacGroupCases()`:

```java
    /** Mirrors TPC-DS's B1-B4 (Cases.java) -- the permissions (branch 2) path in isolation. */
    public static List<Case> permGroupCases() {
        String multiPermClaim = Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "ASSESSMENT", "[\"CONTROL\",\"TEMPLATE\"]");
        String omittedPermClaim = Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "ASSESSMENT", "[\"TEMPLATE\"]");
        String wrongFormatClaim = Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "ASSESSMENT", "[\"control.view\",\"template.view\"]");

        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-B1", "PERM", "CONTROL visible via branch 2 (CONTROL in permissions) -> ALL controls.",
            "Mirrors TPC-DS B1. root=ASSESSMENT, permissions=[CONTROL,TEMPLATE], query cmb_controlimplementation.",
            multiPermClaim, "SELECT count(*) FROM " + q("cmb_controlimplementation"), Expect.all(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-B2", "PERM", "cmb_template via branch 2 (TEMPLATE in permissions) -> ALL templates.",
            "Mirrors TPC-DS B2. Same claim as OT-B1, query cmb_template -- one permissions claim opens "
                + "every governed related table it lists.",
            multiPermClaim, "SELECT count(*) FROM " + q("cmb_template"), Expect.all(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-B3", "PERM", "Deny on a GOVERNED non-root table: CONTROL NOT in permissions (only TEMPLATE) -> 0.",
            "Mirrors TPC-DS B3. root=ASSESSMENT, permissions=[TEMPLATE] (CONTROL deliberately omitted), "
                + "query cmb_controlimplementation.",
            omittedPermClaim, "SELECT count(*) FROM " + q("cmb_controlimplementation"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-B4", "PERM", "Wrong format: 'control.view' != object type 'CONTROL' -> branch 2 array_contains fails -> 0.",
            "Mirrors TPC-DS B4. permissions=['control.view','template.view'] (dot-notation, not object "
                + "types) -- branch 2 compares against the OBJECT TYPE string 'CONTROL', not a permission string.",
            wrongFormatClaim, "SELECT count(*) FROM " + q("cmb_controlimplementation"), Expect.zero(), NEEDS_CLAIM_SWAP));

        return cs;
    }
```

- [ ] **Step 2: Fold into `all()`**

```java
        cs.addAll(abacGroupCases());
        cs.addAll(permGroupCases());
        cs.addAll(compatibleQueryCases());
```

- [ ] **Step 3: Compile and smoke-test**

```bash
cd JDBC && mvn -q package
```

Same scratch-smoke-test pattern as Task 3 Step 3, asserting `OnetrustCases.permGroupCases().size() == 4`.

- [ ] **Step 4: Commit**

```bash
git add JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier A PERM group (OT-B1..OT-B4) for the OneTrust JDBC suite"
```

---

### Task 5: Tier A — RBAC group (6 cases: `OT-R1`..`OT-R4`, `OT-ODEL`, `OT-OLIVE`)

**Files:**
- Modify: `sql_onetrust/05_seed_test_principals.sql` (5th identity)
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Consumes: `SUITE_ORG`/`SUITE_EMPTY`/`DEL_ORG`/`LIVE_ORG` (Task 1's `Runner.onetrustFixtureInserts`), `RBAC_ORG_ID` constant (already defined in `OnetrustCases.java` for `OT-T8`).
- Produces: `OnetrustCases.rbacGroupCases()` returning `List<Case>`, folded into `all()`. A 5th real seeded identity, `u.assets.owner@example.com`, with an explicit assignment (id `900005`) on a real `ASSETS`-type entity from `cmb_v_inventoryaggregatedrisksummary`.

**Context:** Mirrors TPC-DS's R1-R4 + ODEL + OLIVE (`Cases.java` lines 121-242) — RBAC_ABAC org-subtree behavior, additive with per-row assignment, and the soft-deleted org-hierarchy edge pair. **`OT-R1` is honestly adapted**, documented inline: all 14 real `cmb_v_inventoryaggregatedrisksummary` rows share the *same single real org* (`b99df4a4-2bf5-4c08-9483-bd636470bc11`), so an org-subtree grant and any explicit assignment on this table always overlap completely — true additivity (org-subtree ∪ assignment > either alone) genuinely can't be demonstrated without fabricating a second org, which would violate this plan's real-data-grounding constraint. `OT-R1` instead confirms the org-subtree count is *unaffected* by an overlapping explicit grant (robustness), and `OT-R2` provides the clean assignment-alone proof TPC-DS's R2 does.

- [ ] **Step 1: Seed the 5th identity in `sql_onetrust/05_seed_test_principals.sql`**

Add after Task 2's `u.template.owner` block:

```sql
-- one real cmb_v_inventoryaggregatedrisksummary entity whose inventoryType is 'Assets' (maps to
-- object type 'ASSETS' via entity_type_to_object_type -- see config.INVENTORY_TYPE_TO_OBJECT_TYPE).
CREATE OR REPLACE TEMPORARY VIEW seed_assets_entity AS
  SELECT entityID AS entity_id FROM abac_onetrust.onetrust_sim.cmb_v_inventoryaggregatedrisksummary
  WHERE upper(inventoryType) = 'ASSETS' ORDER BY entityID LIMIT 1;

-- assignment 900005: explicit grant on the seeded ASSETS entity to u.assets.owner (5th real
-- explicit-assignment identity -- used by the RBAC group to prove 3b works independent of 3a).
INSERT INTO abac_onetrust.onetrust_sim.ABAC_Assignment
  (id, guid, staticIdentifier, name, objectType, sourceType, isActive, createdBy, createDT, updatedBy, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900005, uuid(), 'phase1-test-seed', 'Owner', 'ASSETS', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false;

INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900005, NULL, entity_id, NULL, 'u.assets.owner@example.com', 'USER_ID', 'ASSETS', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-test-seed', false
FROM seed_assets_entity;
```

Update the trailing expected-count comment from 4 to 5. Note for the operator (same as Task 2 Step 2): apply this live before this task's cases can be verified; `SELECT count(*) FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-test-seed'` should return 5.

- [ ] **Step 2: Add `rbacGroupCases()` to `OnetrustCases.java`**

Add after `permGroupCases()`:

```java
    /**
     * Mirrors TPC-DS's R1-R4 + ODEL/OLIVE (Cases.java). OT-R1 is an honest adaptation -- see the
     * class-level note in this plan's Task 5: all 14 real cmb_v_inventoryaggregatedrisksummary
     * rows share ONE real org, so org-subtree and per-row-assignment grants always overlap on
     * this table and true additivity can't be shown without a fabricated second org.
     */
    public static List<Case> rbacGroupCases() {
        String assetsOwnerRbacClaim = Cases.claim("u.assets.owner@example.com", "SUITE_ORG", "RBAC_ABAC", "ASSETS", "[]");
        String assetsOwnerEmptyOrgClaim = Cases.claim("u.assets.owner@example.com", "SUITE_EMPTY", "RBAC_ABAC", "ASSETS", "[]");
        String assetsOwnerAbacClaim = Cases.claim("u.assets.owner@example.com", "SUITE_ORG", "RBAC_ABAC", "CONTROL", "[]");
        String nobodySuiteOrgClaim = Cases.claim("u.nobody@example.com", "SUITE_ORG", "RBAC_ABAC", "ASSETS", "[]");
        String nobodyDelOrgClaim = Cases.claim("u.nobody@example.com", "DEL_ORG", "RBAC_ABAC", "ASSETS", "[]");
        String nobodyLiveOrgClaim = Cases.claim("u.nobody@example.com", "LIVE_ORG", "RBAC_ABAC", "ASSETS", "[]");

        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-R1", "RBAC",
            "RBAC_ABAC org=SUITE_ORG with an OVERLAPPING explicit assignment -> org-subtree count unaffected (10). Adapted, see class doc.",
            "TPC-DS R1 demonstrates additivity (org-subtree UNION explicit assignment > either alone). "
                + "OneTrust's single-real-org dataset means u.assets.owner's explicit assignment (900005) "
                + "is already covered by SUITE_ORG's subtree grant, so this instead proves the org-subtree "
                + "count is unaffected -- not doubled, not broken -- by a redundant per-row grant.",
            assetsOwnerRbacClaim,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary") + " WHERE upper(inventoryType) = 'ASSETS'",
            Expect.exact(10), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-R2", "RBAC",
            "RBAC_ABAC is ADDITIVE (3a OR 3b): org=SUITE_EMPTY has no children (3a empty), but the explicit assignment (3b) still shows -> 1.",
            "Mirrors TPC-DS R2. org=SUITE_EMPTY (no children seeded), so 3a's child set is empty; 3b EXISTS "
                + "still matches u.assets.owner's explicit assignment on the seeded ASSETS entity.",
            assetsOwnerEmptyOrgClaim,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary")
                + " WHERE entityID = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.assets.owner@example.com' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-R3", "RBAC", "RBAC_ABAC does not help non-root tables: 3a lives only inside root=object_type -> 0.",
            "Mirrors TPC-DS R3. mode=RBAC_ABAC, root=ASSETS, query cmb_controlimplementation (a "
                + "different, non-root table) -- branch 3 (where 3a lives) never opens for it.",
            assetsOwnerAbacClaim, "SELECT count(*) FROM " + q("cmb_controlimplementation"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-R4", "RBAC", "RBAC_ABAC is org-driven: a user with NO assignment -> only branch 3a's org subtree -> 10.",
            "Mirrors TPC-DS R4. u.nobody has no assignments anywhere; mode=RBAC_ABAC, org=SUITE_ORG. "
                + "3b EXISTS finds nothing, but 3a matches all 10 real ASSETS-type rows via the org subtree "
                + "-- proves 3a is purely org-driven, independent of any grant.",
            nobodySuiteOrgClaim,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary") + " WHERE upper(inventoryType) = 'ASSETS'",
            Expect.exact(10), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-ODEL", "RBAC",
            "DEL_ORG's only child is soft-deleted -> excluded from branch 3a's child set; nobody has no assignment (3b) -> 0.",
            "Mirrors TPC-DS ODEL. org=DEL_ORG; the fixture (Task 1) seeds the real ASSETS org as a child "
                + "of DEL_ORG with isDeleted=true, so ABAC_OrgHierarchy (filtered to isDeleted IS NOT TRUE) "
                + "excludes it -- 3a's child set is empty, and u.nobody has no assignment either.",
            nobodyDelOrgClaim,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary") + " WHERE upper(inventoryType) = 'ASSETS'",
            Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-OLIVE", "RBAC",
            "Control: the SAME org is a LIVE child of LIVE_ORG -> branch 3a includes it -> 10. Proves the isDeleted flag, not emptiness, excludes OT-ODEL.",
            "Mirrors TPC-DS OLIVE. The SAME real org id is also seeded as a live child of LIVE_ORG "
                + "(isDeleted=false) -- since it's the only org all 10 real ASSETS rows carry, all 10 pass. "
                + "The only difference from OT-ODEL is the isDeleted flag, proving the flag (not emptiness) is what excludes it.",
            nobodyLiveOrgClaim,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary") + " WHERE upper(inventoryType) = 'ASSETS'",
            Expect.exact(10), NEEDS_CLAIM_SWAP));

        return cs;
    }
```

- [ ] **Step 3: Fold into `all()`**

```java
        cs.addAll(permGroupCases());
        cs.addAll(rbacGroupCases());
        cs.addAll(compatibleQueryCases());
```

- [ ] **Step 4: Compile and smoke-test**

```bash
cd JDBC && mvn -q package
```

Same pattern, asserting `OnetrustCases.rbacGroupCases().size() == 6`.

- [ ] **Step 5: Commit**

```bash
git add sql_onetrust/05_seed_test_principals.sql JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier A RBAC group (OT-R1..OT-R4, OT-ODEL, OT-OLIVE) for the OneTrust JDBC suite"
```

---

### Task 6: Tier A — TENANT + ORG groups (4 cases: `OT-T1t`, `OT-T2t`, `OT-O1`, `OT-O2`)

**Files:**
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Consumes: `Cases.claim(long tenant, ...)` overload (already exists in `Cases.java`, takes an explicit tenant), the RBAC group's fixture orgs (Task 1).
- Produces: `OnetrustCases.tenantOrgGroupCases()` returning `List<Case>`, folded into `all()`.

**Naming note:** IDs use `T1t`/`T2t` (not `T1`/`T2`) to avoid colliding with the existing `06_test_cases.sql`-derived `OT-T1`..`OT-T8` in `functionalCases()` — this plan's group ids are otherwise a straight port of TPC-DS's `T1`/`T2`/`O1`/`O2`.

**Context:** Mirrors TPC-DS's T1-T2/O1-O2 (`Cases.java` lines 141-159) — `ctx.tenant` is never read by `abac_row_filter` (proven already for the TPC-DS deployment; same UDF template here), and `ctx.org` is read only inside the RBAC_ABAC branch, so it's inert in plain ABAC mode but drives visibility in RBAC_ABAC.

- [ ] **Step 1: Add `tenantOrgGroupCases()` to `OnetrustCases.java`**

Add after `rbacGroupCases()`:

```java
    /** Mirrors TPC-DS's T1-T2/O1-O2 (Cases.java) -- ctx.tenant is never read by the filter; ctx.org
     *  is read only inside the RBAC_ABAC branch (inert in plain ABAC). */
    public static List<Case> tenantOrgGroupCases() {
        String ownerClaimTenant999 = Cases.claim(999L, "u.assessment.owner@example.com", "100", "ABAC", "ASSESSMENT", "[]");
        String assetsRbacClaimTenant999 = Cases.claim(999L, "u.nobody@example.com", "SUITE_ORG", "RBAC_ABAC", "ASSETS", "[]");
        String orgUnusedAbacClaim = Cases.claim(1L, "u.assessment.owner@example.com", "ORG_UNUSED_999", "ABAC", "ASSESSMENT", "[]");
        String nobodySuiteEmptyRbacClaim = Cases.claim(1L, "u.nobody@example.com", "SUITE_EMPTY", "RBAC_ABAC", "ASSETS", "[]");

        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-T1t", "TENANT", "tenant is not read by the filter: OT-A2's claim with tenant=999 (vs tenant=1) -> identical result = 1.",
            "Mirrors TPC-DS T1. abac_row_filter never references ctx.tenant, so the tenant value cannot "
                + "affect any branch; evaluation is byte-identical to OT-A2.",
            ownerClaimTenant999,
            "SELECT count(*) FROM " + q("cmb_assessment")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.assessment.owner@example.com' AND objectType = 'ASSESSMENT' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-T2t", "TENANT", "tenant inert in RBAC_ABAC too: tenant=999, org=SUITE_ORG -> org still drives visibility -> same as OT-R4 = 10.",
            "Mirrors TPC-DS T2. The OT-R4 claim (mode=RBAC_ABAC, org=SUITE_ORG) but tenant=999 -- tenant "
                + "is again unread; org still drives 3a.",
            assetsRbacClaimTenant999,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary") + " WHERE upper(inventoryType) = 'ASSETS'",
            Expect.exact(10), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-O1", "ORG", "org is inert in ABAC mode (3a is the only reader, and it needs RBAC_ABAC): org=ORG_UNUSED_999 vs OT-A2's org=100 -> EXISTS unchanged -> 1.",
            "Mirrors TPC-DS O1. OT-A2's claim but org=ORG_UNUSED_999 and mode=ABAC -- ctx.org is read "
                + "ONLY inside 3a, which requires mode=RBAC_ABAC; in ABAC mode org is never consulted.",
            orgUnusedAbacClaim,
            "SELECT count(*) FROM " + q("cmb_assessment")
                + " WHERE id = (SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
                + " WHERE subjectId = 'u.assessment.owner@example.com' AND objectType = 'ASSESSMENT' LIMIT 1)",
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-O2", "ORG", "org DRIVES RBAC_ABAC: user with NO assignment + org=SUITE_EMPTY (no children) -> 3a empty AND 3b empty -> 0.",
            "Mirrors TPC-DS O2. Mirror of OT-R4 (org=SUITE_ORG -> 10); isolates the child-org set as 3a's "
                + "sole input by emptying it.",
            nobodySuiteEmptyRbacClaim,
            "SELECT count(*) FROM " + q("cmb_v_inventoryaggregatedrisksummary") + " WHERE upper(inventoryType) = 'ASSETS'",
            Expect.zero(), NEEDS_CLAIM_SWAP));

        return cs;
    }

```

- [ ] **Step 2: Fold into `all()`**

```java
        cs.addAll(rbacGroupCases());
        cs.addAll(tenantOrgGroupCases());
        cs.addAll(compatibleQueryCases());
```

- [ ] **Step 3: Compile and smoke-test**

```bash
cd JDBC && mvn -q package
```

Same pattern, asserting `OnetrustCases.tenantOrgGroupCases().size() == 4`. This closes out Tier A — at this point `OnetrustCases.all()` should total `8 + 9 + 4 + 6 + 4 + 50 = 81` cases (`functionalCases` + `abacGroupCases` + `permGroupCases` + `rbacGroupCases` + `tenantOrgGroupCases` + `compatibleQueryCases`). Assert this count in the smoke test.

- [ ] **Step 4: Commit**

```bash
git add JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier A TENANT+ORG groups (OT-T1t, OT-T2t, OT-O1, OT-O2) for the OneTrust JDBC suite"
```

**Tier A is now complete (23 cases: 9+4+6+4).** Task 6b (below) is the first Tier B task — the EDGE group, which needs no new SQL, so it's the natural bridge before the isolated-schema-heavy tasks that follow.

---

### Task 6b: Tier B — EDGE group (8 cases: `OT-C1`..`OT-C8`)

**Files:** Modify `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:** Produces `OnetrustCases.edgeGroupCases()` returning `List<Case>`, folded into `all()` (after `tenantOrgGroupCases()`, before `conflictGroupCases()`).

**Context:** Mirrors TPC-DS's C1-C8 (`Cases.java` lines 162-193) — claim parsing/case-sensitivity edge values against the real seeded assessment (`u.assessment.owner@example.com`, `OT-A2`'s entity), no new SQL. Raw JSON literals, same idiom as `OT-CL1`-`OT-CL4` (Task 17).

- [ ] **Step 1: Add `edgeGroupCases()`**

```java
    /** Mirrors TPC-DS's C1-C8 -- claim parsing/case-sensitivity, against the real seeded
     *  assessment (OT-A2's entity). No new SQL. */
    public static List<Case> edgeGroupCases() {
        String entitySubquery = "(SELECT entityId FROM " + q("ABAC_EntitySubjectAssignment")
            + " WHERE subjectId = 'u.assessment.owner@example.com' AND objectType = 'ASSESSMENT' LIMIT 1)";
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-C1", "EDGE", "mode 'abac' (lowercase): non-magic -> EXISTS path -> same as OT-A2.",
            "Mirrors TPC-DS C1. 'abac' is neither the magic 'DISABLE' nor 'RBAC_ABAC', so evaluation falls to 3b EXISTS.",
            Cases.claim("u.assessment.owner@example.com", "100", "abac", "ASSESSMENT", "[]"),
            "SELECT count(*) FROM " + q("cmb_assessment") + " WHERE id = " + entitySubquery,
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-C2", "EDGE", "mode 'disable' (lowercase): NOT allow-all (DISABLE is case-sensitive) -> 1.",
            "Mirrors TPC-DS C2. Branch 1 compares ctx.mode = 'DISABLE' case-SENSITIVELY.",
            Cases.claim("u.assessment.owner@example.com", "100", "disable", "ASSESSMENT", "[]"),
            "SELECT count(*) FROM " + q("cmb_assessment") + " WHERE id = " + entitySubquery,
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-C3", "EDGE", "root 'assessment' (lowercase) != 'ASSESSMENT' -> root branch fails -> 0.",
            "Mirrors TPC-DS C3. Branch 3's gate 'ctx.root = object_type' is case-sensitive.",
            Cases.claim("u.assessment.owner@example.com", "100", "ABAC", "assessment", "[]"),
            "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-C4", "EDGE", "missing 'permissions': from_json null; root path unaffected -> 1.",
            "Mirrors TPC-DS C4. The root/3b path never touches permissions.",
            "{\"tenant\":1,\"user\":\"u.assessment.owner@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"ASSESSMENT\"}",
            "SELECT count(*) FROM " + q("cmb_assessment") + " WHERE id = " + entitySubquery,
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-C5", "EDGE", "extra unknown field 'scope' ignored by from_json -> 1.",
            "Mirrors TPC-DS C5. from_json drops fields not in the target STRUCT.",
            "{\"tenant\":1,\"user\":\"u.assessment.owner@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"ASSESSMENT\",\"permissions\":[],\"scope\":\"xyz\"}",
            "SELECT count(*) FROM " + q("cmb_assessment") + " WHERE id = " + entitySubquery,
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-C6", "EDGE", "tenant as string \"1\": from_json tolerates the type mismatch; row set unchanged -> 1.",
            "Mirrors TPC-DS C6.",
            "{\"tenant\":\"1\",\"user\":\"u.assessment.owner@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"ASSESSMENT\",\"permissions\":[]}",
            "SELECT count(*) FROM " + q("cmb_assessment") + " WHERE id = " + entitySubquery,
            Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-C7", "EDGE", "empty claim {}: all fields null -> secure default deny -> 0.",
            "Mirrors TPC-DS C7. A malformed/empty claim fails closed.",
            "{}", "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-C8", "EDGE", "user mixed-case: exact subjectId compare fails -> 0.",
            "Mirrors TPC-DS C8. Identities are matched exactly, case included.",
            Cases.claim("U.Assessment.Owner@example.com", "100", "ABAC", "ASSESSMENT", "[]"),
            "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        return cs;
    }
```

- [ ] **Step 2: Fold into `all()`** — insert `cs.addAll(edgeGroupCases());` between `tenantOrgGroupCases()` and `conflictGroupCases()`.

- [ ] **Step 3: Compile and smoke-test**, asserting `.size() == 8`.

- [ ] **Step 4: Commit** — `git commit -m "feat: Tier B EDGE group (OT-C1..OT-C8) for the OneTrust JDBC suite"`.

---

### Task 7: Tier B — CONFLICT group (4 cases: `OT-W1`, `OT-WP1`, `OT-WP2`, `OT-WS1`)

**Files:**
- Create: `sql_onetrust/08_row_filter_conflict.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Produces: `OnetrustCases.conflictGroupCases()` returning `List<Case>`, folded into `all()`.

**Context:** Mirrors TPC-DS's W1 (`Cases.java` lines 200-205) + WP1/WP2/WS1 (ported from `sql/12_rowfilter_conflict.sql`) — `UC_ABAC_MULTIPLE_ROW_FILTERS`: two row filters on one table always errors, table-wide, regardless of which columns each binds or whether the query even touches the shared column. **Deviation from TPC-DS, per this plan's isolation constraint:** TPC-DS attaches these conflicting policies to its *real* tables (`warehouse`, `web_page`, `web_site`); OneTrust instead uses one new isolated schema, `abac_onetrust.abac_conflict`, with 3 throwaway tables mirroring those roles — never a real OneTrust table. W1's setup was done ad hoc via the UI in TPC-DS (not scripted anywhere); this port scripts it for the first time, consistent with everything else in this schema.

- [ ] **Step 1: Write `sql_onetrust/08_row_filter_conflict.sql`**

```sql
-- =====================================================================
-- 08_row_filter_conflict.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported from sql/12_rowfilter_conflict.sql (TPC-DS), for the OneTrust suite (OT-W1/OT-WP1/
-- OT-WP2/OT-WS1). Isolated schema, not a real OneTrust table -- see Task 7's plan note: TPC-DS's
-- W1/WP1/WP2/WS1 attach to real tables (warehouse/web_page/web_site); this port uses 3 throwaway
-- tables instead, matching the isolated-schema pattern the rest of Tier B follows.
--
--   conflict_a = Scenario A (was `warehouse`): two policies, allow-all + deny-all (OT-W1)
--   conflict_b = Scenario B (was `web_page`): two row filters, DIFFERENT column bindings (OT-WP1/OT-WP2)
--   conflict_c = Scenario C (was `web_site`): two row filters, SAME single column (OT-WS1)
--
-- PREDICTION (same mechanism confirmed live for TPC-DS): a row filter is TABLE-WIDE, at most ONE
-- per table (UC_ABAC_MULTIPLE_ROW_FILTERS), enforced at QUERY time, INDEPENDENT of the column list.
-- Both CREATE POLICY succeed per pair; every query on any of the 3 tables errors.
--
-- SP the JDBC suite authenticates as (owners bypass row filters): <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_conflict;

-- =========================================================
-- OT-W1 -- conflict_a: allow-all + deny-all
-- =========================================================
CREATE OR REPLACE TABLE abac_onetrust.abac_conflict.conflict_a (id BIGINT);
INSERT INTO abac_onetrust.abac_conflict.conflict_a SELECT id FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_conflict.conflict_a ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE FUNCTION abac_onetrust.abac_conflict.allow_all(id BIGINT) RETURNS BOOLEAN RETURN true;
CREATE OR REPLACE FUNCTION abac_onetrust.abac_conflict.deny_all(id BIGINT)  RETURNS BOOLEAN RETURN false;

CREATE OR REPLACE POLICY conflict_a_allow_policy
ON TABLE abac_onetrust.abac_conflict.conflict_a
ROW FILTER abac_onetrust.abac_conflict.allow_all
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

-- >>> If THIS 2nd policy errors at CREATE time, the conflict is caught at creation -- that is the finding.
CREATE OR REPLACE POLICY conflict_a_deny_policy
ON TABLE abac_onetrust.abac_conflict.conflict_a
ROW FILTER abac_onetrust.abac_conflict.deny_all
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

-- =========================================================
-- OT-WP1 / OT-WP2 -- conflict_b: DIFFERENT column bindings (col1,col2) vs (col2)
-- =========================================================
CREATE OR REPLACE TABLE abac_onetrust.abac_conflict.conflict_b (col1 BIGINT, col2 BIGINT);
INSERT INTO abac_onetrust.abac_conflict.conflict_b SELECT id, id * 2 FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_conflict.conflict_b ALTER COLUMN col1 SET TAGS ('abac_column_id'  = 'true');
ALTER TABLE abac_onetrust.abac_conflict.conflict_b ALTER COLUMN col2 SET TAGS ('abac_column_org' = 'true');

CREATE OR REPLACE FUNCTION abac_onetrust.abac_conflict.rf_b_1(c1 BIGINT, c2 BIGINT)
  RETURNS BOOLEAN RETURN true;
CREATE OR REPLACE FUNCTION abac_onetrust.abac_conflict.rf_b_2(c2 BIGINT)
  RETURNS BOOLEAN RETURN c2 IS NOT NULL;

CREATE OR REPLACE POLICY conflict_b_rf1_policy
ON TABLE abac_onetrust.abac_conflict.conflict_b
ROW FILTER abac_onetrust.abac_conflict.rf_b_1
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS c1, has_tag('abac_column_org') AS c2
USING COLUMNS (c1, c2);

CREATE OR REPLACE POLICY conflict_b_rf2_policy
ON TABLE abac_onetrust.abac_conflict.conflict_b
ROW FILTER abac_onetrust.abac_conflict.rf_b_2
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_org') AS c2
USING COLUMNS (c2);

-- =========================================================
-- OT-WS1 -- conflict_c: two row filters on the SAME column
-- =========================================================
CREATE OR REPLACE TABLE abac_onetrust.abac_conflict.conflict_c (id BIGINT);
INSERT INTO abac_onetrust.abac_conflict.conflict_c SELECT id FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_conflict.conflict_c ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE FUNCTION abac_onetrust.abac_conflict.rf_c_1(c BIGINT) RETURNS BOOLEAN RETURN true;
CREATE OR REPLACE FUNCTION abac_onetrust.abac_conflict.rf_c_2(c BIGINT) RETURNS BOOLEAN RETURN c IS NOT NULL;

CREATE OR REPLACE POLICY conflict_c_rf1_policy
ON TABLE abac_onetrust.abac_conflict.conflict_c
ROW FILTER abac_onetrust.abac_conflict.rf_c_1
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS c
USING COLUMNS (c);

CREATE OR REPLACE POLICY conflict_c_rf2_policy
ON TABLE abac_onetrust.abac_conflict.conflict_c
ROW FILTER abac_onetrust.abac_conflict.rf_c_2
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS c
USING COLUMNS (c);

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_conflict TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_conflict.conflict_a TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_conflict.conflict_b TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_conflict.conflict_c TO `<ONETRUST_SP>`;

-- Expect (as the SP via the suite): all 4 queries below error with UC_ABAC_MULTIPLE_ROW_FILTERS.
--   OT-W1:  SELECT count(*)  FROM conflict_a
--   OT-WP1: SELECT count(*)  FROM conflict_b
--   OT-WP2: SELECT col1      FROM conflict_b   (bound by rf_b_1 only -- still CONFLICT, table-wide)
--   OT-WS1: SELECT count(*)  FROM conflict_c

-- ---- TEARDOWN ----
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_conflict CASCADE;
```

- [ ] **Step 2: Add `conflictGroupCases()` to `OnetrustCases.java`**

```java
    /** Mirrors TPC-DS's W1/WP1/WP2/WS1 -- UC_ABAC_MULTIPLE_ROW_FILTERS, table-wide, regardless of
     *  column bindings. Setup: sql_onetrust/08_row_filter_conflict.sql (isolated schema). */
    public static List<Case> conflictGroupCases() {
        String schema = "abac_onetrust.abac_conflict";
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-W1", "CONFLICT", "Two policies on conflict_a (allow-all + deny-all): UC rejects the query -- at most one row filter per table.",
            "Mirrors TPC-DS W1. Setup: sql_onetrust/08_row_filter_conflict.sql.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".conflict_a",
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-WP1", "CONFLICT", "conflict_b count(*): two row filters with DIFFERENT bindings -> at most one row filter per table.",
            "Mirrors TPC-DS WP1.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".conflict_b",
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-WP2", "CONFLICT", "conflict_b SELECT col1: this column is bound by rf_b_1 ONLY, yet still errors -- the conflict is table-wide.",
            "Mirrors TPC-DS WP2. The conflict is detected at the TABLE level during planning, before any column-specific evaluation.",
            Cases.DISABLE_CLAIM, "SELECT col1 FROM " + schema + ".conflict_b",
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-WS1", "CONFLICT", "conflict_c count(*): two row filters on the SAME column -> at most one row filter per table.",
            "Mirrors TPC-DS WS1.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".conflict_c",
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"), NEEDS_CLAIM_SWAP));

        return cs;
    }
```

- [ ] **Step 3: Fold into `all()`** — add one line after Task 6b's `edgeGroupCases()` (not after `tenantOrgGroupCases()` directly — Task 6b already sits between them):

```java
        cs.addAll(edgeGroupCases());
        cs.addAll(conflictGroupCases());
        cs.addAll(compatibleQueryCases());
```

- [ ] **Step 4: Compile and smoke-test**

```bash
cd JDBC && mvn -q package
```

Same pattern, asserting `OnetrustCases.conflictGroupCases().size() == 4`. Note in the task's completion report that `sql_onetrust/08_row_filter_conflict.sql` must be applied live by the operator (with `<ONETRUST_SP>` substituted) before these 4 cases can be live-verified — they'll all `ERROR` with a connection/table-not-found error otherwise, not the expected `UC_ABAC_MULTIPLE_ROW_FILTERS`.

- [ ] **Step 5: Commit**

```bash
git add sql_onetrust/08_row_filter_conflict.sql JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier B CONFLICT group (OT-W1, OT-WP1, OT-WP2, OT-WS1) for the OneTrust JDBC suite"
```

---

### Task 8: Tier B — META group (4 cases: `OT-N1`..`OT-N4`)

**Files:**
- Create: `sql_onetrust/09_onboard_new_tables.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Produces: `OnetrustCases.metaGroupCases()` returning `List<Case>`, folded into `all()`.

**Context:** Mirrors TPC-DS's N1-N4 (ported from `sql/13_onboard_new_tables.sql`, PART A-D only — Part E's org rows are already covered by Task 1's `DEL_ORG`/`LIVE_ORG`) — onboarding a brand-new table under the **same deployed row filter**: soft-deleted assignment, group-membership grant, inactive assignment, soft-deleted assignment record. **Deviation from TPC-DS, per this plan's isolation constraint:** the 4 tables themselves live in a new isolated schema (`abac_onetrust.abac_meta`), not real OneTrust tables — but they're wired to the **real** deployed `abac_row_filter_wrapper_oauth` and seed **real** rows into the shared `ABAC_Assignment`/`ABAC_EntitySubjectAssignment`/`UserGroupMembers` tables (namespaced `'phase1-meta-seed'`, distinct from `05_seed_test_principals.sql`'s `'phase1-test-seed'` marker), since testing "a new table onboarding onto the existing shared filter infrastructure" is the whole point — isolating the metadata tables too would test something else.

- [ ] **Step 1: Write `sql_onetrust/09_onboard_new_tables.sql`**

```sql
-- =====================================================================
-- 09_onboard_new_tables.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported from sql/13_onboard_new_tables.sql (TPC-DS Parts A-D; Part E's org rows are already
-- covered by Task 1's DEL_ORG/LIVE_ORG fixture), for the OneTrust suite (OT-N1..OT-N4).
-- 4 throwaway tables in an isolated schema, wired to the REAL deployed
-- abac_row_filter_wrapper_oauth (see sql_onetrust/07_oauth_wiring.sql) -- the point of this group
-- is proving a brand-new table onboards correctly onto the EXISTING shared filter, so the
-- metadata rows are real, namespaced 'phase1-meta-seed' (not isolated like the tables).
--
-- Conditions exercised (all honored by the deployed abac_row_filter):
--   meta_promo -> esa.isDeleted = true             -> branch 3b EXISTS excluded  -> 0  (negative)
--   meta_store -> esa.subjectType = 'USER_GROUP'   -> group path via ugm          -> 1  (positive)
--   meta_cc    -> ABAC_Assignment.isActive = false -> JOIN a.isActive fails       -> 0  (negative)
--   meta_ship  -> ABAC_Assignment.isDeleted = true -> AND a.isDeleted=false fails -> 0  (negative)
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_meta;

CREATE OR REPLACE TABLE abac_onetrust.abac_meta.meta_promo (id BIGINT);
INSERT INTO abac_onetrust.abac_meta.meta_promo SELECT id FROM range(1, 21);
CREATE OR REPLACE TABLE abac_onetrust.abac_meta.meta_store (id BIGINT);
INSERT INTO abac_onetrust.abac_meta.meta_store SELECT id FROM range(1, 21);
CREATE OR REPLACE TABLE abac_onetrust.abac_meta.meta_cc (id BIGINT);
INSERT INTO abac_onetrust.abac_meta.meta_cc SELECT id FROM range(1, 21);
CREATE OR REPLACE TABLE abac_onetrust.abac_meta.meta_ship (id BIGINT);
INSERT INTO abac_onetrust.abac_meta.meta_ship SELECT id FROM range(1, 21);

ALTER TABLE abac_onetrust.abac_meta.meta_promo ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.abac_meta.meta_store ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.abac_meta.meta_cc    ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.abac_meta.meta_ship  ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

-- object type literals: any string not 'PROCESSING ACTIVITIES' passes through
-- entity_type_to_object_type() unchanged (see sql_onetrust/03_row_filter_udfs.sql).
CREATE OR REPLACE POLICY meta_promo_policy
ON TABLE abac_onetrust.abac_meta.meta_promo
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper_oauth
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'META_PROMO', '100');

CREATE OR REPLACE POLICY meta_store_policy
ON TABLE abac_onetrust.abac_meta.meta_store
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper_oauth
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'META_STORE', '100');

CREATE OR REPLACE POLICY meta_cc_policy
ON TABLE abac_onetrust.abac_meta.meta_cc
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper_oauth
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'META_CC', '100');

CREATE OR REPLACE POLICY meta_ship_policy
ON TABLE abac_onetrust.abac_meta.meta_ship
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_wrapper_oauth
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'META_SHIP', '100');

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_meta         TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_meta.meta_promo    TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_meta.meta_store    TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_meta.meta_cc       TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_meta.meta_ship     TO `<ONETRUST_SP>`;

-- PART B — ABAC_Assignment rows (the on/off + soft-delete switches). Real table, namespaced.
DELETE FROM abac_onetrust.onetrust_sim.ABAC_Assignment WHERE staticIdentifier = 'phase1-meta-seed';
DELETE FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-meta-seed';
DELETE FROM abac_onetrust.onetrust_sim.UserGroupMembers WHERE tenantHash = 'phase1-meta-seed';

INSERT INTO abac_onetrust.onetrust_sim.ABAC_Assignment
  (id, guid, staticIdentifier, name, objectType, sourceType, isActive, createdBy, createDT, updatedBy, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900101, uuid(), 'phase1-meta-seed', 'Owner', 'META_PROMO', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', false
UNION ALL
SELECT 900102, uuid(), 'phase1-meta-seed', 'Owner', 'META_STORE', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', false
UNION ALL
SELECT 900103, uuid(), 'phase1-meta-seed', 'Owner', 'META_CC', 'SYSTEM', false, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', false
UNION ALL
SELECT 900104, uuid(), 'phase1-meta-seed', 'Owner', 'META_SHIP', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', true;

-- PART C — ABAC_EntitySubjectAssignment rows.
-- meta_promo: esa.isDeleted = TRUE (negative)
INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
VALUES (900101, NULL, '1', NULL, 'u.meta.tester@example.com', 'USER_ID', 'META_PROMO', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', true);
-- meta_store: subjectType = USER_GROUP -> group 'meta_group_1' (positive; meta.tester is a member, Part D)
INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
VALUES (900102, NULL, '1', NULL, 'meta_group_1', 'USER_GROUP', 'META_STORE', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', false);
-- meta_cc: normal esa, but its assignment is inactive (negative)
INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
VALUES (900103, NULL, '1', NULL, 'u.meta.tester@example.com', 'USER_ID', 'META_CC', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', false);
-- meta_ship: normal esa, but its assignment is soft-deleted (negative)
INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
VALUES (900104, NULL, '1', NULL, 'u.meta.tester@example.com', 'USER_ID', 'META_SHIP', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-meta-seed', false);

-- PART D — UserGroupMembers (meta.tester is a member of meta_group_1)
INSERT INTO abac_onetrust.onetrust_sim.UserGroupMembers (memberId, groupId, eventTime, recModifiedTime, isDeleted, tenantHash)
VALUES ('u.meta.tester@example.com', 'meta_group_1', current_timestamp(), current_timestamp(), false, 'phase1-meta-seed');

-- Expect (as the SP via the suite, claim user=u.meta.tester@example.com, mode=ABAC, root=<table's type>):
--   OT-N1 (meta_promo, root=META_PROMO): count(*) WHERE id=1 -> 0 (esa soft-deleted)
--   OT-N2 (meta_store, root=META_STORE): count(*) WHERE id=1 -> 1 (group grant)
--   OT-N3 (meta_cc, root=META_CC):       count(*) WHERE id=1 -> 0 (assignment inactive)
--   OT-N4 (meta_ship, root=META_SHIP):   count(*) WHERE id=1 -> 0 (assignment soft-deleted)

-- ---- TEARDOWN ----
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_meta CASCADE;
--   DELETE FROM abac_onetrust.onetrust_sim.ABAC_Assignment WHERE staticIdentifier = 'phase1-meta-seed';
--   DELETE FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-meta-seed';
--   DELETE FROM abac_onetrust.onetrust_sim.UserGroupMembers WHERE tenantHash = 'phase1-meta-seed';
```

- [ ] **Step 2: Add `metaGroupCases()` to `OnetrustCases.java`**

```java
    /** Mirrors TPC-DS's N1-N4 -- onboarding a new table under the SAME deployed row filter.
     *  Setup: sql_onetrust/09_onboard_new_tables.sql (isolated tables, real shared metadata). */
    public static List<Case> metaGroupCases() {
        String metaSchema = "abac_onetrust.abac_meta";
        String promoClaim = Cases.claim("u.meta.tester@example.com", "100", "ABAC", "META_PROMO", "[]");
        String storeClaim = Cases.claim("u.meta.tester@example.com", "100", "ABAC", "META_STORE", "[]");
        String ccClaim = Cases.claim("u.meta.tester@example.com", "100", "ABAC", "META_CC", "[]");
        String shipClaim = Cases.claim("u.meta.tester@example.com", "100", "ABAC", "META_SHIP", "[]");

        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-N1", "META", "meta_promo: its esa row has isDeleted=true -> excluded -> 0 (negative).",
            "Mirrors TPC-DS N1. Setup: sql_onetrust/09_onboard_new_tables.sql.",
            promoClaim, "SELECT count(*) FROM " + metaSchema + ".meta_promo WHERE id = 1", Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-N2", "META", "meta_store: esa subjectType=USER_GROUP; meta.tester is a member -> group path grants -> 1 (positive).",
            "Mirrors TPC-DS N2. Proves the group-membership grant path AND that a brand-new table onboards correctly.",
            storeClaim, "SELECT count(*) FROM " + metaSchema + ".meta_store WHERE id = 1", Expect.exact(1), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-N3", "META", "meta_cc: its ABAC_Assignment has isActive=false -> the JOIN ... AND a.isActive fails -> 0 (negative).",
            "Mirrors TPC-DS N3.",
            ccClaim, "SELECT count(*) FROM " + metaSchema + ".meta_cc WHERE id = 1", Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-N4", "META", "meta_ship: its ABAC_Assignment has isDeleted=true -> the AND a.isDeleted=false fails -> 0 (negative).",
            "Mirrors TPC-DS N4.",
            shipClaim, "SELECT count(*) FROM " + metaSchema + ".meta_ship WHERE id = 1", Expect.zero(), NEEDS_CLAIM_SWAP));

        return cs;
    }
```

- [ ] **Step 3: Fold into `all()`**

```java
        cs.addAll(conflictGroupCases());
        cs.addAll(metaGroupCases());
        cs.addAll(compatibleQueryCases());
```

- [ ] **Step 4: Compile and smoke-test**

```bash
cd JDBC && mvn -q package
```

Same pattern, asserting `OnetrustCases.metaGroupCases().size() == 4`. Note: `sql_onetrust/09_onboard_new_tables.sql` must be applied live before these cases pass.

- [ ] **Step 5: Commit**

```bash
git add sql_onetrust/09_onboard_new_tables.sql JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier B META group (OT-N1..OT-N4) for the OneTrust JDBC suite"
```

---

### Task 9: Tier B — THRESH group (3 cases: `OT-TH1`..`OT-TH3`)

**Files:**
- Create: `sql_onetrust/10_threshold_filter.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Produces: `OnetrustCases.threshGroupCases()` returning `List<Case>`, folded into `all()`.

**Context:** Mirrors TPC-DS's TH1-TH3 (ported from `sql/14_threshold_filter.sql`) — a *separate* range row filter (`>=` instead of `=`) that doesn't touch the deployed `abac_row_filter_wrapper_oauth`, applied to an isolated throwaway table (`abac_onetrust.abac_thresh.thresh_inventory`, since there's no real OneTrust equivalent of TPC-DS's `inventory` fact table already in scope), but still reads from the real shared `ABAC_EntitySubjectAssignment`/`ABAC_Assignment` tables (namespaced `'phase1-thresh-seed'`), same pattern as Task 8's META group.

- [ ] **Step 1: Write `sql_onetrust/10_threshold_filter.sql`**

```sql
-- =====================================================================
-- 10_threshold_filter.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported from sql/14_threshold_filter.sql (TPC-DS), for the OneTrust suite (OT-TH1..OT-TH3).
-- A RANGE / THRESHOLD row filter -- "show every row whose quantity is >= the assigned value",
-- instead of the deployed filter's EXACT match. Isolated table (abac_onetrust.abac_thresh),
-- since there's no real OneTrust table already in this suite's scope playing the role TPC-DS's
-- `inventory` fact table does -- but reads from the REAL shared ABAC_EntitySubjectAssignment/
-- ABAC_Assignment tables (namespaced 'phase1-thresh-seed'), same pattern as
-- sql_onetrust/09_onboard_new_tables.sql.
--
-- This is a SEPARATE function; it does NOT touch abac_row_filter_wrapper_oauth, so every other
-- OneTrust case (Tier A, META) is unaffected.
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_thresh;

CREATE OR REPLACE TABLE abac_onetrust.abac_thresh.thresh_inventory (id BIGINT, quantity BIGINT);
INSERT INTO abac_onetrust.abac_thresh.thresh_inventory SELECT id, id * 25 FROM range(1, 21);
-- quantities: 25, 50, 75, ..., 500 (20 rows)

-- ---- 1. Threshold filter function (same shape as abac_row_filter, only 3b's predicate changed) ----
CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.abac_row_filter_threshold(
  entity_id   STRING,
  object_type STRING,
  org_id      STRING,
  ctx STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>
)
RETURNS BOOLEAN
RETURN (
  ctx.mode = 'DISABLE'
  OR (
    ctx.root = object_type
    AND EXISTS (
      SELECT 1
      FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment esa
      JOIN abac_onetrust.onetrust_sim.ABAC_Assignment a
        ON esa.assignmentId = a.id AND a.isActive AND a.isDeleted = false
      LEFT JOIN abac_onetrust.onetrust_sim.UserGroupMembers ugm
        ON esa.subjectType = 'USER_GROUP'
       AND esa.subjectId   = ugm.groupId
       AND ugm.memberId    = ctx.user
       AND ugm.isDeleted   = false
      WHERE esa.isDeleted = false
        AND esa.objectType = object_type
        AND try_cast(entity_id AS BIGINT) >= try_cast(esa.entityId AS BIGINT)   -- <<< threshold (was '=')
        AND ( ugm.memberId IS NOT NULL
              OR (esa.subjectType = 'USER_ID' AND esa.subjectId = ctx.user) )
    )
  )
);

CREATE OR REPLACE FUNCTION abac_onetrust.onetrust_sim.abac_row_filter_threshold_wrapper(
  entity_id STRING, object_type STRING, org_id STRING
)
RETURNS BOOLEAN
RETURN abac_onetrust.onetrust_sim.abac_row_filter_threshold(
  entity_id,
  abac_onetrust.onetrust_sim.entity_type_to_object_type(object_type),
  org_id,
  abac_onetrust.onetrust_sim.get_user_context());

ALTER TABLE abac_onetrust.abac_thresh.thresh_inventory ALTER COLUMN quantity SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE POLICY thresh_inventory_policy
ON TABLE abac_onetrust.abac_thresh.thresh_inventory
ROW FILTER abac_onetrust.onetrust_sim.abac_row_filter_threshold_wrapper
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'THRESH_INVENTORY', '100');

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_thresh                  TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_thresh.thresh_inventory       TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.onetrust_sim.abac_row_filter_threshold_wrapper TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.onetrust_sim.abac_row_filter_threshold         TO `<ONETRUST_SP>`;

-- assignment: threshold = 250 for u.thresh.tester on object type 'THRESH_INVENTORY'
DELETE FROM abac_onetrust.onetrust_sim.ABAC_Assignment WHERE staticIdentifier = 'phase1-thresh-seed';
DELETE FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-thresh-seed';

INSERT INTO abac_onetrust.onetrust_sim.ABAC_Assignment
  (id, guid, staticIdentifier, name, objectType, sourceType, isActive, createdBy, createDT, updatedBy, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
SELECT 900201, uuid(), 'phase1-thresh-seed', 'Owner', 'THRESH_INVENTORY', 'SYSTEM', true, 'seed', current_timestamp(), 'seed', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-thresh-seed', false;

INSERT INTO abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment
  (assignmentId, policyId, entityId, entityOrganizationId, subjectId, subjectType, objectType, updateDT, eventTime, recModifiedTime, tenantHash, isDeleted)
VALUES (900201, NULL, '250', NULL, 'u.thresh.tester@example.com', 'USER_ID', 'THRESH_INVENTORY', current_timestamp(), current_timestamp(), current_timestamp(), 'phase1-thresh-seed', false);

-- Expect (as u.thresh.tester, root=THRESH_INVENTORY): quantities 250..500 visible (11 of 20 rows;
-- quantity=25*id, so id=10..20). count(*) WHERE quantity < 250 -> 0. min(quantity) -> 250.

-- ---- TEARDOWN ----
--   DROP POLICY IF EXISTS thresh_inventory_policy ON TABLE abac_onetrust.abac_thresh.thresh_inventory;
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_thresh CASCADE;
--   DROP FUNCTION IF EXISTS abac_onetrust.onetrust_sim.abac_row_filter_threshold_wrapper;
--   DROP FUNCTION IF EXISTS abac_onetrust.onetrust_sim.abac_row_filter_threshold;
--   DELETE FROM abac_onetrust.onetrust_sim.ABAC_Assignment WHERE staticIdentifier = 'phase1-thresh-seed';
--   DELETE FROM abac_onetrust.onetrust_sim.ABAC_EntitySubjectAssignment WHERE tenantHash = 'phase1-thresh-seed';
```

- [ ] **Step 2: Add `threshGroupCases()` to `OnetrustCases.java`**

```java
    /** Mirrors TPC-DS's TH1-TH3 -- a SEPARATE range (>=) row filter, isolated table but real
     *  shared metadata. Setup: sql_onetrust/10_threshold_filter.sql. */
    public static List<Case> threshGroupCases() {
        String schema = "abac_onetrust.abac_thresh";
        String claim = Cases.claim("u.thresh.tester@example.com", "100", "ABAC", "THRESH_INVENTORY", "[]");

        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-TH1", "THRESH", "Range grant: tester assigned 250 -> rows with quantity >= 250 are visible -> 11 rows.",
            "Mirrors TPC-DS TH1. Setup: sql_onetrust/10_threshold_filter.sql (quantity = id*25, 20 rows).",
            claim, "SELECT count(*) FROM " + schema + ".thresh_inventory", Expect.exact(11), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-TH2", "THRESH", "The cutoff holds: among VISIBLE rows, none are below the threshold -> count where quantity < 250 is exactly 0.",
            "Mirrors TPC-DS TH2. Data-independent: the row filter is ANDed with the query, so "
                + "'quantity >= 250 AND quantity < 250' is impossible for every row.",
            claim, "SELECT count(*) FROM " + schema + ".thresh_inventory WHERE quantity < 250", Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-TH3", "THRESH", "The floor holds: the minimum visible quantity is >= 250 (asserted; expected: exactly 250).",
            "Mirrors TPC-DS TH3. Confirms the boundary the '>=' predicate enforces.",
            claim, "SELECT min(quantity) FROM " + schema + ".thresh_inventory", Expect.atLeast(250), NEEDS_CLAIM_SWAP));

        return cs;
    }
```

- [ ] **Step 3: Fold into `all()`**

```java
        cs.addAll(metaGroupCases());
        cs.addAll(threshGroupCases());
        cs.addAll(compatibleQueryCases());
```

- [ ] **Step 4: Compile and smoke-test**

```bash
cd JDBC && mvn -q package
```

Same pattern, asserting `OnetrustCases.threshGroupCases().size() == 3`. Note: `sql_onetrust/10_threshold_filter.sql` must be applied live before these cases pass.

- [ ] **Step 5: Commit**

```bash
git add sql_onetrust/10_threshold_filter.sql JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier B THRESH group (OT-TH1..OT-TH3) for the OneTrust JDBC suite"
```

---

### Task 10: Tier B — RLS + DR2 setup group (1 case: `OT-DR1`; DR2 setup feeds Task 18's scenario)

**Files:**
- Create: `sql_onetrust/11_direct_rls_and_dr2.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Produces: `OnetrustCases.rlsGroupCases()` returning `List<Case>` (just `OT-DR1`), folded into `all()`. The SQL file also sets up `dr2_demo`/`dr2_row_filter`/`dr2_wrapper` that Task 18's `OnetrustDr2HotSwap` scenario consumes directly (not wrapped in a `Case` — matches how TPC-DS's DR2 is a `Scenario`, not a `Case`, since it hot-swaps mid-run).

**Context:** Mirrors TPC-DS's DR1 (a `Case`, ported from `sql/15_direct_rls.sql`) + DR2's setup (consumed by a `Scenario`, not a `Case`, in Task 18) — two contrasting attachment mechanisms: DR1 is **classic** RLS (`ALTER TABLE ... SET ROW FILTER`, no tags, no policy); DR2 is the **ABAC** `has_tag()` policy flow with a hot-swappable inner UDF. Both isolated tables, mirroring `reason` (DR1) and `income_band` (DR2, 20 fixed rows).

- [ ] **Step 1: Write `sql_onetrust/11_direct_rls_and_dr2.sql`**

```sql
-- =====================================================================
-- 11_direct_rls_and_dr2.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported from sql/15_direct_rls.sql (TPC-DS), for the OneTrust suite (OT-DR1 + the
-- OnetrustDr2HotSwap scenario, Task 18). Two contrasting ways to attach a row filter, on
-- isolated tables (abac_onetrust.abac_rls):
--   DR1 = CLASSIC RLS on rls_demo          -> ALTER TABLE ... SET ROW FILTER; NO tags, NO policy.
--   DR2 = ABAC tag+policy on dr2_demo      -> has_tag() MATCH COLUMNS policy + wrapper + inner
--         row-filter UDF that OnetrustDr2HotSwap hot-swaps mid-run: assert -> CREATE OR REPLACE
--         the UDF -> poll until reflected -> re-assert -> revert.
--
-- Both are tiny fixed dimension tables with clean 1..20 surrogate keys, neither governed by
-- anything else. dr2_row_filter is OWNED BY THE SP so the suite can CREATE OR REPLACE it during
-- the run; the POLICY binds the STABLE wrapper, so the swap never touches an in-use binding.
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_rls;

-- =========================================================
-- DR1 -- CLASSIC Row-Level Security on rls_demo (NO governance tags, NO CREATE POLICY)
-- =========================================================
CREATE OR REPLACE TABLE abac_onetrust.abac_rls.rls_demo (id BIGINT);
INSERT INTO abac_onetrust.abac_rls.rls_demo SELECT id FROM range(1, 21);

CREATE OR REPLACE FUNCTION abac_onetrust.abac_rls.rls_demo_filter(k BIGINT)
  RETURNS BOOLEAN RETURN k >= 10;                        -- keep only id >= 10 (11 of 20 rows)
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_rls.rls_demo_filter TO `<ONETRUST_SP>`;
GRANT SELECT   ON TABLE    abac_onetrust.abac_rls.rls_demo       TO `<ONETRUST_SP>`;
GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_rls                TO `<ONETRUST_SP>`;
-- classic UC row filter: bound DIRECTLY to the column -- no has_tag, no CREATE POLICY, no wrapper.
ALTER TABLE abac_onetrust.abac_rls.rls_demo
  SET ROW FILTER abac_onetrust.abac_rls.rls_demo_filter ON (id);

-- =========================================================
-- DR2 -- ABAC tag + policy on dr2_demo (the has_tag() flow; hot-swappable inner UDF)
-- =========================================================
CREATE OR REPLACE TABLE abac_onetrust.abac_rls.dr2_demo (id BIGINT);
INSERT INTO abac_onetrust.abac_rls.dr2_demo SELECT id FROM range(1, 21);

-- inner row-filter UDF (SWAPPABLE, owned by the SP). Original cutoff: id <= 10.
CREATE OR REPLACE FUNCTION abac_onetrust.abac_rls.dr2_row_filter(
  entity_id STRING, object_type STRING, org_id STRING,
  ctx STRUCT<tenant:INT, user:STRING, org:STRING, mode:STRING, root:STRING, permissions:ARRAY<STRING>>)
RETURNS BOOLEAN
RETURN try_cast(entity_id AS BIGINT) <= 10;              -- << OnetrustDr2HotSwap CREATE OR REPLACEs this cutoff
ALTER FUNCTION abac_onetrust.abac_rls.dr2_row_filter OWNER TO `<ONETRUST_SP>`;

-- stable wrapper the POLICY binds (same shape as the deployed abac_row_filter_wrapper_oauth)
CREATE OR REPLACE FUNCTION abac_onetrust.abac_rls.dr2_wrapper(
  entity_id STRING, object_type STRING, org_id STRING)
RETURNS BOOLEAN
RETURN abac_onetrust.abac_rls.dr2_row_filter(
  entity_id,
  abac_onetrust.onetrust_sim.entity_type_to_object_type(object_type),
  org_id,
  abac_onetrust.onetrust_sim.get_user_context());

GRANT EXECUTE ON FUNCTION abac_onetrust.abac_rls.dr2_wrapper     TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_rls.dr2_row_filter  TO `<ONETRUST_SP>`;
GRANT SELECT   ON TABLE    abac_onetrust.abac_rls.dr2_demo       TO `<ONETRUST_SP>`;

ALTER TABLE abac_onetrust.abac_rls.dr2_demo ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
CREATE OR REPLACE POLICY dr2_demo_policy
ON TABLE abac_onetrust.abac_rls.dr2_demo
ROW FILTER abac_onetrust.abac_rls.dr2_wrapper
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id, 'DR2_DEMO', '100');

-- If the SP cannot CREATE OR REPLACE dr2_row_filter even as its owner, also run (as owner):
--   GRANT CREATE FUNCTION ON SCHEMA abac_onetrust.abac_rls TO `<ONETRUST_SP>`;

-- Expect (as the SP via the suite):
--   OT-DR1 : SELECT count(*) FROM rls_demo WHERE id < 10  ->  0   (classic RLS, no tags)
--   DR2a (OnetrustDr2HotSwap start) : SELECT count(*) FROM dr2_demo             -> 10 (cutoff <= 10)
--   DR2b (after CREATE OR REPLACE dr2_row_filter <= 5 + poll) : SELECT count(*) -> 5
--   DR2c (after revert to <= 10)                               : SELECT count(*) -> 10

-- ---- TEARDOWN ----
--   ALTER TABLE abac_onetrust.abac_rls.rls_demo DROP ROW FILTER;
--   DROP POLICY IF EXISTS dr2_demo_policy ON TABLE abac_onetrust.abac_rls.dr2_demo;
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_rls CASCADE;
```

- [ ] **Step 2: Add `rlsGroupCases()` to `OnetrustCases.java`**

```java
    /** Mirrors TPC-DS's DR1 -- classic RLS, no tags, no policy. Setup:
     *  sql_onetrust/11_direct_rls_and_dr2.sql (which also sets up DR2 for OnetrustDr2HotSwap, Task 18). */
    public static List<Case> rlsGroupCases() {
        List<Case> cs = new ArrayList<>();
        cs.add(new Case("OT-DR1", "RLS",
            "Direct classic RLS (NO tags, NO policy): rls_demo has SET ROW FILTER keeping id >= 10 -> count where < 10 is 0.",
            "Mirrors TPC-DS DR1. Setup: sql_onetrust/11_direct_rls_and_dr2.sql. Data-independent proof "
                + "that classic (table-managed) RLS filters WITHOUT any ABAC tag/policy machinery -- "
                + "contrast OnetrustDr2HotSwap (Task 18), which does the same via a has_tag() policy.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM abac_onetrust.abac_rls.rls_demo WHERE id < 10",
            Expect.zero(), NEEDS_CLAIM_SWAP));
        return cs;
    }
```

- [ ] **Step 3: Fold into `all()`**

```java
        cs.addAll(threshGroupCases());
        cs.addAll(rlsGroupCases());
        cs.addAll(compatibleQueryCases());
```

- [ ] **Step 4: Compile and smoke-test**

```bash
cd JDBC && mvn -q package
```

Same pattern, asserting `OnetrustCases.rlsGroupCases().size() == 1`. Note: `sql_onetrust/11_direct_rls_and_dr2.sql` must be applied live before this case (and Task 18's scenario) can be live-verified.

- [ ] **Step 5: Commit**

```bash
git add sql_onetrust/11_direct_rls_and_dr2.sql JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier B RLS group (OT-DR1) + DR2 setup for the OneTrust JDBC suite"
```

---

### Task 11: Tier B — VIEWS group (3 cases: `OT-V1`..`OT-V3`)

**Files:**
- Create: `sql_onetrust/12_views.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Consumes: `abac_onetrust.abac_rls.rls_demo`/`dr2_demo` (Task 10).
- Produces: `OnetrustCases.viewGroupCases()` returning `List<Case>`, folded into `all()`.

**Context:** Mirrors TPC-DS's V1-V3 (ported from `sql/16_views.sql`) — does a row filter propagate through a view, or is a view a bypass? V1/V3 = a view over `rls_demo` (classic RLS, Task 10). V2 = a view over `dr2_demo` (ABAC policy, Task 10). **Prerequisite: Task 10's SQL must already be applied** — without it, these views would just measure the two unfiltered base tables.

- [ ] **Step 1: Write `sql_onetrust/12_views.sql`**

```sql
-- =====================================================================
-- 12_views.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported from sql/16_views.sql (TPC-DS), for the OneTrust suite (OT-V1..OT-V3).
-- Does a row filter propagate through a VIEW, or is a view a bypass?
--   V1/V3 = a view over rls_demo, which already carries CLASSIC RLS (id >= 10) from
--           sql_onetrust/11_direct_rls_and_dr2.sql. No new policy needed here -- only a new view.
--   V2    = a view over dr2_demo, which already carries the ABAC has_tag() policy (cutoff id <= 10,
--           of 20 fixed rows) from sql_onetrust/11_direct_rls_and_dr2.sql.
--
-- PREREQUISITE: sql_onetrust/11_direct_rls_and_dr2.sql must already be applied.
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

-- V1/V3: a view over a GOVERNED base table (classic RLS on rls_demo).
CREATE OR REPLACE VIEW abac_onetrust.abac_rls.v_rls_demo_governed AS
SELECT id FROM abac_onetrust.abac_rls.rls_demo;

-- V2: a view over dr2_demo, governed by the ABAC has_tag() policy (cutoff id <= 10).
CREATE OR REPLACE VIEW abac_onetrust.abac_rls.v_dr2_demo_governed AS
SELECT id FROM abac_onetrust.abac_rls.dr2_demo;

GRANT SELECT ON VIEW abac_onetrust.abac_rls.v_rls_demo_governed  TO `<ONETRUST_SP>`;
GRANT SELECT ON VIEW abac_onetrust.abac_rls.v_dr2_demo_governed  TO `<ONETRUST_SP>`;

-- Expect (as the SP via the suite):
--   OT-V1: SELECT count(*) FROM v_rls_demo_governed WHERE id < 10  ->  0   (classic RLS keeps only
--          id >= 10; the view must not bypass it)
--   OT-V2: SELECT count(*) FROM v_dr2_demo_governed                ->  10  (ABAC policy keeps only
--          id <= 10, of 20 fixed rows; the view must not bypass it)
--   OT-V3: SELECT min(id) FROM v_rls_demo_governed                 ->  >= 10 (an aggregate through
--          the view must not reveal the existence of filtered-out rows)

-- ---- TEARDOWN ----
--   DROP VIEW IF EXISTS abac_onetrust.abac_rls.v_rls_demo_governed;
--   DROP VIEW IF EXISTS abac_onetrust.abac_rls.v_dr2_demo_governed;
```

- [ ] **Step 2: Add `viewGroupCases()` to `OnetrustCases.java`**

```java
    /** Mirrors TPC-DS's V1-V3 -- row filters (classic and ABAC) propagate through views, including
     *  aggregates. Setup: sql_onetrust/12_views.sql (requires Task 10's SQL applied first). */
    public static List<Case> viewGroupCases() {
        String schema = "abac_onetrust.abac_rls";
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-V1", "V", "View over a governed base table (classic RLS) inherits the base row filter.",
            "Mirrors TPC-DS V1. Setup: sql_onetrust/12_views.sql.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".v_rls_demo_governed WHERE id < 10",
            Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-V2", "V", "View over a table governed by an ABAC policy still filters.",
            "Mirrors TPC-DS V2.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".v_dr2_demo_governed",
            Expect.exact(10), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-V3", "V", "Aggregate through a view cannot leak filtered rows.",
            "Mirrors TPC-DS V3. min(id) through the view must be >= 10 -- an aggregate must not "
                + "reveal filtered-out rows exist.",
            Cases.DISABLE_CLAIM, "SELECT min(id) FROM " + schema + ".v_rls_demo_governed",
            Expect.atLeast(10), NEEDS_CLAIM_SWAP));

        return cs;
    }
```

- [ ] **Step 3: Fold into `all()`**

```java
        cs.addAll(rlsGroupCases());
        cs.addAll(viewGroupCases());
        cs.addAll(compatibleQueryCases());
```

- [ ] **Step 4: Compile and smoke-test**

```bash
cd JDBC && mvn -q package
```

Same pattern, asserting `OnetrustCases.viewGroupCases().size() == 3`. Note: `sql_onetrust/12_views.sql` (and its prerequisite, `11_direct_rls_and_dr2.sql`) must be applied live before these cases pass.

- [ ] **Step 5: Commit**

```bash
git add sql_onetrust/12_views.sql JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier B VIEWS group (OT-V1..OT-V3) for the OneTrust JDBC suite"
```

---

### Task 12: Tier B — SC (policy scope) group (4 cases: `OT-SC1`..`OT-SC4`)

**Files:**
- Create: `sql_onetrust/13_policy_scope.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Produces: `OnetrustCases.scGroupCases()` returning `List<Case>`, folded into `all()`.

**Context:** A clean, fully self-contained 1:1 port of TPC-DS's SC1-SC4 (`sql/17_policy_scope.sql`) — this group already used an isolated schema in TPC-DS (`abac_tpcds.abac_scope`), so this task is a pure catalog-prefix + SP substitution, no adaptation needed. `ON SCHEMA` policy scope: governs every matching member, not just the first; untagged tables are ungoverned (fails open, silently); schema-level + table-level filters conflict (no precedence order).

- [ ] **Step 1: Write `sql_onetrust/13_policy_scope.sql`**

```sql
-- =====================================================================
-- 13_policy_scope.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported 1:1 from sql/17_policy_scope.sql (TPC-DS), for the OneTrust suite (OT-SC1..OT-SC4).
-- Policy SCOPE: does an ON SCHEMA policy govern every table beneath it, what happens to a table
-- with no matching tag, and what happens when a schema-level and a table-level row filter both
-- target the SAME table.
--   SC1 = scoped_a:    a table governed by a SCHEMA-level policy (ON SCHEMA, not ON TABLE).
--   SC2 = scoped_c:    a THIRD table in the same schema, same governance tag, covered by NOTHING
--                      but the schema-level policy -> proves schema scope governs every matching
--                      member, not just the first.
--   SC3 = ungoverned:  sits inside the policy's ON SCHEMA scope but has NO abac_column_id tag ->
--                      MATCH COLUMNS matches nothing -> policy silently does not apply -> ALL rows
--                      visible. The dangerous case: a BROKEN policy fails CLOSED; a NON-MATCHING
--                      one fails OPEN, with no error at all.
--   SC4 = scoped_b:    carries BOTH the schema-level policy AND a second, TABLE-level policy --
--                      NOT a precedence contest. Both CREATE POLICY succeed; the conflict is only
--                      detected when the table is QUERIED (UC_ABAC_MULTIPLE_ROW_FILTERS, 42KDJ).
--
-- Uses only the already-registered governed tag keys abac_column_id (see sql_onetrust/02_tags.sql).
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_scope;

CREATE OR REPLACE TABLE abac_onetrust.abac_scope.scoped_a (id BIGINT, label STRING);
INSERT INTO abac_onetrust.abac_scope.scoped_a SELECT id, concat('row-', id) FROM range(1, 21);

CREATE OR REPLACE TABLE abac_onetrust.abac_scope.scoped_b (id BIGINT, label STRING);
INSERT INTO abac_onetrust.abac_scope.scoped_b SELECT id, concat('row-', id) FROM range(1, 21);

CREATE OR REPLACE TABLE abac_onetrust.abac_scope.scoped_c (id BIGINT, label STRING);
INSERT INTO abac_onetrust.abac_scope.scoped_c SELECT id, concat('row-', id) FROM range(1, 21);

CREATE OR REPLACE TABLE abac_onetrust.abac_scope.ungoverned (id BIGINT);
INSERT INTO abac_onetrust.abac_scope.ungoverned SELECT id FROM range(1, 21);

ALTER TABLE abac_onetrust.abac_scope.scoped_a ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.abac_scope.scoped_b ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.abac_scope.scoped_c ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE FUNCTION abac_onetrust.abac_scope.scope_filter(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;

CREATE OR REPLACE POLICY scope_schema_policy
ON SCHEMA abac_onetrust.abac_scope
ROW FILTER abac_onetrust.abac_scope.scope_filter
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_scope TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_scope.scoped_a   TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_scope.scoped_b   TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_scope.scoped_c   TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_scope.ungoverned TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_scope.scope_filter TO `<ONETRUST_SP>`;

-- SC4: add a TABLE-level policy on top of the schema-level one, on scoped_b ONLY.
CREATE OR REPLACE FUNCTION abac_onetrust.abac_scope.scope_filter_tbl(id BIGINT)
RETURNS BOOLEAN RETURN id <= 5;

CREATE OR REPLACE POLICY scope_table_policy
ON TABLE abac_onetrust.abac_scope.scoped_b
ROW FILTER abac_onetrust.abac_scope.scope_filter_tbl
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

GRANT EXECUTE ON FUNCTION abac_onetrust.abac_scope.scope_filter_tbl TO `<ONETRUST_SP>`;

-- Expect (as the SP via the suite):
--   OT-SC1: SELECT count(*) FROM scoped_a               ->  10  (schema policy, id <= 10 of 20)
--   OT-SC2: SELECT count(*) FROM scoped_c WHERE id > 10  ->  0   (schema policy also governs this
--           THIRD, otherwise-unrelated table)
--   OT-SC3: SELECT count(*) FROM ungoverned              ->  20  (no tag -> fails OPEN, ALL rows)
--   OT-SC4: SELECT count(*) FROM scoped_b                ->  ERROR UC_ABAC_MULTIPLE_ROW_FILTERS

-- ---- TEARDOWN ----
--   DROP POLICY IF EXISTS scope_table_policy ON TABLE abac_onetrust.abac_scope.scoped_b;
--   DROP POLICY IF EXISTS scope_schema_policy ON SCHEMA abac_onetrust.abac_scope;
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_scope CASCADE;
```

- [ ] **Step 2: Add `scGroupCases()` to `OnetrustCases.java`**

```java
    /** Mirrors TPC-DS's SC1-SC4 -- ON SCHEMA policy scope. Setup: sql_onetrust/13_policy_scope.sql. */
    public static List<Case> scGroupCases() {
        String schema = "abac_onetrust.abac_scope";
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-SC1", "SC", "ON SCHEMA policy governs a table in that schema",
            "Mirrors TPC-DS SC1. Setup: sql_onetrust/13_policy_scope.sql.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".scoped_a", Expect.exact(10), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-SC2", "SC", "ON SCHEMA policy covers EVERY matching member, not just the first",
            "Mirrors TPC-DS SC2. scoped_c is a THIRD table in the same schema, covered by nothing but "
                + "the schema-level policy.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".scoped_c WHERE id > 10", Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-SC3", "SC", "A table with no matching tag is NOT governed -- returns ALL rows",
            "Mirrors TPC-DS SC3. ungoverned sits inside the policy's ON SCHEMA scope but has no "
                + "abac_column_id tag -- the dangerous fail-open case.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".ungoverned", Expect.exact(20), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-SC4", "SC", "Schema-level + table-level row filters CONFLICT -- they do not have a precedence order",
            "Mirrors TPC-DS SC4. scoped_b is covered by both the schema-level and a table-level policy.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".scoped_b",
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"), NEEDS_CLAIM_SWAP));

        return cs;
    }
```

- [ ] **Step 3: Fold into `all()`**

```java
        cs.addAll(viewGroupCases());
        cs.addAll(scGroupCases());
        cs.addAll(compatibleQueryCases());
```

- [ ] **Step 4: Compile and smoke-test**

```bash
cd JDBC && mvn -q package
```

Same pattern, asserting `OnetrustCases.scGroupCases().size() == 4`. Note: `sql_onetrust/13_policy_scope.sql` must be applied live before these cases pass.

- [ ] **Step 5: Commit**

```bash
git add sql_onetrust/13_policy_scope.sql JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier B SC group (OT-SC1..OT-SC4) for the OneTrust JDBC suite"
```

---

### Task 13: Tier B — TG (tag binding) group (3 cases: `OT-TG1`..`OT-TG3`)

**Files:**
- Create: `sql_onetrust/14_tag_binding.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Produces: `OnetrustCases.tgGroupCases()` returning `List<Case>`, folded into `all()`.

**Context:** A 1:1 port of TPC-DS's TG1-TG3 (`sql/18_tag_binding.sql`) — `has_tag_value()` binds only the column whose tag *value* matches, two columns sharing one tag makes the binding ambiguous (Databricks refuses to pick one), a `MATCH COLUMNS` matching no column fails **open** silently. **Important prerequisite, not a new registration step:** governed tag keys are workspace/metastore-level, shared across every catalog including `abac_onetrust` — `abac_column_id`'s allowed values already include `'filter'`/`'ignore'` if TPC-DS's own TG1 setup (`sql/18`) was ever applied in this workspace (confirmed live 2026-07-22 per that file's header). If it wasn't, `ALTER ... SET TAGS ('abac_column_id' = 'filter')` below will fail with `Tag value filter is not an allowed value` — the operator adds `'filter'`/`'ignore'` to `abac_column_id`'s allowed values (Settings → Catalog → Governed tags) before applying this file, exactly as TPC-DS's own setup required.

- [ ] **Step 1: Write `sql_onetrust/14_tag_binding.sql`**

```sql
-- =====================================================================
-- 14_tag_binding.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported 1:1 from sql/18_tag_binding.sql (TPC-DS), for the OneTrust suite (OT-TG1..OT-TG3).
-- MATCH COLUMNS tag BINDING: does has_tag_value() bind only the column whose tag VALUE matches,
-- what happens when TWO columns carry the SAME tag, and what happens when a MATCH COLUMNS
-- expression matches NO column at all.
--   TG1 = tagval:  id carries abac_column_id='filter'; other carries abac_column_id='ignore'.
--                  has_tag_value('abac_column_id','filter') must bind ONLY id.
--   TG2 = dualtag: TWO columns (a, b) carry the IDENTICAL tag -- genuinely ambiguous. Ships as
--                  INFO until observed (see TPC-DS's own TG2 finding: Databricks refuses to bind,
--                  it does not pick the first column -- UC_ABAC_AMBIGUOUS_COLUMN_MATCH).
--   TG3 = notag:   a REGISTERED tag key (abac_column_org) whose MATCH COLUMNS matches NO column on
--                  this table -- CREATE POLICY succeeds, but the policy silently never applies.
--
-- PREREQUISITE: abac_column_id's allowed values must already include 'filter'/'ignore' (governed
-- tag keys are workspace-level, shared across catalogs -- already true if TPC-DS's own sql/18 was
-- applied; if not, add them first via Settings > Catalog > Governed tags).
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_tags;

-- TG1: has_tag_value() -- match on a tag's VALUE, not just its presence.
CREATE OR REPLACE TABLE abac_onetrust.abac_tags.tagval (id BIGINT, other BIGINT);
INSERT INTO abac_onetrust.abac_tags.tagval SELECT id, id * 10 FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_tags.tagval ALTER COLUMN id    SET TAGS ('abac_column_id' = 'filter');
ALTER TABLE abac_onetrust.abac_tags.tagval ALTER COLUMN other SET TAGS ('abac_column_id' = 'ignore');

CREATE OR REPLACE FUNCTION abac_onetrust.abac_tags.tag_filter(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;

CREATE OR REPLACE POLICY tagval_policy
ON TABLE abac_onetrust.abac_tags.tagval
ROW FILTER abac_onetrust.abac_tags.tag_filter
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag_value('abac_column_id', 'filter') AS id
USING COLUMNS (id);

-- TG2: TWO columns carrying the SAME tag -- what does the alias bind to?
CREATE OR REPLACE TABLE abac_onetrust.abac_tags.dualtag (a BIGINT, b BIGINT);
INSERT INTO abac_onetrust.abac_tags.dualtag SELECT id, 21 - id FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_tags.dualtag ALTER COLUMN a SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.abac_tags.dualtag ALTER COLUMN b SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE POLICY dualtag_policy
ON TABLE abac_onetrust.abac_tags.dualtag
ROW FILTER abac_onetrust.abac_tags.tag_filter
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS c
USING COLUMNS (c);

-- TG3: a MATCH COLUMNS expression on a REGISTERED tag key that matches NO column on this table.
CREATE OR REPLACE TABLE abac_onetrust.abac_tags.notag (id BIGINT);
INSERT INTO abac_onetrust.abac_tags.notag SELECT id FROM range(1, 21);

CREATE OR REPLACE POLICY notag_policy
ON TABLE abac_onetrust.abac_tags.notag
ROW FILTER abac_onetrust.abac_tags.tag_filter
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_org') AS id
USING COLUMNS (id);

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_tags TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_tags.tagval  TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_tags.dualtag TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_tags.notag   TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_tags.tag_filter TO `<ONETRUST_SP>`;

-- Expect (as the SP via the suite):
--   OT-TG1: SELECT count(*) FROM tagval             ->  10  (has_tag_value binds id; a wrong
--           binding to `other` would give exactly 1 row instead)
--   OT-TG2: SELECT a FROM dualtag ORDER BY a         ->  INFO (record the observed ids/error;
--           TPC-DS's own TG2 observed Databricks REFUSES to bind -- UC_ABAC_AMBIGUOUS_COLUMN_MATCH
--           -- convert to Expect.errorContains(...) if OneTrust confirms the same)
--   OT-TG3: SELECT count(*) FROM notag               ->  20  (no matching column -> fails OPEN)

-- ---- TEARDOWN ----
--   DROP POLICY IF EXISTS tagval_policy  ON TABLE abac_onetrust.abac_tags.tagval;
--   DROP POLICY IF EXISTS dualtag_policy ON TABLE abac_onetrust.abac_tags.dualtag;
--   DROP POLICY IF EXISTS notag_policy   ON TABLE abac_onetrust.abac_tags.notag;
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_tags CASCADE;
```

- [ ] **Step 2: Add `tgGroupCases()` to `OnetrustCases.java`**

```java
    /** Mirrors TPC-DS's TG1-TG3 -- tag-binding edge cases. Setup: sql_onetrust/14_tag_binding.sql.
     *  OT-TG2 ships as INFO, same as TPC-DS's TG2 originally did, pending an OneTrust-side live
     *  observation of which column (if either) Databricks actually binds. */
    public static List<Case> tgGroupCases() {
        String schema = "abac_onetrust.abac_tags";
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-TG1", "TG", "has_tag_value() binds only the column whose tag VALUE matches",
            "Mirrors TPC-DS TG1. Setup: sql_onetrust/14_tag_binding.sql.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".tagval", Expect.exact(10), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-TG2", "TG", "Two columns sharing one tag -- record which column (if either) Databricks binds",
            "Mirrors TPC-DS TG2. INFO until observed live on abac_onetrust -- TPC-DS's own TG2 found "
                + "Databricks REFUSES to bind (UC_ABAC_AMBIGUOUS_COLUMN_MATCH), not that it silently "
                + "picks the first column; confirm the same holds here before converting to a hard assertion.",
            Cases.DISABLE_CLAIM, "SELECT a FROM " + schema + ".dualtag ORDER BY a", Expect.info(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-TG3", "TG", "A MATCH COLUMNS that matches nothing makes the policy SILENTLY not apply",
            "Mirrors TPC-DS TG3. abac_column_org (registered) matches no column on notag -- fails OPEN.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".notag", Expect.exact(20), NEEDS_CLAIM_SWAP));

        return cs;
    }
```

- [ ] **Step 3: Fold into `all()`**

```java
        cs.addAll(scGroupCases());
        cs.addAll(tgGroupCases());
        cs.addAll(compatibleQueryCases());
```

- [ ] **Step 4: Compile and smoke-test**

```bash
cd JDBC && mvn -q package
```

Same pattern, asserting `OnetrustCases.tgGroupCases().size() == 3`. Note: `sql_onetrust/14_tag_binding.sql` must be applied live before these cases pass (and its allowed-value prerequisite checked first).

- [ ] **Step 5: Commit**

```bash
git add sql_onetrust/14_tag_binding.sql JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier B TG group (OT-TG1..OT-TG3) for the OneTrust JDBC suite"
```

---

### Task 14: Tier B — UC (UDF contract) group (1 case: `OT-UC2`)

**Files:**
- Create: `sql_onetrust/15_udf_contract.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Produces: `OnetrustCases.ucGroupCases()` returning `List<Case>`, folded into `all()`.

**Context:** A 1:1 port of TPC-DS's UC2 (`sql/19_udf_contract.sql`) — a declared `DATE` UDF param bound (via `USING COLUMNS`) to a `TIMESTAMP` column: Databricks coerces, it does not reject. **UC1 (arity mismatch) is intentionally NOT ported as a live case**, exactly matching TPC-DS: it's a `CREATE POLICY`-time (DDL) rejection the service principal can never observe via its query path (only an owner issues `CREATE POLICY`), so it stays a commented-out reproducible demo in the SQL file, never a `Case`. (This was independently reconfirmed by TPC-DS's own `sql/21` `DP1` finding — see Task 16.)

- [ ] **Step 1: Write `sql_onetrust/15_udf_contract.sql`**

```sql
-- =====================================================================
-- 15_udf_contract.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported 1:1 from sql/19_udf_contract.sql (TPC-DS), for the OneTrust suite (OT-UC2 only).
-- The UDF CONTRACT between a row-filter function's declared signature and USING COLUMNS.
--   UC1 = arity: NOT a suite case (see class doc above) -- kept commented out below, exactly as
--                in TPC-DS. A row filter auto-supplies NO argument of its own, so ALL declared
--                params must appear in USING COLUMNS; this is a DDL-time rejection, never
--                observable from the SP's query path.
--   UC2 = type:  date_param(d DATE) is bound, via USING COLUMNS, to `ts` -- a TIMESTAMP column.
--                Databricks COERCES TIMESTAMP -> DATE at bind time (confirmed live for TPC-DS
--                2026-07-22); the filter applies, keeping ts < 2020-01-11 (9 of 20 rows).
--
-- *** THE UC1 CREATE POLICY BLOCK BELOW IS COMMENTED OUT AND MUST STAY THAT WAY BY DEFAULT. ***
-- Uncomment ONLY to reproduce the arity-mismatch error in isolation; re-comment immediately after.
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_udf;

CREATE OR REPLACE TABLE abac_onetrust.abac_udf.arity (id BIGINT, ts TIMESTAMP);
INSERT INTO abac_onetrust.abac_udf.arity
  SELECT id, timestamp(date_add(DATE'2020-01-01', CAST(id AS INT))) FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_udf.arity ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
ALTER TABLE abac_onetrust.abac_udf.arity ALTER COLUMN ts SET TAGS ('abac_column_org' = 'true');

-- UC1: the UDF declares TWO params; a policy supplying only ONE is REJECTED at CREATE POLICY time.
CREATE OR REPLACE FUNCTION abac_onetrust.abac_udf.two_param(id BIGINT, extra STRING)
RETURNS BOOLEAN RETURN id <= 10;

-- =====================================================================================
-- UC1 -- DO NOT UNCOMMENT AS PART OF A NORMAL APPLY OF THIS SCRIPT. EXPECTED TO FAIL
-- (arity mismatch). See the class doc above.
--
-- CREATE OR REPLACE POLICY arity_policy
-- ON TABLE abac_onetrust.abac_udf.arity
-- ROW FILTER abac_onetrust.abac_udf.two_param
-- TO `<ONETRUST_SP>`
-- FOR TABLES
-- MATCH COLUMNS has_tag('abac_column_id') AS id
-- USING COLUMNS (id);
-- =====================================================================================

-- UC2: declared param type DATE, bound column type TIMESTAMP -- coerced, not rejected.
CREATE OR REPLACE FUNCTION abac_onetrust.abac_udf.date_param(d DATE)
RETURNS BOOLEAN RETURN d < DATE'2020-01-11';

CREATE OR REPLACE POLICY type_policy
ON TABLE abac_onetrust.abac_udf.arity
ROW FILTER abac_onetrust.abac_udf.date_param
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_org') AS ts
USING COLUMNS (ts);

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_udf TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_udf.arity TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_udf.date_param TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_udf.two_param  TO `<ONETRUST_SP>`;

-- Expect (as the SP via the suite):
--   OT-UC2: SELECT count(*) FROM arity -> 9 (ts runs 2020-01-02..2020-01-21; TIMESTAMP->DATE
--           coercion keeps d < 2020-01-11, i.e. 2020-01-02..2020-01-10)

-- ---- TEARDOWN ----
--   DROP POLICY IF EXISTS type_policy  ON TABLE abac_onetrust.abac_udf.arity;
--   DROP FUNCTION IF EXISTS abac_onetrust.abac_udf.date_param;
--   DROP FUNCTION IF EXISTS abac_onetrust.abac_udf.two_param;
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_udf CASCADE;
```

- [ ] **Step 2: Add `ucGroupCases()` to `OnetrustCases.java`**

```java
    /** Mirrors TPC-DS's UC2 -- a declared-type UDF param bound to a differently-typed column is
     *  coerced, not rejected. Setup: sql_onetrust/15_udf_contract.sql. UC1 has no case -- see the
     *  class doc there and Task 16's DP1 note. */
    public static List<Case> ucGroupCases() {
        List<Case> cs = new ArrayList<>();
        cs.add(new Case("OT-UC2", "UC", "Declared DATE param vs bound TIMESTAMP column -- Databricks COERCES, it does not reject",
            "Mirrors TPC-DS UC2. Setup: sql_onetrust/15_udf_contract.sql.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM abac_onetrust.abac_udf.arity",
            Expect.exact(9), NEEDS_CLAIM_SWAP));
        return cs;
    }
```

- [ ] **Step 3: Fold into `all()`**

```java
        cs.addAll(tgGroupCases());
        cs.addAll(ucGroupCases());
        cs.addAll(compatibleQueryCases());
```

- [ ] **Step 4: Compile and smoke-test**

```bash
cd JDBC && mvn -q package
```

Same pattern, asserting `OnetrustCases.ucGroupCases().size() == 1`. Note: `sql_onetrust/15_udf_contract.sql` must be applied live before this case passes.

- [ ] **Step 5: Commit**

```bash
git add sql_onetrust/15_udf_contract.sql JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier B UC group (OT-UC2) for the OneTrust JDBC suite"
```

---

### Task 15: Tier B — XT (cross-mechanism) group (1 case: `OT-XT1`)

**Files:**
- Create: `sql_onetrust/16_cross_mechanism.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Produces: `OnetrustCases.xtGroupCases()` returning `List<Case>`, folded into `all()`.

**Context:** A 1:1 port of TPC-DS's XT1 (`sql/20_cross_mechanism.sql`) — does the one-row-filter-per-table limit span *both* attachment mechanisms (a tag-driven ABAC `CREATE POLICY` AND a classic `ALTER TABLE ... SET ROW FILTER` on the same table), or is classic RLS tracked separately from ABAC policies? Two deliberately disjoint predicates (`id <= 10` vs `id > 15`) turn every possible outcome into a diagnostic signal — see the decode table in the SQL comments.

- [ ] **Step 1: Write `sql_onetrust/16_cross_mechanism.sql`**

```sql
-- =====================================================================
-- 16_cross_mechanism.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported 1:1 from sql/20_cross_mechanism.sql (TPC-DS), for the OneTrust suite (OT-XT1).
-- CROSS-MECHANISM conflict: a tag-driven ABAC CREATE POLICY row filter AND a classic
-- ALTER TABLE ... SET ROW FILTER row filter, attached to the SAME table.
--
-- DESIGN: deliberately DISJOINT predicates --
--   abac_fn    (ABAC policy)    keeps id <= 10  -> 10 of 20 rows if it alone applies
--   classic_fn (classic filter) keeps id > 15   ->  5 of 20 rows if it alone applies
-- DECODE TABLE for `SELECT count(*) FROM abac_onetrust.abac_xmech.both`:
--   ERROR (UC_ABAC_MULTIPLE_ROW_FILTERS) => the one-filter-per-table limit spans BOTH mechanisms
--   0     => the two filters were ANDed together (id <= 10 AND id > 15 is empty)
--   10    => the ABAC policy won; classic was ignored
--   5     => the classic filter won; the ABAC policy was ignored
--   20    => NEITHER mechanism applied to this query
-- If a COUNT comes back instead of an error, that is a significant finding, not a test bug --
-- record the observed number and read it against the decode table, rather than treating it as broken.
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_xmech;

CREATE OR REPLACE TABLE abac_onetrust.abac_xmech.both (id BIGINT);
INSERT INTO abac_onetrust.abac_xmech.both SELECT id FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_xmech.both ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE FUNCTION abac_onetrust.abac_xmech.abac_fn(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;                -- ABAC policy predicate: keeps 10 of 20 rows

CREATE OR REPLACE FUNCTION abac_onetrust.abac_xmech.classic_fn(id BIGINT)
RETURNS BOOLEAN RETURN id > 15;                 -- classic RLS predicate: keeps 5 of 20 rows (disjoint)

-- Mechanism 1: the ABAC policy (tag-driven, has_tag() MATCH COLUMNS binding).
CREATE OR REPLACE POLICY xmech_policy
ON TABLE abac_onetrust.abac_xmech.both
ROW FILTER abac_onetrust.abac_xmech.abac_fn
TO `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

-- Mechanism 2: classic table-managed RLS, bound directly to the column (no tag, no policy).
-- >>> If THIS statement errors because xmech_policy already occupies the table's one row-filter
-- slot, that IS the finding this case exists to surface -- record the error verbatim.
ALTER TABLE abac_onetrust.abac_xmech.both SET ROW FILTER abac_onetrust.abac_xmech.classic_fn ON (id);

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_xmech TO `<ONETRUST_SP>`;
GRANT SELECT ON TABLE abac_onetrust.abac_xmech.both TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_xmech.abac_fn    TO `<ONETRUST_SP>`;
GRANT EXECUTE ON FUNCTION abac_onetrust.abac_xmech.classic_fn TO `<ONETRUST_SP>`;

-- Expect (as the SP via the suite):
--   OT-XT1: SELECT count(*) FROM abac_onetrust.abac_xmech.both
--           -> ERROR UC_ABAC_MULTIPLE_ROW_FILTERS (expected/hypothesised: the per-table limit spans
--              BOTH mechanisms). If instead a COUNT comes back, decode it per the table above.

-- ---- TEARDOWN ----
-- Order matters: drop the classic row filter FIRST, then the ABAC policy, then the schema.
--   ALTER TABLE abac_onetrust.abac_xmech.both DROP ROW FILTER;
--   DROP POLICY IF EXISTS xmech_policy ON TABLE abac_onetrust.abac_xmech.both;
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_xmech CASCADE;
```

- [ ] **Step 2: Add `xtGroupCases()` to `OnetrustCases.java`**

```java
    /** Mirrors TPC-DS's XT1 -- the one-row-filter-per-table limit spans both ABAC and classic RLS.
     *  Setup: sql_onetrust/16_cross_mechanism.sql. */
    public static List<Case> xtGroupCases() {
        List<Case> cs = new ArrayList<>();
        cs.add(new Case("OT-XT1", "XT", "Classic SET ROW FILTER + ABAC policy on the SAME table",
            "Mirrors TPC-DS XT1. Setup: sql_onetrust/16_cross_mechanism.sql. abac_fn keeps id<=10; "
                + "classic_fn keeps id>15 -- disjoint predicates make every outcome diagnostic (see the "
                + "decode table in the SQL file's comments).",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM abac_onetrust.abac_xmech.both",
            Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"), NEEDS_CLAIM_SWAP));
        return cs;
    }
```

- [ ] **Step 3: Fold into `all()`**

```java
        cs.addAll(ucGroupCases());
        cs.addAll(xtGroupCases());
        cs.addAll(compatibleQueryCases());
```

- [ ] **Step 4: Compile and smoke-test**

```bash
cd JDBC && mvn -q package
```

Same pattern, asserting `OnetrustCases.xtGroupCases().size() == 1`. Note: `sql_onetrust/16_cross_mechanism.sql` must be applied live before this case passes.

- [ ] **Step 5: Commit**

```bash
git add sql_onetrust/16_cross_mechanism.sql JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier B XT group (OT-XT1) for the OneTrust JDBC suite"
```

---

### Task 16: Tier B — EX (except clause) group (2 cases: `OT-EX1`, `OT-EX2`)

**Files:**
- Create: `sql_onetrust/17_except_and_defaults.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Produces: `OnetrustCases.exGroupCases()` returning `List<Case>`, folded into `all()`.

**Context:** A 1:1 port of TPC-DS's EX1 (the `TO ... EXCEPT` exemption) + EX2 (its control, disambiguating whether EX1's "all rows" means "exempted" or "never subject to the policy at all") from `sql/21_except_and_defaults.sql`. **`DP1` (DEFAULT UDF parameters) is NOT ported as a live case** — TPC-DS confirmed live (2026-07-23) that a `DEFAULT` does not let `USING COLUMNS` omit an argument (a DDL-time rejection, same non-suite-observable reason as `UC1` in Task 14) — kept as a commented-out reproducible demo only, exactly matching the source file.

- [ ] **Step 1: Write `sql_onetrust/17_except_and_defaults.sql`**

```sql
-- =====================================================================
-- 17_except_and_defaults.sql   (RUN IN THE DBR SQL EDITOR AS OWNER / METASTORE ADMIN)
-- Ported 1:1 from sql/21_except_and_defaults.sql (TPC-DS), for the OneTrust suite (OT-EX1/OT-EX2).
--
-- CONFIRMED FINDING (carried over from TPC-DS, 2026-07-23): row-filter UDF ARITY is STRICT, and a
-- DEFAULT parameter does NOT let USING COLUMNS omit the argument -- a DDL-time rejection the
-- service principal can never observe via its query path (same reasoning as UC1, Task 14). DP1's
-- CREATE POLICY block below is kept COMMENTED OUT as a reproducible demonstration only -- do NOT
-- uncomment it in a normal apply.
--
-- What this file DOES test live, via the service principal:
--   OT-EX1 = EXCEPT:  does CREATE POLICY ... TO <principal> EXCEPT <principal> actually exempt the
--                      excepted principal?
--   OT-EX2 = the CONTROL for OT-EX1 -- disambiguates whether OT-EX1's "all rows" means "the SP was
--            exempted" or "the SP was never subject to the broad TO grant at all".
--
-- OPERATOR NOTE: `account users` is the built-in Unity Catalog group covering every principal in
-- the account (backticked because its name contains a space). If this workspace rejects that
-- group form in a policy's TO clause, STOP and record the exact error verbatim -- do not silently
-- substitute a narrower principal, which would defeat what OT-EX1 tests.
--
-- SP the JDBC suite authenticates as: <ONETRUST_SP>
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS abac_onetrust.abac_gaps;

-- =========================================================
-- OT-EX1 -- the EXCEPT clause
-- =========================================================
CREATE OR REPLACE TABLE abac_onetrust.abac_gaps.exempt (id BIGINT);
INSERT INTO abac_onetrust.abac_gaps.exempt SELECT id FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_gaps.exempt ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE FUNCTION abac_onetrust.abac_gaps.except_filter(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;                -- keeps 10 of 20 rows for a SUBJECT principal

CREATE OR REPLACE POLICY exempt_policy
ON TABLE abac_onetrust.abac_gaps.exempt
ROW FILTER abac_onetrust.abac_gaps.except_filter
TO `account users`
EXCEPT `<ONETRUST_SP>`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

GRANT USE SCHEMA ON SCHEMA abac_onetrust.abac_gaps             TO `<ONETRUST_SP>`;
GRANT SELECT   ON TABLE  abac_onetrust.abac_gaps.exempt         TO `<ONETRUST_SP>`;
GRANT EXECUTE  ON FUNCTION abac_onetrust.abac_gaps.except_filter TO `<ONETRUST_SP>`;

-- =========================================================
-- OT-EX2 -- the CONTROL for OT-EX1. Same shape, same filter, same broad TO, but NO EXCEPT.
-- =========================================================
CREATE OR REPLACE TABLE abac_onetrust.abac_gaps.subject (id BIGINT);
INSERT INTO abac_onetrust.abac_gaps.subject SELECT id FROM range(1, 21);
ALTER TABLE abac_onetrust.abac_gaps.subject ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');

CREATE OR REPLACE POLICY subject_policy
ON TABLE abac_onetrust.abac_gaps.subject
ROW FILTER abac_onetrust.abac_gaps.except_filter
TO `account users`
FOR TABLES
MATCH COLUMNS has_tag('abac_column_id') AS id
USING COLUMNS (id);

GRANT SELECT ON TABLE abac_onetrust.abac_gaps.subject TO `<ONETRUST_SP>`;

-- =========================================================
-- DP1 -- DEFAULT UDF parameters: ANSWERED (see TPC-DS's confirmed finding above), kept commented
-- as a reproducible demo only. DO NOT UNCOMMENT IN A NORMAL APPLY -- fails BY DESIGN.
-- =========================================================
-- CREATE OR REPLACE TABLE abac_onetrust.abac_gaps.defparam (id BIGINT);
-- INSERT INTO abac_onetrust.abac_gaps.defparam SELECT id FROM range(1, 21);
-- ALTER TABLE abac_onetrust.abac_gaps.defparam ALTER COLUMN id SET TAGS ('abac_column_id' = 'true');
-- CREATE OR REPLACE FUNCTION abac_onetrust.abac_gaps.def_filter(id BIGINT, cutoff BIGINT DEFAULT 10)
-- RETURNS BOOLEAN RETURN id <= cutoff;
-- CREATE OR REPLACE POLICY defparam_policy
-- ON TABLE abac_onetrust.abac_gaps.defparam
-- ROW FILTER abac_onetrust.abac_gaps.def_filter
-- TO `<ONETRUST_SP>`
-- FOR TABLES
-- MATCH COLUMNS has_tag('abac_column_id') AS id
-- USING COLUMNS (id);          -- <-- 1 arg supplied, 2 declared -> REJECTED here

-- Expect (as the SP via the suite):
--   OT-EX2: SELECT count(*) FROM abac_onetrust.abac_gaps.subject  -> 10 (CONTROL: the SP IS subject
--           to subject_policy, no EXCEPT -- proves the SP is in `account users`, without which
--           OT-EX1 proves nothing)
--   OT-EX1: SELECT count(*) FROM abac_onetrust.abac_gaps.exempt   -> 20 (ALL rows), MEANINGFUL ONLY
--           IF OT-EX2 == 10. The SP is EXCEPTed from exempt_policy.

-- ---- TEARDOWN ----
--   DROP POLICY IF EXISTS exempt_policy   ON TABLE abac_onetrust.abac_gaps.exempt;
--   DROP POLICY IF EXISTS subject_policy  ON TABLE abac_onetrust.abac_gaps.subject;
--   DROP SCHEMA IF EXISTS abac_onetrust.abac_gaps CASCADE;
```

- [ ] **Step 2: Add `exGroupCases()` to `OnetrustCases.java`**

```java
    /** Mirrors TPC-DS's EX1/EX2 -- the TO ... EXCEPT exemption + its control. Setup:
     *  sql_onetrust/17_except_and_defaults.sql. DP1 has no case -- DDL-time rejection, not
     *  suite-observable, see the class doc there. */
    public static List<Case> exGroupCases() {
        String schema = "abac_onetrust.abac_gaps";
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-EX2", "EX", "CONTROL for OT-EX1: the SP IS subject to a broad TO with no EXCEPT -- filtered to 10",
            "Mirrors TPC-DS EX2. Setup: sql_onetrust/17_except_and_defaults.sql. Must run/be read "
                + "BEFORE OT-EX1 -- OT-EX1's result is only meaningful if this returns 10.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".subject", Expect.exact(10), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-EX1", "EX", "EXCEPT clause: the excepted principal is NOT subject to the policy -- sees ALL rows",
            "Mirrors TPC-DS EX1. exempt_policy is bound TO `account users` EXCEPT the SP.",
            Cases.DISABLE_CLAIM, "SELECT count(*) FROM " + schema + ".exempt", Expect.exact(20), NEEDS_CLAIM_SWAP));

        return cs;
    }
```

- [ ] **Step 3: Fold into `all()`**

```java
        cs.addAll(xtGroupCases());
        cs.addAll(exGroupCases());
        cs.addAll(compatibleQueryCases());
```

- [ ] **Step 4: Compile and smoke-test**

```bash
cd JDBC && mvn -q package
```

Same pattern, asserting `OnetrustCases.exGroupCases().size() == 2`. Note: `sql_onetrust/17_except_and_defaults.sql` must be applied live before these cases pass — and if `` `account users` `` is rejected in this workspace's `TO` clause, report that verbatim rather than substituting a narrower principal (see the SQL file's operator note).

- [ ] **Step 5: Commit**

```bash
git add sql_onetrust/17_except_and_defaults.sql JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier B EX group (OT-EX1, OT-EX2) for the OneTrust JDBC suite"
```

---

### Task 17: Tier B — CL (claim shapes) group (4 cases: `OT-CL1`..`OT-CL4`)

**Files:**
- Modify: `JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java`

**Interfaces:**
- Consumes: `u.assessment.owner@example.com`'s real seeded assignment (Task 2), `q()` helper.
- Produces: `OnetrustCases.clGroupCases()` returning `List<Case>`, folded into `all()`.

**Context:** Mirrors TPC-DS's CL1-CL4 — malformed/null-shaped claim JSON (missing `mode` key entirely, explicit `null` user, `permissions` as the wrong JSON type, a null element inside `permissions`). No DDL — pure claim-shape variation against the real seeded assignment, same idiom as `OT-A6`-style deny cases. Claims here are raw JSON literals (not built via `Cases.claim(...)`, which always emits all 6 fields), matching how TPC-DS's `Cases.java` constructs its own CL cases.

- [ ] **Step 1: Add `clGroupCases()` to `OnetrustCases.java`**

```java
    /** Mirrors TPC-DS's CL1-CL4 -- malformed/null-shaped claim JSON. No DDL; pure claim-shape
     *  variation against the real seeded assignment (Task 2). */
    public static List<Case> clGroupCases() {
        List<Case> cs = new ArrayList<>();

        cs.add(new Case("OT-CL1", "CL", "Claim missing the `mode` key entirely, sent by a user with NO assignment",
            "Mirrors TPC-DS CL1. from_json produces ctx.mode = NULL -- branch 1 (mode='DISABLE') and "
                + "3a (mode='RBAC_ABAC') are both unreadable without a mode, regardless of who ctx.user "
                + "is; this user has no assignment either, so 3b also fails.",
            "{\"tenant\":1,\"user\":\"u.cl.nobody@example.com\",\"org\":\"100\",\"root\":\"ASSESSMENT\",\"permissions\":[]}",
            "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-CL2", "CL", "Claim with an explicit null user",
            "Mirrors TPC-DS CL2. ctx.user = NULL, so the 3b subject match (esa.subjectId = ctx.user) "
                + "is NULL for every row and no assignment can match.",
            "{\"tenant\":1,\"user\":null,\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"ASSESSMENT\",\"permissions\":[]}",
            "SELECT count(*) FROM " + q("cmb_assessment"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-CL3", "CL", "Claim with `permissions` as a string instead of an array",
            "Mirrors TPC-DS CL3. The declared struct type is ARRAY<STRING>; a scalar string is not "
                + "coercible, so ctx.permissions is NULL and array_contains(NULL, ...) is NULL -- "
                + "branch 2 cannot fire. root=ASSESSMENT querying cmb_template (non-root) means only "
                + "branch 2 could have granted access.",
            "{\"tenant\":1,\"user\":\"u.assessment.owner@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"ASSESSMENT\",\"permissions\":\"TEMPLATE\"}",
            "SELECT count(*) FROM " + q("cmb_template"), Expect.zero(), NEEDS_CLAIM_SWAP));

        cs.add(new Case("OT-CL4", "CL", "Claim with a null ELEMENT inside `permissions` (not covered by CL1-CL3)",
            "Mirrors TPC-DS CL4. permissions=[null,\"CONTROL\"] -- INFO until observed: does a null "
                + "element alongside a real match break array_contains's ability to find 'CONTROL', or "
                + "does it still correctly fire branch 2? root=ASSESSMENT querying cmb_controlimplementation "
                + "(non-root) isolates branch 2 as the only path that could grant access.",
            "{\"tenant\":1,\"user\":\"u.assessment.owner@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"ASSESSMENT\",\"permissions\":[null,\"CONTROL\"]}",
            "SELECT count(*) FROM " + q("cmb_controlimplementation"), Expect.info(), NEEDS_CLAIM_SWAP));

        return cs;
    }
```

- [ ] **Step 2: Fold into `all()`**

```java
        cs.addAll(exGroupCases());
        cs.addAll(clGroupCases());
        cs.addAll(compatibleQueryCases());
```

- [ ] **Step 3: Compile and smoke-test**

```bash
cd JDBC && mvn -q package
```

Same pattern, asserting `OnetrustCases.clGroupCases().size() == 4`. This closes out Tier B's case groups — at this point `OnetrustCases.all()` should total `8 + 9 + 4 + 6 + 4 + 8 + 4 + 4 + 3 + 1 + 3 + 4 + 3 + 1 + 1 + 2 + 4 + 50 = 119` (`functionalCases`' 8 + Tier A's 23 + Tier B's 38 [including Task 6b's EDGE, 8 cases] + `compatibleQueryCases`' 50). Assert this count in the smoke test — no live credentials needed for OT-CL1..OT-CL4 specifically, since they're plain JSON literals, but the full-suite count assertion doesn't require a live run either.

- [ ] **Step 4: Commit**

```bash
git add JDBC/src/main/java/com/abacpoc/cases/OnetrustCases.java
git commit -m "feat: Tier B CL group (OT-CL1..OT-CL4) for the OneTrust JDBC suite"
```

**Tier B's 38 cases are now complete.** Tasks 18-20 build the 8 duplicated scenario classes.

---

### Task 18: Scenarios — `OnetrustDr2HotSwap` + `OnetrustViewPolicySwap`

**Files:**
- Create: `JDBC/src/main/java/com/abacpoc/scenario/OnetrustDr2HotSwap.java`
- Create: `JDBC/src/main/java/com/abacpoc/scenario/OnetrustViewPolicySwap.java`

**Interfaces:**
- Consumes: `abac_onetrust.abac_rls.dr2_demo`/`dr2_row_filter`/`dr2_wrapper` (Task 10), `abac_onetrust.abac_rls.v_dr2_demo_governed` (Task 11), `Cases.DISABLE_CLAIM` (existing, engine-agnostic — DISABLE mode ignores every other claim field).
- Produces: two new `Scenario` implementations, wired into `runOnetrustCases` by Task 21.

**Context:** 1:1 duplicates of `Dr2HotSwap.java`/`ViewPolicySwap.java` (read in full above), retargeted at Task 10/11's isolated `dr2_demo` table and view instead of TPC-DS's `income_band`/`v_income_band_governed`. `OnetrustViewPolicySwap` **must run after** `OnetrustDr2HotSwap` in the scenario list (Task 21) — exactly like TPC-DS's ordering constraint — since `Dr2HotSwap` reverts the cutoff to 10 when it finishes, which is exactly the baseline `ViewPolicySwap`'s first assertion expects.

- [ ] **Step 1: Write `OnetrustDr2HotSwap.java`**

```java
package com.abacpoc.scenario;

import com.abacpoc.cases.Cases;
import com.abacpoc.engine.Capability;
import com.abacpoc.engine.Engine;
import com.abacpoc.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/** OneTrust duplicate of Dr2HotSwap -- same structure, retargeted at
 *  abac_onetrust.abac_rls.dr2_demo / dr2_row_filter / dr2_wrapper (sql_onetrust/11). */
public class OnetrustDr2HotSwap implements Scenario {

    private static final String TBL = "abac_onetrust.abac_rls.dr2_demo";
    private static final String FN  = "abac_onetrust.abac_rls.dr2_row_filter";

    @Override public String id() { return "OT-DR2"; }

    @Override public Set<Capability> requires() {
        return Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.CLAIM_SWAP);
    }

    @Override public int[] run(Engine e, Connection c) {
        int[] r = runDr2Swap(e, c);
        return new int[]{r[0], r[1], 0, r[2]};
    }

    static int[] runDr2Swap(Engine e, Connection c) {
        int pass = 0, fail = 0, error = 0;
        final String CNT = "SELECT count(*) FROM " + TBL;
        System.out.println();
        System.out.println("---------------- OT-DR2 hot-swap scenario (dr2_demo, ABAC has_tag() policy) ----------------");
        System.out.println(" Policy binds dr2_wrapper -> dr2_row_filter (SP-owned, swappable). Change the INNER UDF,");
        System.out.println(" POLL until the change is reflected (measuring real propagation latency), then revert.");
        System.out.println(" Tables/UDFs come from sql_onetrust/11_direct_rls_and_dr2.sql.");
        Thread guard = null;
        try {
            e.applyIdentity(c, Cases.DISABLE_CLAIM);   // dr2_wrapper calls get_user_context() -> session must carry a claim
            long a1 = Jdbc.count(c, CNT);
            boolean ok1 = (a1 == 10);
            print("OT-DR2a", "baseline (ABAC policy; dr2_row_filter cutoff <= 10): 10 of 20 rows",
                  CNT, "10", String.valueOf(a1), ok1);
            if (ok1) pass++; else fail++;

            guard = registerRevertGuard(c);

            Jdbc.exec(c, dr2Def(5));
            System.out.println();
            System.out.println("   [swapped dr2_row_filter -> cutoff <= 5; polling until reflected ...]");
            long ms = Jdbc.pollUntilCount(c, CNT, 5, 30_000, 250);
            boolean ok2 = (ms >= 0);
            print("OT-DR2b", "after CREATE OR REPLACE (cutoff <= 5): 5 of 20 rows"
                             + (ms >= 0 ? "  [swap->reflected in " + ms + " ms, measured by polling]"
                                        : "  [DID NOT reflect within 30s -- change never propagated]"),
                  CNT, "5", ok2 ? "5 (reached)" : "still not 5 after 30s", ok2);
            if (ok2) pass++; else fail++;

            Jdbc.exec(c, dr2Def(10));
            long a3 = Jdbc.count(c, CNT);
            boolean ok3 = (a3 == 10);
            print("OT-DR2c", "reverted dr2_row_filter -> cutoff <= 10: visible count back to 10",
                  CNT, "10", String.valueOf(a3), ok3);
            if (ok3) pass++; else fail++;
        } catch (SQLException e2) {
            System.out.println("   actual : <error> " + Jdbc.shortErr(e2.getMessage()));
            System.out.println("   verdict: ERROR (OT-DR2 scenario). Ensure sql_onetrust/11 ran and the SP OWNS"
                             + " dr2_row_filter (CREATE OR REPLACE needs ownership).");
            error++;
            try { Jdbc.exec(c, dr2Def(10)); } catch (SQLException ignore) { /* best-effort revert */ }
        } finally {
            removeGuard(guard);
        }
        return new int[]{pass, fail, error};
    }

    static Thread registerRevertGuard(Connection c) {
        Thread hook = new Thread(() -> {
            try { Jdbc.exec(c, dr2Def(10)); } catch (Throwable ignore) { /* best-effort during shutdown */ }
        }, "ot-dr2-revert-guard");
        try { Runtime.getRuntime().addShutdownHook(hook); } catch (IllegalStateException alreadyShuttingDown) { return null; }
        return hook;
    }

    static void removeGuard(Thread hook) {
        if (hook == null) return;
        try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignore) { /* shutdown in progress */ }
    }

    static String dr2Def(int cutoff) {
        return "CREATE OR REPLACE FUNCTION " + FN
             + "(entity_id STRING, object_type STRING, org_id STRING,"
             + " ctx STRUCT<tenant:INT,user:STRING,org:STRING,mode:STRING,root:STRING,permissions:ARRAY<STRING>>)"
             + " RETURNS BOOLEAN RETURN try_cast(entity_id AS BIGINT) <= " + cutoff;
    }

    static void print(String id, String purpose, String sql, String expect, String actual, boolean ok) {
        System.out.println();
        System.out.println("[" + id + "] (OT-DR2) " + purpose);
        System.out.println("   sql    : " + sql);
        System.out.println("   expect : " + expect);
        System.out.println("   actual : " + actual);
        System.out.println("   verdict: " + (ok ? "PASS" : "FAIL"));
    }
}
```

This keeps `OnetrustDr2HotSwap.run(Engine e, Connection c)` self-contained exactly like the TPC-DS original — it calls `e.applyIdentity(c, Cases.DISABLE_CLAIM)` itself, so Task 21's scenario-loop wiring needs no special-casing beyond passing the right `(engine, onetrustConn)` pair, identical in shape to `runAll`'s existing scenario loop.

- [ ] **Step 2: Write `OnetrustViewPolicySwap.java`**

```java
package com.abacpoc.scenario;

import com.abacpoc.engine.Capability;
import com.abacpoc.engine.Engine;
import com.abacpoc.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/** OneTrust duplicate of ViewPolicySwap -- same structure, retargeted at
 *  abac_onetrust.abac_rls.v_dr2_demo_governed (sql_onetrust/12_views.sql), reusing
 *  OnetrustDr2HotSwap's dr2Def/registerRevertGuard/removeGuard (same underlying UDF). */
public class OnetrustViewPolicySwap implements Scenario {

    @Override public String id() { return "OT-VP"; }

    @Override public Set<Capability> requires() {
        return Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.CLAIM_SWAP, Capability.VIEWS);
    }

    @Override public int[] run(Engine e, Connection c) {
        int[] r = runViewSwap(c);
        return new int[]{r[0], r[1], 0, r[2]};
    }

    static int[] runViewSwap(Connection c) {
        int pass = 0, fail = 0, error = 0;
        final String VIEW = "abac_onetrust.abac_rls.v_dr2_demo_governed";
        final String CNT = "SELECT count(*) FROM " + VIEW;
        System.out.println();
        System.out.println("---------------- OT-VP view+policy-swap scenario (v_dr2_demo_governed, through a VIEW) ----------------");
        System.out.println(" Reuses sql_onetrust/11's dr2_row_filter (SP-owned, swappable) bound via dr2_wrapper by");
        System.out.println(" dr2_demo_policy, queried through sql_onetrust/12's v_dr2_demo_governed VIEW. Runs AFTER");
        System.out.println(" OnetrustDr2HotSwap, which reverted cutoff to 10, so baseline holds.");
        Thread guard = null;
        try {
            long a1 = Jdbc.count(c, CNT);
            boolean ok1 = (a1 == 10);
            print("OT-VP1", "baseline THROUGH THE VIEW (ABAC policy; dr2_row_filter cutoff <= 10): 10 of 20 rows",
                  CNT, "10", String.valueOf(a1), ok1);
            if (ok1) pass++; else fail++;

            guard = OnetrustDr2HotSwap.registerRevertGuard(c);

            Jdbc.exec(c, OnetrustDr2HotSwap.dr2Def(5));
            System.out.println();
            System.out.println("   [swapped dr2_row_filter -> cutoff <= 5; polling the VIEW until reflected ...]");
            long ms = Jdbc.pollUntilCount(c, CNT, 5, 30_000, 250);
            boolean ok2 = (ms >= 0);
            print("OT-VP2", "after CREATE OR REPLACE (cutoff <= 5), THROUGH THE VIEW: 5 of 20 rows"
                             + (ms >= 0 ? "  [swap->reflected in " + ms + " ms, measured by polling]"
                                        : "  [DID NOT reflect within 30s -- change never propagated through the view]"),
                  CNT, "5", ok2 ? "5 (reached)" : "still not 5 after 30s", ok2);
            if (ok2) pass++; else fail++;

            Jdbc.exec(c, OnetrustDr2HotSwap.dr2Def(10));
            long a3 = Jdbc.count(c, CNT);
            boolean ok3 = (a3 == 10);
            print("OT-VP3", "reverted dr2_row_filter -> cutoff <= 10: visible count THROUGH THE VIEW back to 10",
                  CNT, "10", String.valueOf(a3), ok3);
            if (ok3) pass++; else fail++;
        } catch (SQLException e2) {
            System.out.println("   actual : <error> " + Jdbc.shortErr(e2.getMessage()));
            System.out.println("   verdict: ERROR (OT-VP scenario). Ensure sql_onetrust/11 and 12 ran and the SP"
                             + " OWNS dr2_row_filter.");
            error++;
            try { Jdbc.exec(c, OnetrustDr2HotSwap.dr2Def(10)); } catch (SQLException ignore) { /* best-effort revert */ }
        } finally {
            OnetrustDr2HotSwap.removeGuard(guard);
        }
        return new int[]{pass, fail, error};
    }

    static void print(String id, String purpose, String sql, String expect, String actual, boolean ok) {
        System.out.println();
        System.out.println("[" + id + "] (OT-VP) " + purpose);
        System.out.println("   sql    : " + sql);
        System.out.println("   expect : " + expect);
        System.out.println("   actual : " + actual);
        System.out.println("   verdict: " + (ok ? "PASS" : "FAIL"));
    }
}
```

- [ ] **Step 3: Compile**

```bash
cd JDBC && mvn -q package
```

Expected: `BUILD SUCCESS`. These two classes aren't wired into `runOnetrustCases` yet (Task 21 does that), so this task's bar is a clean compile plus a careful re-read of both files against `Dr2HotSwap.java`/`ViewPolicySwap.java` confirming the only differences are: table/function/view names, the `Cases.DISABLE_CLAIM`-application relocation (see the note in Step 1), and the `OT-` id prefixes.

- [ ] **Step 4: Commit**

```bash
git add JDBC/src/main/java/com/abacpoc/scenario/OnetrustDr2HotSwap.java JDBC/src/main/java/com/abacpoc/scenario/OnetrustViewPolicySwap.java
git commit -m "feat: OnetrustDr2HotSwap + OnetrustViewPolicySwap scenarios"
```

---

### Task 19: Scenarios — `OnetrustSecretInvariance` + `OnetrustSecondPrincipal` + `OnetrustTokenExpiry`

**Files:**
- Create: `JDBC/src/main/java/com/abacpoc/scenario/OnetrustSecretInvariance.java`
- Create: `JDBC/src/main/java/com/abacpoc/scenario/OnetrustSecondPrincipal.java`
- Create: `JDBC/src/main/java/com/abacpoc/scenario/OnetrustTokenExpiry.java`

**Interfaces:**
- Consumes: `abac_onetrust.abac_rls.dr2_demo` (Task 10, a governed table bound `TO` the OneTrust SP only — the same role TPC-DS's `income_band` plays for its `SecondPrincipal`), `OT-A2`'s claim/entity (Task 3), `AbacJdbcClient.mintCustomClaimToken` (existing, engine-agnostic).
- Produces: three new `Scenario` implementations, wired into `runOnetrustCases` by Task 21. New env vars, distinct from both TPC-DS's and the primary OneTrust connection's, to avoid any naming collision: `ONETRUST_CLIENT_SECRET_ALT`, `ONETRUST_SP2_CLIENT_ID`/`ONETRUST_SP2_CLIENT_SECRET`, `ONETRUST_ABAC_EXPIRED_TOKEN`.

**Context:** 1:1 duplicates of `SecretInvariance.java`, `SecondPrincipal.java`, `TokenExpiry.java` (all read in full above) — same secret-doesn't-affect-decisions / non-TO-principal-sees-unfiltered / expired-token-fails-closed proofs, retargeted at OneTrust data and given their own env vars.

- [ ] **Step 1: Write `OnetrustSecretInvariance.java`**

```java
package com.abacpoc.scenario;

import com.abacpoc.engine.Capability;
import com.abacpoc.engine.DatabricksEngine;
import com.abacpoc.engine.Engine;
import com.abacpoc.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/** OneTrust duplicate of SecretInvariance -- same SP, a 2nd OAuth secret, must reach IDENTICAL
 *  ABAC decisions. Uses ONETRUST_CLIENT_SECRET_ALT (distinct from TPC-DS's CLIENT_SECRET_ALT and
 *  from the primary ONETRUST_CLIENT_SECRET). Probes: OT-A2's claim/table (branch 3b), OT-A9's
 *  claim/table (branch 2), DISABLE on dr2_demo (branch 1). */
public class OnetrustSecretInvariance implements Scenario {

    @Override public String id() { return "OT-SEC"; }

    @Override public Set<Capability> requires() { return Set.of(Capability.CLAIM_SWAP); }

    @Override public int[] run(Engine e, Connection c) {
        if (!(e instanceof DatabricksEngine)) {
            System.out.println();
            System.out.println("[OT-SEC] verdict: SKIP (Databricks-auth-specific; engine is " + e.name() + ")");
            return new int[]{0, 0, 1, 0};
        }

        String clientId = System.getenv("ONETRUST_CLIENT_ID");
        String secretAlt = System.getenv("ONETRUST_CLIENT_SECRET_ALT");
        if (secretAlt == null || secretAlt.isEmpty()) {
            System.out.println();
            System.out.println("[OT-SEC] verdict: SKIP (set ONETRUST_CLIENT_SECRET_ALT (a 2nd OAuth secret"
                             + " for the same OneTrust SP) to run)");
            return new int[]{0, 0, 1, 0};
        }

        int pass = 0, fail = 0, error = 0;
        System.out.println();
        System.out.println("---------------- OT-SEC secret-invariance scenario (same OneTrust SP, 2nd OAuth secret) ----------------");
        System.out.println(" Opens a SECOND connection authenticating as the SAME SP (ONETRUST_CLIENT_ID unchanged) but with");
        System.out.println(" ONETRUST_CLIENT_SECRET_ALT instead of ONETRUST_CLIENT_SECRET. For each probe, inject the SAME");
        System.out.println(" claim on BOTH connections and assert the counts are EQUAL.");

        Connection conn2 = null;
        try {
            conn2 = ((DatabricksEngine) e).connectAs(clientId, secretAlt);

            String[][] probes = {
                {"OT-SEC1",
                    "{\"tenant\":1,\"user\":\"u.assessment.owner@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"ASSESSMENT\",\"permissions\":[]}",
                    "SELECT count(*) FROM abac_onetrust.onetrust_sim.cmb_assessment", "branch 3b per-row assignment"},
                {"OT-SEC2",
                    "{\"tenant\":1,\"user\":\"u.assessment.owner@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"ASSESSMENT\",\"permissions\":[\"CONTROL\"]}",
                    "SELECT count(*) FROM abac_onetrust.onetrust_sim.cmb_controlimplementation", "branch 2 permissions"},
                {"OT-SEC3", "{\"tenant\":1,\"user\":\"probe\",\"org\":\"100\",\"mode\":\"DISABLE\",\"root\":\"DR2_DEMO\",\"permissions\":[]}",
                    "SELECT count(*) FROM abac_onetrust.abac_rls.dr2_demo", "branch 1 DISABLE"}
            };

            for (String[] p : probes) {
                String pid = p[0], claim = p[1], sql = p[2], branch = p[3];

                e.applyIdentity(c, claim);
                long n1 = Jdbc.count(c, sql);

                e.applyIdentity(conn2, claim);
                long n2 = Jdbc.count(conn2, sql);

                boolean ok = (n1 == n2);
                System.out.println();
                System.out.println("[" + pid + "] (OT-SEC) same SP, 2nd secret -- " + branch + ": counts must be EQUAL");
                System.out.println("   sql    : " + sql);
                System.out.println("   claim  : " + claim);
                System.out.println("   expect : primary connection == alt-secret connection");
                System.out.println("   actual : primary=" + n1 + "  alt-secret=" + n2);
                System.out.println("   verdict: " + (ok ? "PASS" : "FAIL"));
                if (ok) pass++; else fail++;
            }
        } catch (SQLException ex) {
            System.out.println("   actual : <error> " + Jdbc.shortErr(ex.getMessage()));
            System.out.println("   verdict: ERROR (OT-SEC scenario)");
            error++;
        } finally {
            if (conn2 != null) {
                try { conn2.close(); } catch (SQLException ignore) { /* best-effort */ }
            }
        }
        return new int[]{pass, fail, 0, error};
    }
}
```

- [ ] **Step 2: Write `OnetrustSecondPrincipal.java`**

```java
package com.abacpoc.scenario;

import com.abacpoc.engine.Capability;
import com.abacpoc.engine.DatabricksEngine;
import com.abacpoc.engine.Engine;
import com.abacpoc.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/** OneTrust duplicate of SecondPrincipal (MSP): dr2_demo_policy (sql_onetrust/11) binds TO the
 *  OneTrust SP ONLY. A DIFFERENT service principal (ONETRUST_SP2_CLIENT_ID/SECRET -- NOT the one
 *  the policy binds to) queries dr2_demo with NO claim injected: the policy is not bound to it,
 *  so get_user_context() never runs for it, and it should see the table RAW -- ALL 20 rows. */
public class OnetrustSecondPrincipal implements Scenario {

    @Override public String id() { return "OT-MSP"; }

    @Override public Set<Capability> requires() { return Set.of(Capability.CLAIM_SWAP); }

    @Override public int[] run(Engine e, Connection c) {
        if (!(e instanceof DatabricksEngine)) {
            System.out.println();
            System.out.println("[OT-MSP] verdict: SKIP (Databricks-auth-specific; engine is " + e.name() + ")");
            return new int[]{0, 0, 1, 0};
        }

        String spClientId = System.getenv("ONETRUST_SP2_CLIENT_ID");
        String spSecret = System.getenv("ONETRUST_SP2_CLIENT_SECRET");
        if (spClientId == null || spClientId.isEmpty() || spSecret == null || spSecret.isEmpty()) {
            System.out.println();
            System.out.println("[OT-MSP] verdict: SKIP (set ONETRUST_SP2_CLIENT_ID and ONETRUST_SP2_CLIENT_SECRET"
                             + " (a 2nd, DIFFERENT service principal, granted SELECT on abac_onetrust.abac_rls.dr2_demo) to run)");
            return new int[]{0, 0, 1, 0};
        }

        int pass = 0, fail = 0, error = 0;
        final String SQL = "SELECT count(*) FROM abac_onetrust.abac_rls.dr2_demo";
        System.out.println();
        System.out.println("---------------- OT-MSP second-principal scenario (dr2_demo, principal-targeting) ----------------");
        System.out.println(" dr2_demo_policy (sql_onetrust/11) binds TO the OneTrust SP ONLY. Query dr2_demo as a");
        System.out.println(" DIFFERENT service principal (SP-B) with NO claim injected -- SP-B should see ALL 20 rows raw.");

        System.out.println();
        System.out.println("[OT-MSP1] (OT-MSP) SP-B (not in the policy's TO set) queries dr2_demo, no claim injected");
        System.out.println("   sql    : " + SQL);

        Connection connB;
        try {
            connB = ((DatabricksEngine) e).connectAs(spClientId, spSecret);
        } catch (SQLException ce) {
            System.out.println("   actual : <error> " + Jdbc.shortErr(ce.getMessage()));
            System.out.println("   verdict: ERROR -- SP-B cannot open a session on this warehouse (it failed to CONNECT,"
                             + " before any query). Grant SP-B workspace access AND `Can use` on the SQL warehouse,"
                             + " then also GRANT SELECT ON TABLE abac_onetrust.abac_rls.dr2_demo TO <SP-B application id>.");
            return new int[]{0, 0, 0, 1};
        }

        try {
            long n = Jdbc.count(connB, SQL);
            boolean ok = (n == 20);
            System.out.println("   expect : 20 (ALL rows, unfiltered -- the policy does not govern SP-B)");
            System.out.println("   actual : " + n
                             + (n == 10 ? "  (SP-B was unexpectedly subject to the filter -- significant)" : ""));
            System.out.println("   verdict: " + (ok ? "PASS" : "FAIL"));
            if (ok) pass++; else fail++;
        } catch (SQLException qe) {
            System.out.println("   actual : <error> " + Jdbc.shortErr(qe.getMessage()));
            System.out.println("   verdict: ERROR -- SP-B connected but the query failed; it likely lacks SELECT. Grant it:"
                             + " GRANT SELECT ON TABLE abac_onetrust.abac_rls.dr2_demo TO <SP-B application id>");
            error++;
        } finally {
            try { connB.close(); } catch (SQLException ignore) { /* best-effort */ }
        }
        return new int[]{pass, fail, 0, error};
    }
}
```

- [ ] **Step 3: Write `OnetrustTokenExpiry.java`**

```java
package com.abacpoc.scenario;

import com.abacpoc.AbacJdbcClient;
import com.abacpoc.engine.Capability;
import com.abacpoc.engine.DatabricksEngine;
import com.abacpoc.engine.Engine;
import com.abacpoc.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/** OneTrust duplicate of TokenExpiry (EXP): an EXPIRED OAuth access token must be REJECTED, never
 *  honored. Uses ONETRUST_ABAC_EXPIRED_TOKEN (distinct from TPC-DS's ABAC_EXPIRED_TOKEN). Same
 *  pure-AUTH probe (SELECT 1) and EXP0 fresh-token control as the TPC-DS original. */
public class OnetrustTokenExpiry implements Scenario {

    @Override public String id() { return "OT-EXP"; }

    @Override public Set<Capability> requires() { return Set.of(); }

    @Override public int[] run(Engine e, Connection c) {
        if (!(e instanceof DatabricksEngine)) {
            System.out.println();
            System.out.println("[OT-EXP] verdict: SKIP (Databricks-auth-specific; engine is " + e.name() + ")");
            return new int[]{0, 0, 1, 0};
        }

        String token = System.getenv("ONETRUST_ABAC_EXPIRED_TOKEN");
        if (token == null || token.isEmpty()) {
            System.out.println();
            System.out.println("[OT-EXP] verdict: SKIP (set ONETRUST_ABAC_EXPIRED_TOKEN to a raw, EXPIRED OAuth"
                             + " access token minted for the OneTrust SP to run)");
            return new int[]{0, 0, 1, 0};
        }

        int pass = 0, fail = 0, error = 0;
        System.out.println();
        System.out.println("---------------- OT-EXP token-expiry scenario (expired bearer must fail closed) ----------------");
        System.out.println(" Injects a raw token via Auth_Flow=0 (token pass-through, NO refresh) and runs `SELECT 1`,");
        System.out.println(" a pure authentication probe. OT-EXP0 is a CONTROL; OT-EXP1 is the test.");

        System.out.println();
        System.out.println("[OT-EXP0] (OT-EXP) CONTROL: a FRESH valid token through the same pass-through path must authenticate");
        System.out.println("   sql    : SELECT 1");
        System.out.println("   expect : 1 (the pass-through path accepts a good token)");
        boolean controlOk = false;
        try {
            String fresh = AbacJdbcClient.mintCustomClaimToken(c,
                "{\"tenant\":1,\"user\":\"probe\",\"org\":\"100\",\"mode\":\"DISABLE\",\"root\":\"ASSESSMENT\",\"permissions\":[]}");
            try (Connection ctrl = ((DatabricksEngine) e).connectWithAccessToken(fresh)) {
                long n = Jdbc.count(ctrl, "SELECT 1");
                controlOk = (n == 1);
                System.out.println("   actual : " + n);
                System.out.println("   verdict: " + (controlOk ? "PASS" : "FAIL"));
                if (controlOk) pass++; else fail++;
            }
        } catch (SQLException ce) {
            System.out.println("   actual : <error> " + Jdbc.shortErr(ce.getMessage()));
            System.out.println("   verdict: ERROR -- the pass-through mechanism itself failed on a FRESH token;"
                             + " the expiry result below cannot be trusted until this is fixed.");
            error++;
        }

        System.out.println();
        System.out.println("[OT-EXP1] (OT-EXP) expired OAuth token + embedded claim, static bearer, no refresh");
        System.out.println("   sql    : SELECT 1   (isolates AUTH from ABAC filtering)");
        System.out.println("   expect : REJECTED at auth (fail-closed) -- NOT a returned row"
                         + (controlOk ? "" : "   [WARNING: OT-EXP0 control did NOT pass -- result below is unverified]"));
        Connection conn = null;
        try {
            conn = ((DatabricksEngine) e).connectWithAccessToken(token);
            long n = Jdbc.count(conn, "SELECT 1");
            System.out.println("   actual : returned " + n + " -- the expired token AUTHENTICATED");
            System.out.println("   verdict: FAIL -- SECURITY: an expired token was accepted and ran a query."
                             + " Expired tokens must be rejected regardless of the claim they carry.");
            fail++;
        } catch (SQLException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            boolean authLooking = msg.matches("(?s).*(token|expir|401|403|unauthor|authenticat|"
                                            + "credential|invalid|denied|forbidden|oauth).*");
            System.out.println("   actual : <error> " + Jdbc.shortErr(ex.getMessage()));
            if (authLooking && controlOk) {
                System.out.println("   verdict: PASS -- expired token REJECTED at auth (fail-closed); a fresh token"
                                 + " through the same path was accepted (OT-EXP0), so this rejection is due to EXPIRY");
                pass++;
            } else if (authLooking) {
                System.out.println("   verdict: ERROR -- looks like an auth rejection, but OT-EXP0 control did not"
                                 + " pass, so it cannot be attributed to expiry rather than a mechanism problem.");
                error++;
            } else {
                System.out.println("   verdict: ERROR -- no data returned, but the error is not clearly an auth"
                                 + " rejection. Verify the token and the Auth_Flow=0 / Auth_AccessToken path.");
                error++;
            }
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (SQLException ignore) { /* best-effort */ }
            }
        }
        return new int[]{pass, fail, 0, error};
    }
}
```

- [ ] **Step 4: Compile**

```bash
cd JDBC && mvn -q package
```

Expected: `BUILD SUCCESS`. Verify `AbacJdbcClient.mintCustomClaimToken` is `public static` (or otherwise accessible from the `scenario` package) by checking its signature in `JDBC/src/main/java/com/abacpoc/AbacJdbcClient.java` before assuming this compiles — `TokenExpiry.java`'s existing use of it confirms it's already accessible from the `scenario` package today, so no visibility change should be needed.

- [ ] **Step 5: Commit**

```bash
git add JDBC/src/main/java/com/abacpoc/scenario/OnetrustSecretInvariance.java JDBC/src/main/java/com/abacpoc/scenario/OnetrustSecondPrincipal.java JDBC/src/main/java/com/abacpoc/scenario/OnetrustTokenExpiry.java
git commit -m "feat: OnetrustSecretInvariance + OnetrustSecondPrincipal + OnetrustTokenExpiry scenarios"
```

---

### Task 20: Scenario — `OnetrustE6Scenarios` (7 placeholders)

**Files:**
- Create: `JDBC/src/main/java/com/abacpoc/scenario/OnetrustE6Scenarios.java`

**Interfaces:**
- Produces: `OnetrustE6Scenarios.all()` returning `List<Scenario>` (7 placeholders), wired into `runOnetrustCases` by Task 21.

**Context:** A mechanical 1:1 port of `E6Scenarios.java` (read in full above) — all 7 require `Capability.CLAIM_SWAP`, which no engine advertises for e6data until its ABAC identity flow ships, so every one unconditionally reports `SKIP` regardless of engine. No OneTrust-specific content exists to adapt here (these are pure placeholders); only the `id()` prefix changes, for consistency with every other OneTrust scenario in this plan.

- [ ] **Step 1: Write `OnetrustE6Scenarios.java`**

```java
package com.abacpoc.scenario;

import com.abacpoc.engine.Capability;
import com.abacpoc.engine.Engine;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

/** OneTrust duplicate of E6Scenarios -- e6data-specific placeholders (planner topology, caching,
 *  pooling, token lifecycle, errors). All require CLAIM_SWAP, which no engine advertises until the
 *  e6data ABAC identity flow ships, so each unconditionally reports SKIP. Mechanical port -- these
 *  placeholders have no OneTrust-specific content to adapt; only the id() prefix differs. */
public final class OnetrustE6Scenarios {

    public static List<Scenario> all() {
        return List.of(
            simple("OT-E6-PLANNER",  "Authenticate on planner A, query planner B — identity is honored, not reused or dropped"),
            simple("OT-E6-CACHE",    "After a policy change, a subsequent query reflects it (ASSERT the new result; REPORT how long it took)"),
            simple("OT-E6-POOL",     "Two identities over a reused connection do not bleed into each other"),
            simple("OT-E6-EXPIRY",   "Token expiry mid-flow yields a clean categorized error, never unfiltered rows"),
            simple("OT-E6-RETRY",    "A transient connect failure recovers within a bounded ATTEMPT COUNT (a count, not a duration)"),
            simple("OT-E6-BREAKER",  "Sustained downstream failure surfaces an error to the client (REPORT time to surface; do not assert on it)"),
            simple("OT-E6-ERRCLASS", "Client errors are distinguishable from internal errors")
        );
    }

    private static Scenario simple(String id, String intent) {
        return new Scenario() {
            @Override public String id() { return id; }
            @Override public Set<Capability> requires() { return Set.of(Capability.CLAIM_SWAP); }
            @Override public int[] run(Engine e, Connection c) {
                long t0 = System.nanoTime();
                System.out.println();
                System.out.println("[" + id + "] (OT-E6) " + intent);
                System.out.println("   verdict: SKIP (awaiting the e6data ABAC identity flow)");
                System.out.println("   elapsed: " + (System.nanoTime() - t0) / 1_000_000 + " ms");
                return new int[]{0, 0, 1, 0};
            }
        };
    }

    private OnetrustE6Scenarios() {}
}
```

- [ ] **Step 2: Compile**

```bash
cd JDBC && mvn -q package
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add JDBC/src/main/java/com/abacpoc/scenario/OnetrustE6Scenarios.java
git commit -m "feat: OnetrustE6Scenarios placeholders"
```

**All 8 scenarios are now written.** Task 21 wires everything together and closes out the plan.

---

### Task 21: Final wiring — scenarios into `runOnetrustCases`, full live verification

**Files:**
- Modify: `JDBC/src/main/java/com/abacpoc/Runner.java`

**Interfaces:**
- Consumes: all 8 `Onetrust*` scenario classes (Tasks 18-20), `OnetrustCases.all()` (now 119 cases).

**Context:** `runOnetrustCases` currently only runs the case list (Task 1). This task adds a scenario loop, mirroring `runAll`'s existing one exactly (same capability-check-then-run-then-accumulate shape), against the same `(engine, onetrustConn)` pair the case loop already uses.

- [ ] **Step 1: Add the scenario loop to `runOnetrustCases`**

Add these imports to `Runner.java` (alongside the existing ones):

```java
import com.abacpoc.scenario.OnetrustDr2HotSwap;
import com.abacpoc.scenario.OnetrustE6Scenarios;
import com.abacpoc.scenario.OnetrustSecondPrincipal;
import com.abacpoc.scenario.OnetrustSecretInvariance;
import com.abacpoc.scenario.OnetrustTokenExpiry;
import com.abacpoc.scenario.OnetrustViewPolicySwap;
```

Change `runOnetrustCases`'s body (inside `try (onetrustConn) { ... }`, after the fixture insert loop) from:

```java
            try {
                List<Case> cases = OnetrustCases.all();
                System.out.println(" " + cases.size() + " cases");
                System.out.println("================================================================");
                int[] r = runCases(engine, onetrustConn, cases);
                System.out.println();
                System.out.println("================================================================");
                System.out.println(" ONETRUST SUMMARY  ->  PASS " + r[0]
                                 + "   FAIL " + r[1] + "   SKIP " + r[2]
                                 + "   INFO " + r[3] + "   ERROR " + r[4]);
                System.out.println("================================================================");
            } finally {
```

to:

```java
            try {
                List<Case> cases = OnetrustCases.all();
                List<Scenario> scenarios = new ArrayList<>();
                // OnetrustViewPolicySwap MUST come after OnetrustDr2HotSwap: Dr2HotSwap reverts
                // dr2_row_filter to cutoff 10 when it finishes, which is exactly the baseline
                // ViewPolicySwap's first assertion expects -- same ordering constraint as TPC-DS's
                // Dr2HotSwap/ViewPolicySwap pair in runAll. Do not reorder.
                scenarios.add(new OnetrustDr2HotSwap());
                scenarios.add(new OnetrustViewPolicySwap());
                scenarios.add(new OnetrustSecretInvariance());
                scenarios.add(new OnetrustSecondPrincipal());
                scenarios.add(new OnetrustTokenExpiry());
                scenarios.addAll(OnetrustE6Scenarios.all());

                System.out.println(" " + cases.size() + " cases + " + scenarios.size() + " scenarios");
                System.out.println("================================================================");
                int[] r = runCases(engine, onetrustConn, cases);
                int pass = r[0], fail = r[1], skip = r[2], info = r[3], error = r[4];

                for (Scenario s : scenarios) {
                    Optional<Capability> missing = firstMissing(s.requires(), engine);
                    if (missing.isPresent()) {
                        System.out.println();
                        System.out.println("[" + s.id() + "] verdict: SKIP (" + engine.name() + " lacks " + missing.get() + ")");
                        skip++;
                        continue;
                    }
                    int[] sr = s.run(engine, onetrustConn);
                    pass += sr[0]; fail += sr[1]; skip += sr[2]; error += sr[3];
                }

                System.out.println();
                System.out.println("================================================================");
                System.out.println(" ONETRUST SUMMARY  ->  PASS " + pass
                                 + "   FAIL " + fail + "   SKIP " + skip
                                 + "   INFO " + info + "   ERROR " + error);
                System.out.println("================================================================");
            } finally {
```

Also add `import com.abacpoc.scenario.Scenario;` if not already present (check `Runner.java`'s existing imports — it already imports `Scenario` for `runAll`'s use, per the file read in earlier tasks, so this should already be there; only the 6 `Onetrust*` scenario imports above are new).

- [ ] **Step 2: Compile**

```bash
cd JDBC && mvn -q package
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Full live verification (operator-run)**

This is the first point where the *entire* replicated suite can be verified end to end. Prerequisites, in order:

1. All SQL files applied live, as owner, in this order: `sql_onetrust/01` through `07` (already applied per earlier sessions), then `08` through `17` (this plan's new files, Tasks 7-16), substituting `<ONETRUST_SP>` with the real OneTrust service principal application id throughout.
2. `sql_onetrust/05_seed_test_principals.sql` re-applied with Task 2's and Task 5's additions (5 real seeded identities total).
3. Environment variables set: `CLIENT_ID`/`CLIENT_SECRET`/`WORKSPACE_HOST`/`WAREHOUSE_ID` (primary TPC-DS connection), `ONETRUST_CLIENT_ID`/`ONETRUST_CLIENT_SECRET` (required), plus optionally `ONETRUST_CLIENT_SECRET_ALT`/`ONETRUST_SP2_CLIENT_ID`/`ONETRUST_SP2_CLIENT_SECRET`/`ONETRUST_ABAC_EXPIRED_TOKEN` (each SKIPs cleanly if unset, matching TPC-DS's own optional-scenario pattern).
4. `INCLUDE_ONETRUST=true`.

```bash
cd JDBC
mvn -q package
java -jar target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar
```

Expected final `ONETRUST SUMMARY` line (baseline, no optional scenario env vars set): `PASS 73 FAIL 0 SKIP 10 INFO 52 ERROR 0`. Derivation: 119 cases total; 52 are `Expect.info()` (`OT-TG2`, `OT-CL4`, plus `compatibleQueryCases()`'s 50) so they report `INFO`, not `PASS`; the remaining 67 cases report `PASS`. Scenarios add 6 more `PASS` (`OT-DR2`×3, `OT-VP`×3 — each of those scenarios' 3 internal checks counts individually, matching how `runAll`'s existing `Dr2HotSwap`/`ViewPolicySwap` scenarios already accumulate). `OT-E6-*`'s 7 placeholders are unconditionally `SKIP` regardless of env vars; `OT-SEC`/`OT-MSP`/`OT-EXP` each additionally report exactly 1 `SKIP` (not per-check) when their own optional env var is unset — 7 + 3 = 10 baseline `SKIP`. If `ONETRUST_CLIENT_SECRET_ALT`/`ONETRUST_SP2_CLIENT_ID`+`SECRET`/`ONETRUST_ABAC_EXPIRED_TOKEN` are all set instead, `OT-SEC` contributes 3 `PASS` (its 3 probes) in place of 1 `SKIP`, `OT-MSP` 1 `PASS` in place of 1 `SKIP`, `OT-EXP` up to 2 `PASS` in place of 1 `SKIP` — `SKIP` drops toward 7 and `PASS` rises correspondingly. Don't treat this exact arithmetic as gospel — the real bar is **`FAIL 0` and `ERROR 0`** (mirroring this repo's established pass bar throughout); recompute the expected `PASS`/`SKIP`/`INFO` split from whichever optional scenarios actually ran, and treat any discrepancy as a real finding to investigate, not a plan error to silently paper over.

Report back: the full `ONETRUST SUMMARY` line, and the complete text of any `FAIL`/`ERROR` verdict (not just the case id) — Task 21 is not complete until every `FAIL`/`ERROR` is either fixed or its root cause is understood and documented (e.g. a genuine, expected divergence worth recording, the same way TPC-DS's own suite recorded `TG2`'s and `UC2`'s live-observed findings back into `Cases.java`'s comments after their first live run).

- [ ] **Step 4: Commit**

```bash
git add JDBC/src/main/java/com/abacpoc/Runner.java
git commit -m "feat: wire all 8 OneTrust scenarios into runOnetrustCases"
```

---

## Self-Review

**Spec coverage:** every group in the design doc's Tier A table (ABAC/PERM/RBAC/TENANT/ORG, 23 cases) and Tier B table (EDGE/CONFLICT/META/THRESH/RLS/V/SC/TG/UC/XT/EX/CL, 38 cases) plus all 8 scenarios has a task. **Gap found during this self-review:** the task breakdown initially had no task for the EDGE group (TPC-DS's C1-C8) — fixed inline by writing Task 6b (moved to its correct reading-order position, between Task 6 and Task 7, not left here — a subagent executing tasks in order needs to encounter it there, not appended after Task 21). Tier A's 23 + Tier B's 38 (which already includes EDGE's 8 per the design doc's own table) = no arithmetic changes elsewhere in this plan; only the task breakdown was missing an explicit task, now fixed.

**Placeholder scan:** no `TBD`/`TODO` strings; every SQL/Java code block is complete, real, and directly copy-pasteable (mechanical ports carry the full transformed file, not a "port from X" abbreviation). The one item deliberately left to implementer judgment — whether `OnetrustCases.java` stays one file or splits once it holds ~18 group methods — is flagged in the File Structure section as a judgment call guided by file size, consistent with `writing-plans`' Task Right-Sizing guidance, not an unspecified requirement.

**Type consistency:** every group method returns `List<Case>`; every `Case` constructor call uses the 8-arg form `(id, group, purpose, description, claim, sql, exp, requires)` consistently from Task 3 onward (matching `Case`'s real record signature, confirmed by reading `Case.java` before this plan was written); `NEEDS_CLAIM_SWAP` (`Set.of(Capability.CLAIM_SWAP)`) is reused verbatim across every new case rather than redeclared per group.

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-27-onetrust-tpcds-suite-replication.md`. Two execution options:

**1. Subagent-Driven (recommended)** — fresh subagent per task (22 tasks: 1-21 plus the newly-added 6b), review between tasks, fast iteration. Matches the process that built Phase 1 earlier this session.

**2. Inline Execution** — execute tasks in this session using `executing-plans`, batch execution with checkpoints.

Which approach?

