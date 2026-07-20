# Multi-Engine ABAC Test Suite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the ABAC JDBC suite engine-pluggable without changing its Databricks behavior, then close seven functional-coverage gaps and add e6data scenario cases.

**Architecture:** An `Engine` SPI isolates the three things that differ per engine — connection construction, identity injection, and table qualification. Cases become engine-independent data with one shared expectation. A `Scenario` interface generalizes the existing bespoke DR2 hot-swap so multi-connection/timing cases are expressible.

**Tech Stack:** Java 17, Maven, Databricks JDBC OSS driver 3.4.1, e6data JDBC driver (`io.e6.jdbc.driver.E6Driver`).

## Global Constraints

- **Java 17** (`maven.compiler.source/target` = 17, `JDBC/pom.xml`).
- **Module stays single**: all work in `JDBC/`; no new Maven module.
- **Phase 0 (Tasks 2–4) must not change program output.** Verified by diff against a captured baseline, modulo the timing normalizer in Task 1. This is the plan's most important gate.
- **`ENGINE` env var defaults to `databricks`** — the existing workflow must keep working with no new env vars.
- **Never print or commit the SP client secret.** `mask()` exists for this; keep using it.
- **Never assert on elapsed time. Measure it and print it.** No case or scenario may PASS/FAIL based
  on a duration, threshold, or "within N ms" window — durations depend on machine load, network, and
  cluster warm-up, so any threshold is flaky and its failures are uninformative. Report the number
  and let a human read it. Correctness assertions must rest on row counts, id lists, or error text.
  (This is also why the timing normalizer in Task 1 is safe: it only masks values nothing depends on.)
- **Baseline is 43 cases + 3 DR2 checks = 46.** Any change to this count before Task 5 is a bug.
- **The operator runs all Databricks verification steps** — they require `CLIENT_ID`, `CLIENT_SECRET`, `WORKSPACE_HOST`, `WAREHOUSE_ID`, which are not available to the implementing agent.
- Build: `cd JDBC && mvn -q package`. Run: `java -cp target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar com.abacpoc.Runner`.
- **Version control:** local git repo (`main`), initialized at `69d25b8`. **Each task ends with one
  commit** covering its files — the step is not written out per task; it is this standing rule.
  History is local staging only; the finished suite is destined for the regression repo.
- **Gate cadence:** Tasks 2, 3, and 4 each end in an operator-run identical-output gate.
  **Execution stops after each** until that gate passes, so a diff always names exactly one task.
  This is the entire reason the plan is refactor-first — do not batch these three.
- **Phase 1 SQL:** Tasks 7–12 write their `sql/` scripts and cases without applying them. The
  operator applies `sql/16`–`sql/20` in one sitting, then all new groups are verified together.

---

## File Structure

| File | Responsibility |
|---|---|
| `JDBC/src/main/java/com/abacpoc/engine/Capability.java` | Enum of engine features cases can require |
| `JDBC/src/main/java/com/abacpoc/engine/Engine.java` | The SPI — connect, applyIdentity, qualify, supports |
| `JDBC/src/main/java/com/abacpoc/engine/DatabricksEngine.java` | Databricks impl; owns the OAuth custom_claim hot-swap |
| `JDBC/src/main/java/com/abacpoc/engine/E6DataEngine.java` | e6data impl; connection surface + identity seam |
| `JDBC/src/main/java/com/abacpoc/cases/Expect.java` | Expected-outcome value type (moved verbatim) |
| `JDBC/src/main/java/com/abacpoc/cases/Case.java` | Case record + required capabilities |
| `JDBC/src/main/java/com/abacpoc/cases/Cases.java` | The case catalog |
| `JDBC/src/main/java/com/abacpoc/scenario/Scenario.java` | Multi-step scenario SPI |
| `JDBC/src/main/java/com/abacpoc/scenario/Dr2HotSwap.java` | DR2 hot-swap (moved verbatim) |
| `JDBC/src/main/java/com/abacpoc/scenario/E6Scenarios.java` | e6data-specific scenarios |
| `JDBC/src/main/java/com/abacpoc/Runner.java` | main(), fixture, reporting |
| `JDBC/src/main/java/com/abacpoc/AbacJdbcClient.java` | Standalone single-query CLI (delegates to DatabricksEngine) |
| `sql/16_views.sql` … `sql/20_cross_mechanism.sql` | Phase 1 DDL, one file per theme |

`AbacTestSuite.java` is deleted at the end of Task 4, once everything has moved out of it.

---

## Task 1: Capture the refactor baseline

**Files:**
- Create: `baseline/README.md`

**Interfaces:**
- Produces: `baseline/databricks-baseline.txt` — the reference output every Phase 0 task diffs against.

- [ ] **Step 1: Build the current suite unchanged**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac/JDBC
mvn -q package
```
Expected: exit 0, `target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar` present.

- [ ] **Step 2: Run the suite and capture output** *(operator — needs credentials)*

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
mkdir -p baseline
java -cp JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar \
  com.abacpoc.AbacTestSuite 2>&1 | tee baseline/databricks-baseline.txt
```
Expected final line: `SUMMARY  ->  PASS 46   FAIL 0   INFO 0   ERROR 0`

If it is not 46/0/0/0, **stop**. The baseline must be green or the gate is meaningless. Apply any missing `sql/` script (notably `sql/14`, `sql/15`) and re-run.

- [ ] **Step 3: Create the normalizer**

The DR2b line embeds elapsed milliseconds, which varies per run, and Task 13 adds an `elapsed:` line
to every scenario. Byte-identical comparison is impossible without normalizing them.

The rule matches **any number immediately followed by ` ms`**. That is deliberately scoped: durations
are the only values printed with a `ms` suffix, and no row count, id, or error code is ever followed
by ` ms` — so this cannot mask a correctness regression. Resist the temptation to broaden it further.

Create `baseline/README.md`:

````markdown
# Refactor baseline

`databricks-baseline.txt` is the reference output of the pre-refactor suite
(43 cases + 3 DR2 checks = 46). Phase 0 tasks must reproduce it exactly.

Compare with the timing normalizer applied to BOTH sides:

```bash
norm() { sed -E 's/[0-9]+ ms/N ms/g'; }
diff <(norm < baseline/databricks-baseline.txt) <(norm < /tmp/after.txt) && echo IDENTICAL
```

Regenerate the baseline only when output changes intentionally
(Task 5 adds SKIP to the summary line — that is the one sanctioned change).
````

- [ ] **Step 4: Prove the baseline is actually reproducible** *(operator — needs credentials)*

Diffing the baseline against itself would prove nothing — it passes regardless of what the
normalizer does. The check that earns its place is running the **unchanged** suite a second time and
confirming the normalized outputs match. That is what establishes that the gate in Tasks 2–4 can
distinguish "the refactor changed something" from "this run was just different."

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
java -cp JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar \
  com.abacpoc.AbacTestSuite 2>&1 > /tmp/baseline-run2.txt
norm() { sed -E 's/[0-9]+ ms/N ms/g'; }
diff <(norm < baseline/databricks-baseline.txt) <(norm < /tmp/baseline-run2.txt) && echo REPRODUCIBLE
```
Expected: `REPRODUCIBLE`

- [ ] **Step 5: If Step 4 shows differences, widen the normalizer — narrowly**

Any diff here is **additional nondeterminism**, not a bug in the suite. Find it before Task 2, or it
will masquerade as a refactor regression.

The most likely source: cases asserting on error text (`W`/`WP`/`WS`, later `SC4`/`XT1`). Databricks
error messages frequently embed a per-statement query ID, and `shortErr` truncates at 260 chars
without stripping identifiers. If you see that, add a targeted rule:

```bash
norm() {
  sed -E -e 's/[0-9]+ ms/N ms/g' \
         -e 's/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/UUID/g'
}
```

Keep every rule **narrow and specific**. Never normalize bare numbers (`s/[0-9]+/N/g`) — that would
erase the row counts, which are exactly what the gate exists to protect. A normalizer that hides a
real regression is worse than no gate at all.

Record the final `norm()` definition in `baseline/README.md` and use that same definition verbatim
in every later verification step.

---

## Task 2: Extract the Engine SPI and DatabricksEngine

**Files:**
- Create: `JDBC/src/main/java/com/abacpoc/engine/Capability.java`
- Create: `JDBC/src/main/java/com/abacpoc/engine/Engine.java`
- Create: `JDBC/src/main/java/com/abacpoc/engine/DatabricksEngine.java`
- Create: `JDBC/src/main/java/com/abacpoc/Runner.java`
- Modify: `JDBC/src/main/java/com/abacpoc/AbacTestSuite.java` (delegate, keep `main` working)
- Modify: `JDBC/pom.xml` (mainClass)

**Interfaces:**
- Produces: `Engine` with `name()`, `connect()`, `applyIdentity(Connection,String)`, `qualify(String)`, `supports(Capability)`, `printBanner()`, `connectionHelp()`; `Capability` enum; `DatabricksEngine` implementing all of it; `Runner.main`.

- [ ] **Step 1: Write `Capability.java`**

```java
package com.abacpoc.engine;

/** Engine features a case may require. A case whose requirements are not met reports SKIP. */
public enum Capability {
    POLICY_DDL,    // CREATE POLICY ... ROW FILTER
    CLAIM_SWAP,    // per-statement identity injection
    TAGS,          // governed column tags + has_tag()
    CLASSIC_RLS,   // ALTER TABLE ... SET ROW FILTER
    VIEWS,         // views over governed base tables
    SCHEMA_SCOPE   // ON SCHEMA policy scoping
}
```

- [ ] **Step 2: Write `Engine.java`**

```java
package com.abacpoc.engine;

import java.sql.Connection;
import java.sql.SQLException;

/** The only abstraction between the case catalog and a query engine. */
public interface Engine {

    /** Short name used in report headers, e.g. "databricks". */
    String name();

    /** Open a connection. Throws if configuration is missing or the engine is unreachable. */
    Connection connect() throws SQLException;

    /** Make subsequent statements on {@code c} run as the identity described by {@code ctxJson}. */
    void applyIdentity(Connection c, String ctxJson) throws SQLException;

    /** Fully-qualify an unqualified table name for this engine. */
    String qualify(String table);

    /** Whether this engine supports {@code c}. Cases requiring an unsupported capability SKIP. */
    boolean supports(Capability c);

    /** Print the engine-specific connection banner (kept engine-side to preserve exact output). */
    void printBanner();

    /** Operator hint printed when {@link #connect()} fails. */
    String connectionHelp();
}
```

- [ ] **Step 3: Write `DatabricksEngine.java`**

The body of `applyIdentity` is `AbacJdbcClient.injectCustomClaim` unchanged — delegate rather than duplicate, so there is exactly one copy.

```java
package com.abacpoc.engine;

import com.abacpoc.AbacJdbcClient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class DatabricksEngine implements Engine {

    /** Unchanged from AbacTestSuite.DB — the refactor must not move any table. */
    private static final String PREFIX = "abac_tpcds.tpcds_1_delta.";

    private final String host, warehouseId, clientId, clientSecret;

    public DatabricksEngine() {
        this.clientId     = env("CLIENT_ID");
        this.clientSecret = env("CLIENT_SECRET");
        this.warehouseId  = env("WAREHOUSE_ID");
        this.host = env("WORKSPACE_HOST").trim()
                .replaceFirst("^https?://", "")
                .replaceAll("/+$", "");
    }

    @Override public String name() { return "databricks"; }

    @Override public String qualify(String table) { return PREFIX + table; }

    @Override public boolean supports(Capability c) { return true; }

    @Override public Connection connect() throws SQLException {
        String url = "jdbc:databricks://" + host + ":443/default";
        Properties props = new Properties();
        props.put("httpPath", "/sql/1.0/warehouses/" + warehouseId);
        props.put("AuthMech", "11");
        props.put("Auth_Flow", "1");
        props.put("OAuth2ClientId", clientId);
        props.put("OAuth2Secret", clientSecret);
        return DriverManager.getConnection(url, props);
    }

    @Override public void applyIdentity(Connection c, String ctxJson) throws SQLException {
        AbacJdbcClient.injectCustomClaim(c, ctxJson);
    }

    @Override public void printBanner() {
        System.out.println("Connecting: host=" + host + "  warehouse=" + warehouseId
                         + "  clientId=" + mask(clientId));
        System.out.println("URL: jdbc:databricks://" + host + ":443/default"
                         + "   httpPath=/sql/1.0/warehouses/" + warehouseId);
    }

    @Override public String connectionHelp() {
        return "   Check: WORKSPACE_HOST is a bare host with NO trailing '/' or 'https://' (using '"
             + host + "'),\n"
             + "          WAREHOUSE_ID='" + warehouseId
             + "' is correct, and CLIENT_ID/CLIENT_SECRET are a valid OAuth M2M pair.";
    }

    /** Keep the first 8 chars of a secret/id, hide the rest. */
    public static String mask(String s) {
        if (s == null || s.length() <= 8) return "********";
        return s.substring(0, 8) + "…";
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) throw new IllegalStateException("Missing env var: " + name);
        return v;
    }
}
```

- [ ] **Step 4: Write `Runner.java`**

`Runner` owns `main` and reproduces the current `AbacTestSuite.main` output exactly, but selects the engine. It delegates the case loop to the still-existing `AbacTestSuite` methods for now; Tasks 3–4 move those.

```java
package com.abacpoc;

import com.abacpoc.engine.DatabricksEngine;
import com.abacpoc.engine.E6DataEngine;
import com.abacpoc.engine.Engine;

import java.sql.Connection;

public class Runner {

    public static Engine select() {
        String which = System.getenv().getOrDefault("ENGINE", "databricks").trim().toLowerCase();
        switch (which) {
            case "databricks": return new DatabricksEngine();
            case "e6data":     return new E6DataEngine();
            default: throw new IllegalStateException(
                "Unknown ENGINE '" + which + "' (expected 'databricks' or 'e6data')");
        }
    }

    public static void main(String[] args) throws Exception {
        Engine engine = select();
        engine.printBanner();

        Connection c;
        try {
            c = engine.connect();
        } catch (Exception e) {
            System.err.println();
            System.err.println("!! Connection FAILED before any test ran: "
                             + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.err.println(engine.connectionHelp());
            throw e;
        }

        try (c) {
            boolean seeded = AbacTestSuite.setUpFixture(engine, c);
            try {
                AbacTestSuite.runAll(engine, c, AbacTestSuite.cases(engine), seeded);
            } finally {
                if (seeded) {
                    try { AbacTestSuite.dropFixture(engine, c); System.out.println(" Fixture: dropped."); }
                    catch (Exception e) {
                        System.out.println(" Fixture: teardown FAILED, remove manually: " + e.getMessage());
                    }
                }
            }
        }
    }
}
```

> **Note on `E6DataEngine`:** Task 6 creates it. To keep this task compiling on its own, create a
> minimal placeholder now — a class implementing `Engine` whose every method throws
> `new UnsupportedOperationException("E6DataEngine: implemented in Task 6")`. Task 6 replaces the body.

- [ ] **Step 5: Thread the engine through `AbacTestSuite`**

Mechanical, and the part most likely to change behavior if done carelessly. In `AbacTestSuite.java`:

1. Delete `static final String DB`. Replace every `DB + "x"` with `e.qualify("x")`.
2. Convert `FIXTURE_INSERTS` / `FIXTURE_DELETES` from `static final String[]` into
   `static String[] fixtureInserts(Engine e)` / `fixtureDeletes(Engine e)` — they reference `DB`, so
   they cannot stay static finals.
3. Add an `Engine e` first parameter to: `cases`, `runAll`, `runDr2Swap`, `dr2Def`, `setUpFixture`,
   `dropFixture`, `totalUnderDisable`, `check`.
4. Replace every `AbacJdbcClient.injectCustomClaim(c, X)` with `e.applyIdentity(c, X)`.
5. Delete `AbacTestSuite.main` and `AbacTestSuite.mask` (now `Runner.main` / `DatabricksEngine.mask`).
6. Make the methods `Runner` calls `public static`.

Do **not** touch any `System.out.println` string in this step. Output changes are the failure mode this task exists to prevent.

- [ ] **Step 6: Point the jar's mainClass at Runner**

In `JDBC/pom.xml`, inside `maven-assembly-plugin` → `configuration` → `archive` → `manifest`:

```xml
<mainClass>com.abacpoc.Runner</mainClass>
```

- [ ] **Step 7: Build**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac/JDBC && mvn -q package
```
Expected: exit 0, no compilation errors.

- [ ] **Step 8: Verify output is identical** *(operator — needs credentials)*

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
java -cp JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar \
  com.abacpoc.Runner 2>&1 | tee /tmp/after.txt
norm() { sed -E 's/[0-9]+ ms/N ms/g'; }
diff <(norm < baseline/databricks-baseline.txt) <(norm < /tmp/after.txt) && echo IDENTICAL
```
Expected: `IDENTICAL`, and the summary still reads `PASS 46   FAIL 0   INFO 0   ERROR 0`.

If diff shows changes, **do not proceed** — fix until identical. That is the whole point of the gate.

---

## Task 3: Move the case model into `cases/`

**Files:**
- Create: `JDBC/src/main/java/com/abacpoc/cases/Expect.java`
- Create: `JDBC/src/main/java/com/abacpoc/cases/Case.java`
- Create: `JDBC/src/main/java/com/abacpoc/cases/Cases.java`
- Modify: `JDBC/src/main/java/com/abacpoc/AbacTestSuite.java`

**Interfaces:**
- Consumes: `Engine.qualify` from Task 2.
- Produces: `Expect` (public, same factories), `Case` record with `Set<Capability> requires()`, `Cases.all(Engine)` returning `List<Case>`, `Cases.claim(...)` helpers.

- [ ] **Step 1: Move `Expect` verbatim into its own public class**

Copy the `Expect` class and the `Kind` enum out of `AbacTestSuite.java` into
`cases/Expect.java`, changing only visibility: `public final class Expect`, `public enum Kind`,
all factories and `describe()` become `public`, and the fields `kind`/`n`/`text`/`ids` become
`public final`. **Do not change any behavior or any `describe()` string** — those strings are printed.

- [ ] **Step 2: Move `Case` and add capability requirements**

```java
package com.abacpoc.cases;

import com.abacpoc.engine.Capability;

import java.util.Set;

/** exp = the expected outcome under the deployed full 3-branch abac_row_filter. */
public record Case(String id, String group, String purpose, String description,
                   String claim, String sql, Expect exp, Set<Capability> requires) {

    /** Convenience for the existing cases, which all require the Databricks feature set. */
    public Case(String id, String group, String purpose, String description,
                String claim, String sql, Expect exp) {
        this(id, group, purpose, description, claim, sql, exp, Set.of());
    }
}
```

`Set.of()` — empty requirements — means "never skipped", which preserves current behavior for all 43 existing cases.

- [ ] **Step 3: Move the catalog**

Move `cases()` and both `claim(...)` overloads into `cases/Cases.java` as
`public static List<Case> all(Engine e)` and `public static String claim(...)`.
Move `DISABLE_CLAIM`, `CONFLICT_TABLE`, `THRESH_TABLE`, `DR2_TBL`, `DR2_FN`, `SUITE_ORG`,
`SUITE_EMPTY` with them as `public static final`. Update `AbacTestSuite` and `Runner` to reference
`Cases.*`. **Do not edit any case's SQL, claim, purpose, or description text.**

- [ ] **Step 4: Build**

Run: `cd JDBC && mvn -q package`
Expected: exit 0.

- [ ] **Step 5: Verify output is identical** *(operator)*

Same commands as Task 2 Step 8.
Expected: `IDENTICAL`, summary `PASS 46   FAIL 0   INFO 0   ERROR 0`.

---

## Task 4: Generalize DR2 into a Scenario

**Files:**
- Create: `JDBC/src/main/java/com/abacpoc/scenario/Scenario.java`
- Create: `JDBC/src/main/java/com/abacpoc/scenario/Dr2HotSwap.java`
- Modify: `JDBC/src/main/java/com/abacpoc/Runner.java`
- Delete: `JDBC/src/main/java/com/abacpoc/AbacTestSuite.java`

**Interfaces:**
- Produces: `Scenario` with `id()`, `requires()`, `run(Engine, Connection)` returning `int[]{pass,fail,skip,error}`; `Dr2HotSwap` implementing it; `Runner.runAll` owning the case loop and reporting.

- [ ] **Step 1: Write `Scenario.java`**

```java
package com.abacpoc.scenario;

import com.abacpoc.engine.Capability;
import com.abacpoc.engine.Engine;

import java.sql.Connection;
import java.util.Set;

/** A multi-step test that a single query + Expect cannot express (state changes, timing,
 *  multiple connections). Returns {pass, fail, skip, error}. */
public interface Scenario {
    String id();
    Set<Capability> requires();
    int[] run(Engine e, Connection c);
}
```

- [ ] **Step 2: Move DR2 verbatim into `Dr2HotSwap`**

Move `runDr2Swap`, `dr2Def`, `dr2Print`, and `sleep` into `Dr2HotSwap`. `run` returns a **4-element**
array — insert `0` for skip so the existing `{pass, fail, error}` values keep their meaning:

```java
@Override public int[] run(Engine e, Connection c) {
    int[] r = runDr2Swap(e, c);          // existing body, unchanged
    return new int[]{r[0], r[1], 0, r[2]};
}

@Override public String id() { return "DR2"; }

@Override public Set<Capability> requires() {
    return Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.CLAIM_SWAP);
}
```

Keep every `System.out.println` string byte-identical.

- [ ] **Step 3: Move the case loop and reporting into `Runner`**

Move `runAll`, `setUpFixture`, `dropFixture`, `exec`, `check`, `totalUnderDisable`, `count`,
`shortErr`, `firstColumn`, and the fixture arrays into `Runner`. Replace the hardcoded DR2 call:

```java
int[] dr2 = new Dr2HotSwap().run(e, c);
pass += dr2[0]; fail += dr2[1]; error += dr2[3];
```

The header line stays exactly:
```java
System.out.println(" ABAC JDBC test suite — " + cases.size() + " cases + DR2 hot-swap scenario (3 checks)");
```

- [ ] **Step 4: Delete `AbacTestSuite.java`**

```bash
rm JDBC/src/main/java/com/abacpoc/AbacTestSuite.java
```
It is now empty of unique content. Confirm nothing references it:

Run: `grep -rn "AbacTestSuite" JDBC/src/`
Expected: **no output**.

- [ ] **Step 5: Build**

Run: `cd JDBC && mvn -q package`
Expected: exit 0.

- [ ] **Step 6: Verify output is identical** *(operator)*

Same commands as Task 2 Step 8.
Expected: `IDENTICAL`, summary `PASS 46   FAIL 0   INFO 0   ERROR 0`.

**This is the final Phase 0 gate.** The refactor is complete and provably behavior-preserving.

---

## Task 5: Add the SKIP verdict and capability gating

**Files:**
- Modify: `JDBC/src/main/java/com/abacpoc/Runner.java`
- Modify: `baseline/README.md`

**Interfaces:**
- Consumes: `Case.requires()`, `Scenario.requires()`, `Engine.supports()`.
- Produces: a 5-counter summary line.

> **This task intentionally changes output by one line** — the only sanctioned change in the plan.
> The baseline is regenerated here.

- [ ] **Step 1: Gate cases in the loop**

At the top of the per-case body in `runAll`, before `applyIdentity`:

```java
java.util.Optional<Capability> missing = cs.requires().stream()
        .filter(cap -> !e.supports(cap)).findFirst();
if (missing.isPresent()) {
    System.out.println("   verdict: SKIP (" + e.name() + " lacks " + missing.get() + ")");
    skip++;
    continue;
}
```

Apply the same guard before running a `Scenario`.

- [ ] **Step 2: Update the summary line**

```java
System.out.println(" SUMMARY  ->  PASS " + pass
                 + "   FAIL " + fail + "   SKIP " + skip
                 + "   INFO " + info + "   ERROR " + error);
```

- [ ] **Step 3: Build**

Run: `cd JDBC && mvn -q package`
Expected: exit 0.

- [ ] **Step 4: Verify the diff is exactly one line** *(operator)*

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
java -cp JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar com.abacpoc.Runner 2>&1 | tee /tmp/after.txt
norm() { sed -E 's/[0-9]+ ms/N ms/g'; }
diff <(norm < baseline/databricks-baseline.txt) <(norm < /tmp/after.txt)
```
Expected: exactly one changed line — the SUMMARY line, now reading
`SUMMARY  ->  PASS 46   FAIL 0   SKIP 0   INFO 0   ERROR 0`.
`SKIP 0` confirms no existing case was accidentally gated.

- [ ] **Step 5: Regenerate the baseline**

```bash
cp /tmp/after.txt baseline/databricks-baseline.txt
```

This is the **only** sanctioned baseline regeneration in the plan. From here on, Tasks 6–13 diff
against this updated file — so a later unexplained diff still means something real changed.

---

## Task 6: Implement E6DataEngine (connection surface + identity seam)

**Files:**
- Modify: `JDBC/src/main/java/com/abacpoc/engine/E6DataEngine.java`
- Modify: `JDBC/pom.xml`
- Modify: `docs/deployment/runbook.md`

**Interfaces:**
- Produces: a working `E6DataEngine` whose `applyIdentity` is the single seam to fill in later.

- [ ] **Step 1: Add the driver dependency**

Add to `JDBC/pom.xml` `<dependencies>`:

```xml
<dependency>
  <groupId>com.e6data</groupId>
  <artifactId>e6-jdbc-driver</artifactId>
  <version>1.0.1</version>
</dependency>
```

If resolution fails, install the local jar and re-run:

```bash
mvn install:install-file \
  -Dfile=$HOME/Downloads/e6data-jdbc-1.1.28-1158d14.jar \
  -DgroupId=com.e6data -DartifactId=e6-jdbc-driver -Dversion=1.0.1 -Dpackaging=jar
```

- [ ] **Step 2: Implement the engine**

```java
package com.abacpoc.engine;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * e6data engine binding. Connection surface only.
 *
 * The ABAC identity flow on e6data is still being built. {@link #applyIdentity} is the ONE seam
 * that changes when it lands — nothing else in the suite should need to move.
 */
public final class E6DataEngine implements Engine {

    private final String host, port, catalog, database, user, password;

    public E6DataEngine() {
        this.host     = env("E6_HOST");
        this.port     = System.getenv().getOrDefault("E6_PORT", "443");
        this.catalog  = env("E6_CATALOG");
        this.database = env("E6_DATABASE");
        this.user     = env("E6_USER");
        this.password = env("E6_PASSWORD");
    }

    @Override public String name() { return "e6data"; }

    @Override public String qualify(String table) { return catalog + "." + database + "." + table; }

    /** Nothing ABAC-related is claimed yet. Cases requiring these report SKIP, not FAIL. */
    @Override public boolean supports(Capability c) { return false; }

    @Override public Connection connect() throws SQLException {
        String url = "jdbc:e6data://" + host + ":" + port
                   + "/database=" + database + "&catalog=" + catalog;
        Properties props = new Properties();
        props.put("user", user);
        props.put("password", password);
        return DriverManager.getConnection(url, props);
    }

    /**
     * SEAM — implement when the e6data ABAC identity flow exists.
     *
     * Throwing (rather than silently no-op'ing) is deliberate: a no-op would let cases run with
     * NO identity and quietly pass against unfiltered data, which is the single most misleading
     * outcome a governance suite can produce.
     */
    @Override public void applyIdentity(Connection c, String ctxJson) throws SQLException {
        throw new SQLException("E6DataEngine.applyIdentity is not implemented — "
            + "the e6data ABAC identity flow is not available yet. "
            + "Implement this method when it lands; nothing else needs to change.");
    }

    @Override public void printBanner() {
        System.out.println("Connecting: engine=e6data host=" + host + ":" + port
                         + "  catalog=" + catalog + "  database=" + database
                         + "  user=" + DatabricksEngine.mask(user));
        System.out.println("URL: jdbc:e6data://" + host + ":" + port
                         + "/database=" + database + "&catalog=" + catalog);
    }

    @Override public String connectionHelp() {
        return "   Check: E6_HOST/E6_PORT reachable, E6_CATALOG='" + catalog
             + "' and E6_DATABASE='" + database + "' exist, and E6_USER/E6_PASSWORD are valid.";
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) throw new IllegalStateException("Missing env var: " + name);
        return v;
    }
}
```

- [ ] **Step 3: Build**

Run: `cd JDBC && mvn -q package`
Expected: exit 0.

- [ ] **Step 4: Verify Databricks is untouched** *(operator)*

Run the diff from Task 5 Step 4.
Expected: `IDENTICAL` against the Task 5 baseline. Adding an engine must not perturb the default one.

- [ ] **Step 5: Verify e6data selection reaches the driver** *(operator)*

```bash
ENGINE=e6data E6_HOST=<host> E6_PORT=<port> E6_CATALOG=<cat> E6_DATABASE=<db> \
E6_USER=<user> E6_PASSWORD=<pw> \
java -cp JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar com.abacpoc.Runner 2>&1 | head -30
```
Expected: the e6data banner prints, then **either** a connection error **or** cases reporting
SKIP/ERROR. Both are correct Phase 2 outcomes. A run that reports `PASS` on ABAC cases would mean
`applyIdentity` was silently bypassed — investigate immediately.

- [ ] **Step 6: Document the env vars**

Add to `docs/deployment/runbook.md` a short "Running against e6data" section listing `ENGINE`,
`E6_HOST`, `E6_PORT`, `E6_CATALOG`, `E6_DATABASE`, `E6_USER`, `E6_PASSWORD`, the `install-file`
fallback from Step 1, and the note that red/skipped is the expected result today.

---

## Task 7: `sql/16_views.sql` + case group V

**Files:**
- Create: `sql/16_views.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/Cases.java`

**Interfaces:**
- Consumes: `Cases.claim(...)`, `Expect.*`, `Engine.qualify`.
- Produces: cases `V1`–`V3` in group `V`.

- [ ] **Step 1: Write `sql/16_views.sql`**

Uses `reason` (already governed by classic RLS `rls_reason`, `r_reason_sk >= 20`, from `sql/15`), so
the view test needs no new policy — only new views. Replace `<SP_APP_ID>` with the service principal.

```sql
-- sql/16_views.sql — do row filters propagate through views?
-- Prereq: sql/15 applied (rls_reason on `reason`, keeps r_reason_sk >= 20).
-- Apply as OWNER. Teardown at the bottom.

-- V1/V3: a view over a GOVERNED base table.
CREATE OR REPLACE VIEW abac_tpcds.tpcds_1_delta.v_reason_governed AS
SELECT r_reason_sk, r_reason_desc FROM abac_tpcds.tpcds_1_delta.reason;

-- V2: a view over an UNGOVERNED base table (income_band has an ABAC policy from sql/15,
-- so use ship_mode's raw twin instead: a view over a table with no filter at all).
CREATE OR REPLACE VIEW abac_tpcds.tpcds_1_delta.v_ungoverned AS
SELECT ib_income_band_sk FROM abac_tpcds.tpcds_1_delta.income_band;

GRANT SELECT ON VIEW abac_tpcds.tpcds_1_delta.v_reason_governed TO `<SP_APP_ID>`;
GRANT SELECT ON VIEW abac_tpcds.tpcds_1_delta.v_ungoverned      TO `<SP_APP_ID>`;

-- Teardown:
-- DROP VIEW IF EXISTS abac_tpcds.tpcds_1_delta.v_reason_governed;
-- DROP VIEW IF EXISTS abac_tpcds.tpcds_1_delta.v_ungoverned;
```

- [ ] **Step 2: Add the V cases to `Cases.all(Engine e)`**

```java
cs.add(new Case("V1", "V",
    "View over a governed base table inherits the base row filter",
    "reason carries classic RLS (rls_reason: r_reason_sk >= 20) from sql/15. Querying THROUGH "
  + "v_reason_governed must still exclude r_reason_sk < 20 — a view must not be a bypass.",
    DISABLE_CLAIM,
    "SELECT count(*) FROM " + e.qualify("v_reason_governed") + " WHERE r_reason_sk < 20",
    Expect.zero(),
    java.util.Set.of(Capability.CLASSIC_RLS, Capability.VIEWS)));

cs.add(new Case("V2", "V",
    "View over a table governed by an ABAC policy still filters",
    "v_ungoverned selects from income_band, which carries the DR2 ABAC has_tag() policy "
  + "(dr2_row_filter, cutoff <= 10). Through the view the same 10-of-20 restriction must hold.",
    DISABLE_CLAIM,
    "SELECT count(*) FROM " + e.qualify("v_ungoverned"),
    Expect.exact(10),
    java.util.Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.VIEWS)));

cs.add(new Case("V3", "V",
    "Aggregate through a view cannot leak filtered rows",
    "max(r_reason_sk) is unremarkable, but min() through the view must be >= 20: an aggregate "
  + "computed over filtered-out rows would reveal their existence.",
    DISABLE_CLAIM,
    "SELECT min(r_reason_sk) FROM " + e.qualify("v_reason_governed"),
    Expect.atLeast(20),
    java.util.Set.of(Capability.CLASSIC_RLS, Capability.VIEWS)));
```

- [ ] **Step 3: Build**

Run: `cd JDBC && mvn -q package`
Expected: exit 0.

- [ ] **Step 4: Apply the SQL** *(operator, as owner in the Databricks UI)*

Run `sql/16_views.sql` with `<SP_APP_ID>` substituted.
Expected: two views created, two grants succeed.

- [ ] **Step 5: Run and check group V** *(operator)*

```bash
java -cp JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar com.abacpoc.Runner 2>&1 | tee /tmp/after.txt
grep -A5 "^\[V" /tmp/after.txt
```
Expected: `V1 PASS`, `V2 PASS`, `V3 PASS`, and the summary now `PASS 49 FAIL 0 SKIP 0 INFO 0 ERROR 0`.

If V1 or V3 FAIL, **that is a real finding**, not a test bug — it would mean views bypass row
filters. Record the actual numbers in `docs/testing/jdbc-cases.md` before changing any expectation.

---

## Task 8: `sql/17_policy_scope.sql` + case group SC

**Files:**
- Create: `sql/17_policy_scope.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/Cases.java`

**Interfaces:**
- Produces: cases `SC1`–`SC4` in group `SC`.

- [ ] **Step 1: Write `sql/17_policy_scope.sql`**

```sql
-- sql/17_policy_scope.sql — policy SCOPE and PRECEDENCE.
-- Creates an isolated schema so a schema-level policy cannot touch the main test tables.
-- Apply as OWNER. Replace <SP_APP_ID>. Teardown at the bottom.

CREATE SCHEMA IF NOT EXISTS abac_tpcds.abac_scope;

-- SC1: a table inside the scoped schema, governed by a SCHEMA-level policy.
CREATE OR REPLACE TABLE abac_tpcds.abac_scope.scoped_a (id BIGINT, label STRING);
INSERT INTO abac_tpcds.abac_scope.scoped_a
  SELECT id, concat('row-', id) FROM range(1, 21);

-- SC2: a second table in the SAME schema, to prove schema scope covers all members.
CREATE OR REPLACE TABLE abac_tpcds.abac_scope.scoped_b (id BIGINT, label STRING);
INSERT INTO abac_tpcds.abac_scope.scoped_b
  SELECT id, concat('row-', id) FROM range(1, 21);

-- SC3: a table with NO policy at all — must return ALL rows.
CREATE OR REPLACE TABLE abac_tpcds.abac_scope.ungoverned (id BIGINT);
INSERT INTO abac_tpcds.abac_scope.ungoverned SELECT id FROM range(1, 21);

ALTER TABLE abac_tpcds.abac_scope.scoped_a ALTER COLUMN id SET TAGS ('abac_scope_id' = 'true');
ALTER TABLE abac_tpcds.abac_scope.scoped_b ALTER COLUMN id SET TAGS ('abac_scope_id' = 'true');

CREATE OR REPLACE FUNCTION abac_tpcds.abac_scope.scope_filter(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;

-- The SCHEMA-level policy: scoped_a and scoped_b are governed, `ungoverned` is not (no tag).
CREATE OR REPLACE POLICY scope_schema_policy
ON SCHEMA abac_tpcds.abac_scope
ROW FILTER abac_tpcds.abac_scope.scope_filter
TO `<SP_APP_ID>`
FOR TABLES
MATCH COLUMNS has_tag('abac_scope_id') AS id
USING COLUMNS (id);

GRANT USE SCHEMA ON SCHEMA abac_tpcds.abac_scope TO `<SP_APP_ID>`;
GRANT SELECT ON TABLE abac_tpcds.abac_scope.scoped_a   TO `<SP_APP_ID>`;
GRANT SELECT ON TABLE abac_tpcds.abac_scope.scoped_b   TO `<SP_APP_ID>`;
GRANT SELECT ON TABLE abac_tpcds.abac_scope.ungoverned TO `<SP_APP_ID>`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac_scope.scope_filter TO `<SP_APP_ID>`;

-- SC4: add a TABLE-level policy on top of the schema-level one, on scoped_b.
-- EXPECTATION: this is NOT a precedence contest — two row filters on one table is a CONFLICT.
-- Both CREATE POLICY statements succeed; the QUERY fails with UC_ABAC_MULTIPLE_ROW_FILTERS (42KDJ).
CREATE OR REPLACE FUNCTION abac_tpcds.abac_scope.scope_filter_tbl(id BIGINT)
RETURNS BOOLEAN RETURN id <= 5;

CREATE OR REPLACE POLICY scope_table_policy
ON TABLE abac_tpcds.abac_scope.scoped_b
ROW FILTER abac_tpcds.abac_scope.scope_filter_tbl
TO `<SP_APP_ID>`
FOR TABLES
MATCH COLUMNS has_tag('abac_scope_id') AS id
USING COLUMNS (id);

GRANT EXECUTE ON FUNCTION abac_tpcds.abac_scope.scope_filter_tbl TO `<SP_APP_ID>`;

-- Teardown:
-- DROP POLICY IF EXISTS scope_table_policy ON TABLE abac_tpcds.abac_scope.scoped_b;
-- DROP POLICY IF EXISTS scope_schema_policy ON SCHEMA abac_tpcds.abac_scope;
-- DROP SCHEMA IF EXISTS abac_tpcds.abac_scope CASCADE;
```

- [ ] **Step 2: Add the SC cases**

Note these tables live in a different schema, so they are referenced with a literal qualified name
rather than `e.qualify` (which prefixes the main schema).

```java
final String SCOPE = "abac_tpcds.abac_scope.";

cs.add(new Case("SC1", "SC",
    "ON SCHEMA policy governs a table in that schema",
    "scope_schema_policy is bound ON SCHEMA, not ON TABLE. scoped_a has the tagged id column, "
  + "so scope_filter (id <= 10) applies: 10 of 20 rows.",
    DISABLE_CLAIM,
    "SELECT count(*) FROM " + SCOPE + "scoped_a",
    Expect.exact(10),
    java.util.Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.SCHEMA_SCOPE)));

cs.add(new Case("SC2", "SC",
    "ON SCHEMA policy covers EVERY matching member, not just the first",
    "scoped_b is a second table in the same schema with the same tag. Schema scope is a search "
  + "scope, so it must be governed identically — proving scope is not per-table.",
    DISABLE_CLAIM,
    "SELECT count(*) FROM " + SCOPE + "scoped_b WHERE id > 10",
    Expect.zero(),
    java.util.Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.SCHEMA_SCOPE)));

cs.add(new Case("SC3", "SC",
    "A table with no matching tag is NOT governed — returns ALL rows",
    "`ungoverned` sits inside the policy's ON SCHEMA scope but has no abac_scope_id tag, so "
  + "MATCH COLUMNS finds nothing and the policy silently does not apply. This is the documented "
  + "dangerous case: unfiltered, not blocked.",
    DISABLE_CLAIM,
    "SELECT count(*) FROM " + SCOPE + "ungoverned",
    Expect.exact(20),
    java.util.Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.SCHEMA_SCOPE)));

cs.add(new Case("SC4", "SC",
    "Schema-level + table-level row filters CONFLICT — they do not have a precedence order",
    "scoped_b is now covered by both scope_schema_policy (ON SCHEMA) and scope_table_policy "
  + "(ON TABLE). Databricks permits at most ONE row filter per table, enforced at query time, "
  + "table-wide. Expect UC_ABAC_MULTIPLE_ROW_FILTERS (SQLSTATE 42KDJ) — NOT 'the more specific "
  + "one wins'. A planner that silently picks one would be wrong.",
    DISABLE_CLAIM,
    "SELECT count(*) FROM " + SCOPE + "scoped_b",
    Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"),
    java.util.Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.SCHEMA_SCOPE)));
```

> **SC2/SC4 interaction:** SC4 makes `scoped_b` error, so SC2 (which also queries `scoped_b`) will
> error once SC4's table policy exists. Run and verify **SC1–SC3 first**, before executing SC4's
> `CREATE POLICY scope_table_policy` block. Then apply it and verify SC4. Step 4 sequences this.

- [ ] **Step 3: Build**

Run: `cd JDBC && mvn -q package`
Expected: exit 0.

- [ ] **Step 4: Apply in two stages and verify** *(operator)*

Stage A — apply `sql/17` **up to and including the grants**, stopping before the SC4 block. Run:
```bash
java -cp JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar com.abacpoc.Runner 2>&1 | grep -A5 "^\[SC"
```
Expected: `SC1 PASS`, `SC2 PASS`, `SC3 PASS`, `SC4 FAIL` (no error yet — its policy is not applied).

Stage B — apply the SC4 block, re-run.
Expected: `SC1 PASS`, `SC3 PASS`, `SC4 PASS`, and **`SC2` now ERRORs** — which is itself the proof
of the conflict. Record SC2's post-Stage-B behavior in `docs/testing/jdbc-cases.md` and mark it
Stage-A-only.

---

## Task 9: `sql/18_tag_binding.sql` + case group TG

**Files:**
- Create: `sql/18_tag_binding.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/Cases.java`

**Interfaces:**
- Produces: cases `TG1`–`TG3` in group `TG`.

- [ ] **Step 1: Write `sql/18_tag_binding.sql`**

```sql
-- sql/18_tag_binding.sql — MATCH COLUMNS binding variants.
-- Apply as OWNER. Replace <SP_APP_ID>. Teardown at the bottom.

CREATE SCHEMA IF NOT EXISTS abac_tpcds.abac_tags;

-- TG1: has_tag_value() — match on a tag's VALUE, not just its presence.
CREATE OR REPLACE TABLE abac_tpcds.abac_tags.tagval (id BIGINT, other BIGINT);
INSERT INTO abac_tpcds.abac_tags.tagval SELECT id, id * 10 FROM range(1, 21);
ALTER TABLE abac_tpcds.abac_tags.tagval ALTER COLUMN id    SET TAGS ('abac_role' = 'filter');
ALTER TABLE abac_tpcds.abac_tags.tagval ALTER COLUMN other SET TAGS ('abac_role' = 'ignore');

CREATE OR REPLACE FUNCTION abac_tpcds.abac_tags.tag_filter(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;

-- Binds ONLY the column whose abac_role tag equals 'filter' (id), not `other`.
CREATE OR REPLACE POLICY tagval_policy
ON TABLE abac_tpcds.abac_tags.tagval
ROW FILTER abac_tpcds.abac_tags.tag_filter
TO `<SP_APP_ID>`
FOR TABLES
MATCH COLUMNS has_tag_value('abac_role', 'filter') AS id
USING COLUMNS (id);

-- TG2: TWO columns carrying the SAME tag — what does the alias bind to?
CREATE OR REPLACE TABLE abac_tpcds.abac_tags.dualtag (a BIGINT, b BIGINT);
INSERT INTO abac_tpcds.abac_tags.dualtag SELECT id, 21 - id FROM range(1, 21);
ALTER TABLE abac_tpcds.abac_tags.dualtag ALTER COLUMN a SET TAGS ('abac_dual' = 'true');
ALTER TABLE abac_tpcds.abac_tags.dualtag ALTER COLUMN b SET TAGS ('abac_dual' = 'true');

CREATE OR REPLACE POLICY dualtag_policy
ON TABLE abac_tpcds.abac_tags.dualtag
ROW FILTER abac_tpcds.abac_tags.tag_filter
TO `<SP_APP_ID>`
FOR TABLES
MATCH COLUMNS has_tag('abac_dual') AS c
USING COLUMNS (c);

-- TG3: a MATCH COLUMNS expression that matches NO column.
CREATE OR REPLACE TABLE abac_tpcds.abac_tags.notag (id BIGINT);
INSERT INTO abac_tpcds.abac_tags.notag SELECT id FROM range(1, 21);

CREATE OR REPLACE POLICY notag_policy
ON TABLE abac_tpcds.abac_tags.notag
ROW FILTER abac_tpcds.abac_tags.tag_filter
TO `<SP_APP_ID>`
FOR TABLES
MATCH COLUMNS has_tag('abac_nonexistent_tag') AS id
USING COLUMNS (id);

GRANT USE SCHEMA ON SCHEMA abac_tpcds.abac_tags TO `<SP_APP_ID>`;
GRANT SELECT ON TABLE abac_tpcds.abac_tags.tagval  TO `<SP_APP_ID>`;
GRANT SELECT ON TABLE abac_tpcds.abac_tags.dualtag TO `<SP_APP_ID>`;
GRANT SELECT ON TABLE abac_tpcds.abac_tags.notag   TO `<SP_APP_ID>`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac_tags.tag_filter TO `<SP_APP_ID>`;

-- Teardown:
-- DROP SCHEMA IF EXISTS abac_tpcds.abac_tags CASCADE;
```

- [ ] **Step 2: Add the TG cases**

```java
final String TAGS_S = "abac_tpcds.abac_tags.";

cs.add(new Case("TG1", "TG",
    "has_tag_value() binds only the column whose tag VALUE matches",
    "Both columns carry abac_role, but with different values. has_tag_value('abac_role','filter') "
  + "must bind `id` (values 1..20, filter id <= 10 -> 10 rows) and NOT `other` (values 10..200, "
  + "which under id <= 10 would yield 0 rows). A count of 10 proves the right column was bound.",
    DISABLE_CLAIM,
    "SELECT count(*) FROM " + TAGS_S + "tagval",
    Expect.exact(10),
    java.util.Set.of(Capability.POLICY_DDL, Capability.TAGS)));

cs.add(new Case("TG2", "TG",
    "Two columns sharing one tag — the alias binding is ambiguous",
    "a = 1..20 and b = 20..1 both carry abac_dual. tag_filter(c) is id <= 10, so binding `a` gives "
  + "10 rows (a in 1..10), binding `b` gives 10 rows (b in 1..10) but a DIFFERENT 10 rows. "
  + "INFO first: record which Databricks actually picks (or whether it errors), then convert to a "
  + "hard assertion — the answer is the oracle e6data must reproduce.",
    DISABLE_CLAIM,
    "SELECT a FROM " + TAGS_S + "dualtag ORDER BY a",
    Expect.info(),
    java.util.Set.of(Capability.POLICY_DDL, Capability.TAGS)));

cs.add(new Case("TG3", "TG",
    "A MATCH COLUMNS that matches nothing makes the policy SILENTLY not apply",
    "notag_policy references a tag no column carries. The policy is created successfully and the "
  + "table returns ALL 20 rows unfiltered. This is the most dangerous failure mode in the whole "
  + "model: a broken policy fail-CLOSES, but a NON-MATCHING one fails OPEN.",
    DISABLE_CLAIM,
    "SELECT count(*) FROM " + TAGS_S + "notag",
    Expect.exact(20),
    java.util.Set.of(Capability.POLICY_DDL, Capability.TAGS)));
```

- [ ] **Step 3: Build**

Run: `cd JDBC && mvn -q package`
Expected: exit 0.

- [ ] **Step 4: Apply and verify** *(operator)*

Apply `sql/18_tag_binding.sql`, then:
```bash
java -cp JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar com.abacpoc.Runner 2>&1 | grep -A5 "^\[TG"
```
Expected: `TG1 PASS`, `TG2 INFO` (record the id list printed), `TG3 PASS`.

If `has_tag_value` is rejected at policy-creation time, that is a finding — record the exact error
in `docs/testing/jdbc-cases.md` and convert TG1 to `Expect.errorContains(...)`.

- [ ] **Step 5: Convert TG2 to a hard assertion**

Take the id list printed by TG2 and replace `Expect.info()` with `Expect.exactIds(...)` listing those
ids verbatim, following the established practice (A3 was converted this way). Update the description
from "INFO first: record..." to state the observed binding. Rebuild and re-run; expect `TG2 PASS`.

---

## Task 10: `sql/19_udf_contract.sql` + case group UC

**Files:**
- Create: `sql/19_udf_contract.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/Cases.java`

**Interfaces:**
- Produces: cases `UC1`–`UC2` in group `UC`.

- [ ] **Step 1: Write `sql/19_udf_contract.sql`**

```sql
-- sql/19_udf_contract.sql — UDF signature vs USING COLUMNS contract.
-- Apply as OWNER. Replace <SP_APP_ID>.
-- NOTE: the UC1 block is EXPECTED TO FAIL at CREATE POLICY time. Record the exact error text.

CREATE SCHEMA IF NOT EXISTS abac_tpcds.abac_udf;

CREATE OR REPLACE TABLE abac_tpcds.abac_udf.arity (id BIGINT, ts TIMESTAMP);
INSERT INTO abac_tpcds.abac_udf.arity
  SELECT id, timestamp(date_add(DATE'2020-01-01', CAST(id AS INT))) FROM range(1, 21);
ALTER TABLE abac_tpcds.abac_udf.arity ALTER COLUMN id SET TAGS ('abac_udf_id' = 'true');
ALTER TABLE abac_tpcds.abac_udf.arity ALTER COLUMN ts SET TAGS ('abac_udf_ts' = 'true');

-- UC1: the UDF declares TWO params; USING COLUMNS supplies ONE.
CREATE OR REPLACE FUNCTION abac_tpcds.abac_udf.two_param(id BIGINT, extra STRING)
RETURNS BOOLEAN RETURN id <= 10;

-- EXPECTED: this statement FAILS (arity mismatch). Record the error and leave it unapplied.
-- CREATE OR REPLACE POLICY arity_policy
-- ON TABLE abac_tpcds.abac_udf.arity
-- ROW FILTER abac_tpcds.abac_udf.two_param
-- TO `<SP_APP_ID>`
-- FOR TABLES
-- MATCH COLUMNS has_tag('abac_udf_id') AS id
-- USING COLUMNS (id);

-- UC2: declared param type DATE, bound column type TIMESTAMP.
CREATE OR REPLACE FUNCTION abac_tpcds.abac_udf.date_param(d DATE)
RETURNS BOOLEAN RETURN d < DATE'2020-01-11';

CREATE OR REPLACE POLICY type_policy
ON TABLE abac_tpcds.abac_udf.arity
ROW FILTER abac_tpcds.abac_udf.date_param
TO `<SP_APP_ID>`
FOR TABLES
MATCH COLUMNS has_tag('abac_udf_ts') AS ts
USING COLUMNS (ts);

GRANT USE SCHEMA ON SCHEMA abac_tpcds.abac_udf TO `<SP_APP_ID>`;
GRANT SELECT ON TABLE abac_tpcds.abac_udf.arity TO `<SP_APP_ID>`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac_udf.date_param TO `<SP_APP_ID>`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac_udf.two_param  TO `<SP_APP_ID>`;

-- Teardown:
-- DROP SCHEMA IF EXISTS abac_tpcds.abac_udf CASCADE;
```

- [ ] **Step 2: Add the UC cases**

```java
final String UDF_S = "abac_tpcds.abac_udf.";

cs.add(new Case("UC1", "UC",
    "USING COLUMNS arity must match the UDF signature",
    "two_param declares (id BIGINT, extra STRING) but USING COLUMNS supplies only (id). Row "
  + "filters auto-supply NO argument, so all n params must be provided. Expect CREATE POLICY to "
  + "be rejected. This case asserts the SQL script's documented outcome; it queries the table to "
  + "confirm no arity_policy is in force.",
    DISABLE_CLAIM,
    "SELECT count(*) FROM " + UDF_S + "arity",
    Expect.exact(10),
    java.util.Set.of(Capability.POLICY_DDL, Capability.TAGS)));

cs.add(new Case("UC2", "UC",
    "Declared DATE param vs bound TIMESTAMP column",
    "date_param(d DATE) is bound to the TIMESTAMP column ts. Rows are 2020-01-02..2020-01-21 and "
  + "the filter keeps d < 2020-01-11, i.e. 9 rows, IF Databricks coerces timestamp->date. If it "
  + "instead rejects the binding, the query errors. INFO first, then assert the observed truth.",
    DISABLE_CLAIM,
    "SELECT count(*) FROM " + UDF_S + "arity",
    Expect.info(),
    java.util.Set.of(Capability.POLICY_DDL, Capability.TAGS)));
```

> **UC1/UC2 both query `arity`.** UC1's expectation of 10 assumes `type_policy` (UC2's, 9 rows) is
> the only policy in force — these conflict. Resolve in Step 4 by measuring, then set UC1 to the
> observed value; its real assertion is "arity_policy does not exist", carried by the SQL script.

- [ ] **Step 3: Build**

Run: `cd JDBC && mvn -q package`
Expected: exit 0.

- [ ] **Step 4: Apply and verify** *(operator)*

Apply `sql/19_udf_contract.sql`. Then **uncomment the UC1 `CREATE POLICY` block and run it alone** —
record the exact error text; re-comment it.

```bash
java -cp JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar com.abacpoc.Runner 2>&1 | grep -A5 "^\[UC"
```
Expected: UC2 prints a count (or an error). Set **both** UC1 and UC2 to that observed count via
`Expect.exact(n)` — or to `Expect.errorContains(...)` if the binding is rejected. Record the UC1
policy-creation error verbatim in `docs/testing/jdbc-cases.md`; it is the case's real payload.

---

## Task 11: `sql/20_cross_mechanism.sql` + case group XT

**Files:**
- Create: `sql/20_cross_mechanism.sql`
- Modify: `JDBC/src/main/java/com/abacpoc/cases/Cases.java`

**Interfaces:**
- Produces: case `XT1` in group `XT`.

- [ ] **Step 1: Write `sql/20_cross_mechanism.sql`**

```sql
-- sql/20_cross_mechanism.sql — classic RLS AND an ABAC policy on the SAME table.
-- Does UC_ABAC_MULTIPLE_ROW_FILTERS fire ACROSS the two attachment mechanisms,
-- or does one silently win? Apply as OWNER. Replace <SP_APP_ID>.

CREATE SCHEMA IF NOT EXISTS abac_tpcds.abac_xmech;

CREATE OR REPLACE TABLE abac_tpcds.abac_xmech.both (id BIGINT);
INSERT INTO abac_tpcds.abac_xmech.both SELECT id FROM range(1, 21);
ALTER TABLE abac_tpcds.abac_xmech.both ALTER COLUMN id SET TAGS ('abac_xmech_id' = 'true');

CREATE OR REPLACE FUNCTION abac_tpcds.abac_xmech.abac_fn(id BIGINT)
RETURNS BOOLEAN RETURN id <= 10;

CREATE OR REPLACE FUNCTION abac_tpcds.abac_xmech.classic_fn(id BIGINT)
RETURNS BOOLEAN RETURN id > 15;

-- Mechanism 1: the ABAC policy (tag-driven).
CREATE OR REPLACE POLICY xmech_policy
ON TABLE abac_tpcds.abac_xmech.both
ROW FILTER abac_tpcds.abac_xmech.abac_fn
TO `<SP_APP_ID>`
FOR TABLES
MATCH COLUMNS has_tag('abac_xmech_id') AS id
USING COLUMNS (id);

-- Mechanism 2: classic table-managed RLS, bound directly to the column.
ALTER TABLE abac_tpcds.abac_xmech.both SET ROW FILTER abac_tpcds.abac_xmech.classic_fn ON (id);

GRANT USE SCHEMA ON SCHEMA abac_tpcds.abac_xmech TO `<SP_APP_ID>`;
GRANT SELECT ON TABLE abac_tpcds.abac_xmech.both TO `<SP_APP_ID>`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac_xmech.abac_fn    TO `<SP_APP_ID>`;
GRANT EXECUTE ON FUNCTION abac_tpcds.abac_xmech.classic_fn TO `<SP_APP_ID>`;

-- Teardown:
-- ALTER TABLE abac_tpcds.abac_xmech.both DROP ROW FILTER;
-- DROP POLICY IF EXISTS xmech_policy ON TABLE abac_tpcds.abac_xmech.both;
-- DROP SCHEMA IF EXISTS abac_tpcds.abac_xmech CASCADE;
```

- [ ] **Step 2: Add the XT case**

```java
cs.add(new Case("XT1", "XT",
    "Classic SET ROW FILTER + ABAC policy on the SAME table",
    "abac_fn keeps id <= 10; classic_fn keeps id > 15. The two predicates are DISJOINT, so any "
  + "non-error result is diagnostic: 0 rows means they were ANDed, 10 means the ABAC policy won, "
  + "5 means classic won, 20 means neither applied. Expect instead the same one-row-filter-per-"
  + "table conflict Databricks raises for two ABAC policies — proving the limit is per TABLE, not "
  + "per mechanism.",
    DISABLE_CLAIM,
    "SELECT count(*) FROM abac_tpcds.abac_xmech.both",
    Expect.errorContains("UC_ABAC_MULTIPLE_ROW_FILTERS"),
    java.util.Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.CLASSIC_RLS)));
```

- [ ] **Step 3: Build**

Run: `cd JDBC && mvn -q package`
Expected: exit 0.

- [ ] **Step 4: Apply and verify** *(operator)*

Apply `sql/20_cross_mechanism.sql`, then:
```bash
java -cp JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar com.abacpoc.Runner 2>&1 | grep -A5 "^\[XT"
```
Expected: `XT1 PASS` (the conflict error).

If it returns a **count instead of an error**, that is a significant finding — the mechanisms do not
share the one-filter budget. Record the actual number and what it implies (per the description's
decode table), then change the expectation to `Expect.exact(n)` with a description stating the
observed semantics.

---

## Task 12: Case group CL — claim shapes (no DDL)

**Files:**
- Modify: `JDBC/src/main/java/com/abacpoc/cases/Cases.java`

**Interfaces:**
- Produces: cases `CL1`–`CL4` in group `CL`.

- [ ] **Step 1: Add the CL cases**

These need no new objects — they vary only the claim JSON against the existing governed `customer`
table. `from_json` yields NULL for absent/unparseable fields, and every branch comparison against
NULL is NULL (not TRUE), so the expectation throughout is 0 rows — never unfiltered data.

```java
cs.add(new Case("CL1", "CL",
    "Claim missing the `mode` key entirely",
    "from_json produces ctx.mode = NULL. Branch 1 (mode='DISABLE') is NULL, branch 3a "
  + "(mode='RBAC_ABAC') is NULL. Only 3b could fire, and this user has no assignment -> 0 rows. "
  + "The query must NOT return unfiltered data.",
    "{\"tenant\":1,\"user\":\"u.analyst1@example.com\",\"org\":\"100\",\"root\":\"Customer\",\"permissions\":[]}",
    "SELECT count(*) FROM " + e.qualify("customer"),
    Expect.zero()));

cs.add(new Case("CL2", "CL",
    "Claim with an explicit null user",
    "ctx.user = NULL, so the 3b subject match (esa.subjectID = ctx.user) is NULL for every row "
  + "and no assignment can match -> 0 rows.",
    "{\"tenant\":1,\"user\":null,\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"Customer\",\"permissions\":[]}",
    "SELECT count(*) FROM " + e.qualify("customer"),
    Expect.zero()));

cs.add(new Case("CL3", "CL",
    "Claim with `permissions` as a string instead of an array",
    "The declared struct type is ARRAY<STRING>. A scalar string is not coercible, so "
  + "ctx.permissions is NULL and array_contains(NULL, ...) is NULL -> branch 2 cannot fire -> 0.",
    "{\"tenant\":1,\"user\":\"u.analyst1@example.com\",\"org\":\"100\",\"mode\":\"ABAC\",\"root\":\"Item\",\"permissions\":\"Customer\"}",
    "SELECT count(*) FROM " + e.qualify("customer"),
    Expect.zero()));

cs.add(new Case("CL4", "CL",
    "Structurally valid but empty claim object",
    "Every field is NULL. No branch can evaluate TRUE, so the table is fully filtered -> 0 rows. "
  + "This is the fail-closed floor: an empty claim must never mean 'no restriction'.",
    "{}",
    "SELECT count(*) FROM " + e.qualify("customer"),
    Expect.zero()));
```

- [ ] **Step 2: Build**

Run: `cd JDBC && mvn -q package`
Expected: exit 0.

- [ ] **Step 3: Run and verify** *(operator)*

```bash
java -cp JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar com.abacpoc.Runner 2>&1 | grep -A5 "^\[CL"
```
Expected: `CL1 PASS`, `CL2 PASS`, `CL3 PASS`, `CL4 PASS`.

Any CL case returning a **non-zero count is a security finding** — a malformed claim would be
granting access. Record it prominently in `docs/testing/jdbc-cases.md` before touching the
expectation. A case that ERRORs instead is acceptable (fail-closed); note the message and convert to
`Expect.errorContains(...)`.

---

## Task 13: e6data scenario cases

**Files:**
- Create: `JDBC/src/main/java/com/abacpoc/scenario/E6Scenarios.java`
- Modify: `JDBC/src/main/java/com/abacpoc/Runner.java`

**Interfaces:**
- Consumes: `Scenario`, `Engine`, `Capability`.
- Produces: seven `Scenario` implementations, registered in `Runner`.

These are **expected to SKIP on Databricks and SKIP-or-fail on e6data**. They are a written
specification of required behavior, not a passing suite.

- [ ] **Step 1: Add a shared skip helper and the scenario list**

```java
package com.abacpoc.scenario;

import com.abacpoc.engine.Capability;
import com.abacpoc.engine.Engine;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

/** e6data-specific scenarios: planner topology, caching, pooling, token lifecycle, errors.
 *  All require CLAIM_SWAP, which no engine advertises until the e6data ABAC flow ships. */
public final class E6Scenarios {

    public static List<Scenario> all() {
        return List.of(
            simple("E6-PLANNER",  "Authenticate on planner A, query planner B — identity is honored, not reused or dropped"),
            simple("E6-CACHE",    "After a policy change, a subsequent query reflects it (ASSERT the new result; REPORT how long it took)"),
            simple("E6-POOL",     "Two identities over a reused connection do not bleed into each other"),
            simple("E6-EXPIRY",   "Token expiry mid-flow yields a clean categorized error, never unfiltered rows"),
            simple("E6-RETRY",    "A transient connect failure recovers within a bounded ATTEMPT COUNT (a count, not a duration)"),
            simple("E6-BREAKER",  "Sustained downstream failure surfaces an error to the client (REPORT time to surface; do not assert on it)"),
            simple("E6-ERRCLASS", "Client errors are distinguishable from internal errors")
        );
    }

    private static Scenario simple(String id, String intent) {
        return new Scenario() {
            @Override public String id() { return id; }
            @Override public Set<Capability> requires() { return Set.of(Capability.CLAIM_SWAP); }
            @Override public int[] run(Engine e, Connection c) {
                long t0 = System.nanoTime();
                System.out.println();
                System.out.println("[" + id + "] (E6) " + intent);
                System.out.println("   verdict: SKIP (awaiting the e6data ABAC identity flow)");
                System.out.println("   elapsed: " + (System.nanoTime() - t0) / 1_000_000 + " ms");
                return new int[]{0, 0, 1, 0};
            }
        };
    }

    private E6Scenarios() {}
}
```

Each `simple(...)` is a placeholder **body**, not a placeholder requirement: the id and intent are
concrete, and the capability gate is real. When `applyIdentity` is implemented, replace each body
with its assertion — the scenario list and gating do not change.

- [ ] **Step 2: Register them in `Runner`**

Where `Dr2HotSwap` is invoked, iterate all scenarios instead:

```java
java.util.List<Scenario> scenarios = new java.util.ArrayList<>();
scenarios.add(new Dr2HotSwap());
scenarios.addAll(E6Scenarios.all());

for (Scenario s : scenarios) {
    java.util.Optional<Capability> miss = s.requires().stream()
            .filter(cap -> !e.supports(cap)).findFirst();
    if (miss.isPresent()) {
        System.out.println();
        System.out.println("[" + s.id() + "] verdict: SKIP (" + e.name() + " lacks " + miss.get() + ")");
        skip++;
        continue;
    }
    int[] r = s.run(e, c);
    pass += r[0]; fail += r[1]; skip += r[2]; error += r[3];
}
```

Update the header line to reflect the scenario count:
```java
System.out.println(" ABAC JDBC test suite — " + cases.size() + " cases + "
                 + scenarios.size() + " scenarios");
```

- [ ] **Step 3: Build**

Run: `cd JDBC && mvn -q package`
Expected: exit 0.

- [ ] **Step 4: Verify on Databricks** *(operator)*

```bash
java -cp JDBC/target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar com.abacpoc.Runner 2>&1 | tail -20
```
Expected: DR2 still runs its 3 checks and passes; the seven `E6-*` scenarios each print SKIP;
`SKIP 7` appears in the summary. Case counts are unchanged from Task 12.

---

## Task 14: Update documentation

**Files:**
- Modify: `docs/testing/jdbc-cases.md`
- Modify: `docs/README.md`
- Modify: `.claude/skills/databricks-abac/SKILL.md`
- Modify: `.claude/skills/databricks-abac/references/poc-playbook.md`
- Modify: `README.md`

- [ ] **Step 1: Update the case catalog**

In `docs/testing/jdbc-cases.md`: add sections for groups **V, SC, TG, UC, XT, CL** and the **E6-***
scenarios, following the existing per-group format; add rows to the coverage table and the one-line
summary list; update the title and the expected-summary line to the final counts.

- [ ] **Step 2: Update the deploy order**

In `poc-playbook.md` §4, extend the `sql/` chain with `16` views, `17` policy scope, `18` tag
binding, `19` UDF contract, `20` cross-mechanism. Update the "43-case" phrasing to the new total.

- [ ] **Step 3: Record the new semantics in the skill**

Add to `SKILL.md`'s gotchas table any behavior the new cases established as fact — in particular the
schema-vs-table conflict outcome (SC4), the non-matching-`MATCH COLUMNS` fail-open (TG3), and the
cross-mechanism result (XT1). These are the reusable findings.

- [ ] **Step 4: Refresh stale counts repo-wide**

```bash
cd /Users/satyapranav/Desktop/PycharmProjects/abac
grep -rn "43 cases\|43-case\|PASS 46" --include='*.md' . | grep -v docs/superpowers
```
Expected after fixing: **no output**.

- [ ] **Step 5: Verify links still resolve**

```bash
grep -rno "\[[^]]*\](\([^)h][^)]*\))" --include='*.md' docs .claude README.md | head -50
```
Spot-check that new relative links point at files that exist.

---

## Self-Review Notes

**Spec coverage.** Every spec section maps to a task: §2.1 Engine SPI → Task 2; §2.2 capabilities and
SKIP → Tasks 2 and 5; §2.3 cases → Task 3; §2.4 scenarios → Task 4; §3 Phase 1 gaps (V/SC/TG/UC/XT/CL)
→ Tasks 7–12; §4 e6data runner → Task 6; §5 Phase 3 scenarios → Task 13; §6 reporting → Task 5;
§7.1 refactor-first with baseline → Tasks 1–4.

**Not under version control**, by decision — this is staging for a later merge into the regression
repo. The tarball snapshot in Global Constraints covers the one irreversible stretch (Tasks 2–4).

**Known interactions**, called out where they occur rather than left to be discovered:
- SC2 and SC4 both query `scoped_b`; SC4's policy makes SC2 error. Task 8 Step 4 stages this.
- UC1 and UC2 both query `arity`. Task 10 Step 4 resolves both to one measured value.
- TG2 and UC2 ship as `INFO` and convert to hard assertions once observed, following the A3/C6/TH3
  precedent already established in this repo.

**Deliberate design choice.** `E6DataEngine.applyIdentity` throws rather than no-ops. A silent no-op
would let ABAC cases run with no identity against unfiltered data and report PASS — the exact
false-green this suite exists to prevent.
