# Multi-engine ABAC test suite — design

**Date:** 2026-07-20
**Status:** Approved, pending implementation plan
**Scope:** Three phases — (1) close the Databricks functional-coverage gaps, (2) make the suite
engine-pluggable so the same cases run against e6data, (3) add e6data-specific scenario cases.

---

## 1. Problem

The current suite (`JDBC/src/main/java/com/abacpoc/AbacTestSuite.java`, 651 lines) proves the
deployed 3-branch ABAC row filter on Databricks: **43 cases + a DR2 hot-swap scenario (3 checks)
= 46 checks**. Two problems:

1. **Coverage gaps.** A functional review against a policy-flow taxonomy (policy application,
   policy logic, policy selection, traditional-vs-ABAC, claims) found seven areas with no
   assertions at all — views, policy scope/hierarchy, tag-binding variants, UDF contract
   violations, cross-mechanism conflict, `EXCEPT`, and claim-shape edge cases.
2. **The suite is welded to Databricks.** Cases hardcode the `DB` catalog prefix, and identity is
   injected through `AbacJdbcClient.injectCustomClaim()` (`AbacJdbcClient.java:85-127`), which
   unwraps to `DatabricksConnection`, builds a `com.databricks.internal.sdk.core.oauth` provider,
   and calls `client.resetAccessToken()`. None of that ports.

### Explicit non-goal

**This design does not depend on how e6data implements ABAC.** That work is in flight. The suite
must exist, compile, and run against e6data; **failing against e6data today is the expected and
correct outcome.** Expectations get reclassified once the real flow lands. No e6data
implementation detail is encoded here beyond the JDBC connection surface.

---

## 2. Architecture

One Maven module (`JDBC/`), with the e6data driver added as a dependency. A second module would add
build friction without buying isolation.

```
com/abacpoc/
  engine/    Engine.java  DatabricksEngine.java  E6DataEngine.java  Capability.java
  cases/     Cases.java  Case.java  Expect.java
  scenario/  Scenario.java  Dr2HotSwap.java  E6Scenarios.java
  Runner.java
```

`AbacJdbcClient.java` stays as the standalone single-query CLI; its `injectCustomClaim` moves into
`DatabricksEngine` and the client delegates, so there is exactly one copy of that logic.

### 2.1 The Engine SPI

The only abstraction. Everything that differs between engines lives behind it:

```java
public interface Engine {
    String     name();
    Connection connect() throws SQLException;
    void       applyIdentity(Connection c, String ctxJson) throws SQLException;
    String     qualify(String table);
    boolean    supports(Capability c);
}
```

| Method | Databricks | e6data |
|---|---|---|
| `connect` | `jdbc:databricks://$HOST:443/default`, `httpPath=/sql/1.0/warehouses/$ID`, `AuthMech=11`, `Auth_Flow=1`, `OAuth2ClientId/Secret` | `jdbc:e6data://$HOST:$PORT/database=$DB&catalog=$CAT`, `user`/`password` properties |
| `applyIdentity` | OAuth `custom_claim` token hot-swap (lifted verbatim from `AbacJdbcClient`) | **Single marked seam.** Throws `UnsupportedIdentityException` until the new flow exists |
| `qualify` | `abac_tpcds.tpcds_1_delta.` + table | `$CAT.$DB.` + table, from config |
| `supports` | all capabilities true | declares what the engine can do today |

`E6DataEngine.applyIdentity` is deliberately **one method with one TODO**. When the e6data identity
flow is defined, that is the only place that changes.

### 2.2 Capabilities and the SKIP verdict

```java
enum Capability { POLICY_DDL, CLAIM_SWAP, TAGS, CLASSIC_RLS, VIEWS, SCHEMA_SCOPE }
```

A case declares what it requires. If the engine does not advertise it, the case reports **SKIP**
with a reason instead of FAIL.

This distinction is the point: against e6data we want the run to be **honestly red where behavior
genuinely diverges, and quiet where a feature simply is not built yet.** A blanket FAIL would make
both look identical and the report useless when you return to update expectations.

Verdicts become: `PASS | FAIL | SKIP | INFO | ERROR`.

### 2.3 Cases

Cases become engine-independent: `engine.qualify("reason")` replaces the hardcoded `DB` constant.

**One expected value per case, shared across engines.** Per-engine expectations were considered and
rejected: they would let a wrong e6data answer be recorded as "expected," which defeats the
comparison. A divergence must surface as FAIL and be reclassified deliberately.

The existing `Expect` framework is unchanged — `Kind { ALL, ZERO, NONZERO, EXACT, ATLEAST, IDLIST,
INFO, ERR }` with factories `all() zero() nonzero() exact(n) atLeast(n) exactIds(...) info()
errorContains(s)`.

### 2.4 Scenarios

Single-query `Case` cannot express multi-connection, timing, or failure-injection tests. `runDr2Swap`
already worked around this as a bespoke method; generalize it:

```java
public interface Scenario {
    String id();
    int[]  run(Engine e, Connection c);   // {pass, fail, skip, error}
}
```

`Dr2HotSwap` is the existing DR2 logic moved behind this interface unchanged. Phase 3's e6data cases
are all `Scenario` implementations.

---

## 3. Phase 1 — Databricks coverage gaps

New **dedicated tables** per theme so the existing 46 checks cannot regress. One script per theme,
applied as owner in the UI (the established workflow).

| Script | Group | Cases |
|---|---|---|
| `sql/16_views.sql` | **V** | View over a governed base table (expect: base filter propagates); view over an ungoverned table; change the base policy, re-query the view |
| `sql/17_policy_scope.sql` | **SC** | `ON SCHEMA` policy applies to schema members; schema-level + table-level together (**expect `UC_ABAC_MULTIPLE_ROW_FILTERS` / 42KDJ — a conflict, not a precedence winner**); a table with no policy returns ALL rows; `TO … EXCEPT` principal is exempt |
| `sql/18_tag_binding.sql` | **TG** | `has_tag_value()` matching; two columns carrying the same tag; a `MATCH COLUMNS` expression that matches no column (**expect the policy silently does not apply** — the dangerous failure mode) |
| `sql/19_udf_contract.sql` | **UC** | `USING COLUMNS` supplying fewer args than the UDF declares; declared param type vs actual column type (timestamp vs date) |
| `sql/20_cross_mechanism.sql` | **XT** | Classic `ALTER TABLE … SET ROW FILTER` **and** an ABAC policy on the *same* table — does 42KDJ fire across the two mechanisms? |

Plus group **CL** (claim shapes) — **no DDL required**, suite-only: claim missing a key, explicit
null fields, wrong JSON types. Complements the existing "missing claim entirely → hard error" case.

Each script includes a teardown block, matching `sql/15`.

---

## 4. Phase 2 — the e6data runner

Mechanical once the SPI exists:

1. Add the e6data JDBC driver dependency. If `com.e6data:e6-jdbc-driver` is not reachable from the
   configured Maven repository, install the local jar:
   `mvn install:install-file -Dfile=<path> -DgroupId=com.e6data -DartifactId=e6-jdbc-driver -Dversion=<v> -Dpackaging=jar`.
   The runbook records both paths.
2. Implement `E6DataEngine` — connection surface only, identity as the marked seam.
3. Config via environment, mirroring today's Databricks vars:
   `E6_HOST`, `E6_PORT`, `E6_CATALOG`, `E6_DATABASE`, `E6_USER`, `E6_PASSWORD`.
4. `ENGINE=databricks|e6data` selects the engine; **default `databricks`**, so the current workflow
   is byte-for-byte unchanged.

---

## 5. Phase 3 — e6data scenario cases

Written now as `Scenario` implementations, each with a real assertion and a SKIP guard. Until the
e6data ABAC flow exists these are **a specification of expected behavior** that can be handed to
whoever builds it.

> **Timing is reported, never asserted.** No case or scenario may pass or fail on a duration or
> "within N ms" window — elapsed time depends on machine load, network, and cluster warm-up, so any
> threshold is flaky and a failure tells you nothing. Every scenario prints its elapsed time; the
> pass/fail decision always rests on row counts, id lists, or error text. (This is also what makes
> the refactor's timing normalizer safe — it masks values no assertion depends on.)

| Scenario | Asserts | Also reports |
|---|---|---|
| Cross-planner session | Identity is honored across planners, not silently reused or dropped | elapsed |
| Policy caching | After a policy change, a subsequent query returns the **new result** | **how long propagation took** |
| Pooled connections | Two identities over a reused connection do not bleed into each other | elapsed |
| Token expiry | Expiry mid-flow produces a clean, categorized error — never unfiltered rows | elapsed |
| Connect retries | Recovery within a bounded **attempt count** (a count, not a duration) | elapsed |
| Circuit breaker | Sustained downstream failure surfaces an **error** to the client | **time to surface** |
| Error categorization | Client errors are distinguishable from internal errors | elapsed |

---

## 6. Reporting

```
SUMMARY -> PASS n  FAIL n  SKIP n  INFO n  ERROR n
```

Followed by a per-group breakdown, so a Databricks-vs-e6data comparison is readable at a glance.
Engine name and resolved target appear in the header.

---

## 7. Testing this work

### 7.1 Execution order — refactor first (decided)

The Engine SPI refactor lands **before** any new case is written, against the current passing 46
checks. Rationale: it yields a clean diff against a known-good baseline. Interleaving the two makes
"the refactor broke it" and "the new case is wrong" indistinguishable at exactly the moment that
distinction matters most.

Concretely:

1. Capture a baseline run of the current suite against Databricks; save the output.
2. Refactor to the Engine SPI (§2). **Acceptance: `ENGINE=databricks` output is identical to the
   captured baseline** — same 46 checks, same verdicts, same counts.
3. Only then add the Phase 1 cases and their `sql/16`–`sql/20` scripts.

### 7.2 Per-phase verification

- **Phase 2 refactor** — verified by the identical-output check in §7.1 step 2. This is the single
  most important gate in the whole plan.
- **Phase 1** — verified by running against Databricks after applying `sql/16`–`sql/20`: the 46
  baseline checks still pass, plus the new V/SC/TG/UC/XT/CL cases.
- **Phase 2 e6data binding** — `ENGINE=e6data` is expected to connect and then fail or skip. That is
  a successful outcome, not a defect.
- **Phase 3** — scenarios are expected red/skipped until the e6data flow exists.

---

## 8. Risks

| Risk | Mitigation |
|---|---|
| Refactor silently changes Databricks behavior | Resolved by §7.1: capture a baseline, refactor first against the current 46, gate on byte-identical `ENGINE=databricks` output before any new case is added |
| New Phase 1 DDL perturbs existing cases | All new objects on dedicated tables; teardown per script |
| e6data driver not in the Maven repo | Documented `install-file` fallback (§4.1) |
| Expected values for the new cases are guesses | Every Phase 1 case asserts a **deterministic** value (0, ALL, or a specific error) wherever possible, following the established preference for data-independent assertions |

---

## 9. Deferred

- The e6data **defect report** is produced separately, from findings already gathered. No further
  investigation of the e6data implementation.
- Reclassifying e6data expectations once the real ABAC flow ships.
