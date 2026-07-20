# e6data — findings from a source read (for the engine team)

**Date:** 2026-07-20
**Repos read:** `e6-query-engine` (`main`, at `77c575c4`), `e6-jdbc-driver` (at `61f6ab0`)
**Context:** gathered while scoping an ABAC test suite. Investigation has since stopped — the new
ABAC flow is in flight and these findings predate it.

> **Confidence caveat — read before filing.** These come from reading source, not from running the
> system. Every file:line below should be confirmed against current `main` before it becomes a
> ticket. Items marked **[documented in-repo]** are corroborated by comments or tests already in the
> codebase and are the most reliable. Some may already be known, fixed, or intentional.

---

## S1 — Silent fail-open (security / correctness)

These are grouped first because they share a failure mode: **the query returns unfiltered data and
nothing reports an error.** For a governance system that is the worst available outcome — worse than
crashing, because it looks like success.

### S1-1. An empty session id bypasses governance entirely

`components/planner/src/main/java/io/e6x/sql/QueryOptimizer.java:1774-1775`

```java
if ((Env.ENABLE_RANGER_AUTH || Env.ENABLE_CATALOG_LEVEL_GOVERNANCE)
    && !Objects.equals(sSessionId, ""))
```

An empty `sSessionId` skips row filters and column masks. **Impact:** any caller that reaches the
planner without a populated session id reads raw data. Note the asymmetry with the rest of the
method, which fails *closed* — a policy-fetch error throws `AuthorizationException` (`:1788-1792`).
Absence of a session is treated as "no governance," presence of an error as "deny."

**Repro sketch:** invoke the planner with `sSessionId = ""` on a table carrying a Ranger row filter;
compare row count against the same query with a valid session.

### S1-2. Governance is not enforced over the JDBC path **[documented in-repo]**

`tests/regression-test/src/main/java/io/e6/common/utility/AuthSessionUtils.java:19-42` (class
javadoc) and `.../JDBCClientUtils.java:60-66`

The javadoc states that JDBC connections are accepted but `sSessionId` is not populated, which
triggers S1-1 — filters are never applied. It cites a concrete symptom: `expected=2 actual=42`.

`JDBCClientUtils.java:60-66` records a **failed fix attempt**: passing an exchanged JWT/sessionId was
tried in commit `647590f0d`, and every connection was rejected with *"Access denied for method:
run"* — the sessionId from `/api/v1/authenticate` is HTTP-only and the JDBC wire protocol does not
accept it. The comment concludes the real fix *"needs to happen elsewhere (probably planner-side)."*

**Impact:** any governance test or workload driven over JDBC silently passes on unfiltered data.
This is also why the ABAC suite cannot validate e6data over JDBC today.

### S1-3. The OPA authorizer always returns "no policy," yielding an unfiltered plan

`shared/auth-interface/src/main/java/io/e6x/QueryAccessAuthorizer.java:16-24` —
`getRowFilters`/`getColumnMasks` are `default` methods returning `Collections.emptyMap()`.
`shared/auth-interface/src/main/java/io/e6x/opa/authorizer/OpaQueryAccessAuthorizer.java:434-462`
(row filters) and `:464-524` (masks) — **both overrides are commented out**, marked
`// TODO keeping commented for next stage`. Client plumbing is likewise disabled
(`OpaHighLevelClient.java:31-32`, `:106-116`, `:123-132`), though `opaRowFiltersUri` /
`opaColumnMaskingUri` are still assigned at `:47-48` and never read.

**Impact:** with `AuthorizerType.OPA` selected, row filters and column masks are silently inactive —
config appears present, enforcement is absent. A default that returns "no restrictions" makes
*not implementing* the safe-looking path; a fail-closed default would surface this immediately.

Incidentally, the commented-out OPA code calls `rangerBasePlugin.evalRowFilterPolicies` — it is a
copy of the Ranger implementation and was never written against OPA.

---

## S2 — Correctness

### S2-1. Multiple filter/mask items per policy are silently dropped

`tests/regression-test/src/main/java/io/e6/common/utility/GovernanceTransformer.java:176` (masks)
and `:204` (row filters) — only `.get(0)` is read. The live Python port
(`tests/regression-test/scripts/transform_policies.py`) has the same behavior.

**Impact:** a Ranger policy with several mask or filter items loses all but the first, with no
warning. Worth contrasting with Unity Catalog, which raises `UC_ABAC_MULTIPLE_ROW_FILTERS`
(SQLSTATE 42KDJ) on the analogous condition. The same collapse exists structurally in the SPI, where
`Map<String,String>` permits exactly one filter per table and a second simply overwrites.

### S2-2. Subquery row filters do not work **[documented in-repo]**

`components/planner/src/test/java/io/e6x/assertplans/RangerTest.java:220-224` —
`testRowFilter09` is `@Disabled("failing due to condition is inner query")`. A second gap is TODO'd
at `:233-234`.

**Impact:** a row-filter expression containing a subquery fails. Relevant because the ABAC filter
this POC replicates is built on an `EXISTS` subquery over assignment tables — that shape is
currently unsupported.

### S2-3. `ClusterStateHandler` is a singleton keyed on nothing

`e6-jdbc-driver/src/main/java/io/e6/jdbc/...ClusterStateHandler.java:72-81` — `getInstance` is an
unsynchronized singleton with no key. The first host/port to construct it wins; **later connections
to different hosts silently reuse that instance.**

**Impact:** a process holding connections to two clusters shares one cluster-state handler, so
resume/suspend/strategy decisions for cluster A apply to cluster B. Also a data race under
concurrent first-construction.

Related: `ClusterStrategyHolderImpl.java:10-59` holds blue/green strategy in a **process-wide**
`AtomicReference`, so the same cross-cluster bleed applies to strategy selection.

---

## S3 — Availability / robustness

### S3-1. `identifyPlanner()` busy-waits with no iteration cap

`e6-jdbc-driver/.../E6GrpcClient.java:915-973` — on `WAITING_ON_PLANNER_SCALEUP` it sleeps 10ms and
re-polls, accumulating elapsed time but **never bounding iterations or total wait**. Exits only on
`GO_AHEAD`; throws on `RATE_LIMIT`.

**Impact:** a planner stuck scaling up hangs the calling thread indefinitely. A 10ms poll also makes
this a hot loop.

### S3-2. Blue/green failover keys on a substring match of an exception message

`e6-jdbc-driver/.../Utility.java:43-44`, used at `E6GrpcClient.java:366-394` and `:866-894` —
status 456 ("unknown strategy") is detected by substring-matching the exception message.

**Impact:** any change to server-side error text silently disables failover. Related:
`extractNewStrategySafely` (`:997-1018`) reads `new_strategy` **reflectively and swallows all
errors**, so a shape change there also fails silently.

### S3-3. Failover logic has zero test coverage **[documented in-repo]**

`e6-jdbc-driver/src/test/java/io/e6/cluster/ClusterStateHandlerTest.java` — **every `@Test` is
commented out.** Combined with S2-3, S3-1, and S3-2, the entire blue/green + auto-resume path is
untested.

### S3-4. JWT sessions cannot be revoked **[documented in-repo]**

`shared/auth-interface/src/main/java/io/e6x/session/SessionManager.java:559` — `invalidate()` is a
no-op, commented *"Presently we are not revoking tokens."* `extend()` at `:565` is likewise a no-op.
Default token lifetime is 300 minutes (`Env.java:71`).

**Impact:** a compromised or de-provisioned identity stays valid for up to five hours with no
revocation path.

---

## S4 — Efficiency and hygiene

| # | Finding | Location | Impact |
|---|---|---|---|
| S4-1 | **No connection pooling — the opposite.** In the default user/password path, each `createStatement()` builds a **new gRPC channel and re-authenticates**; the resulting session id is never propagated back to `E6Connection` | `E6Statement.java:56-60`; also `E6DatabaseMetadata.java:853-858, :948-952, :1013-1017, :1420-1424` | A full auth round trip + TLS handshake per statement |
| S4-2 | `E6Connection.close()` flips a boolean and **does not close gRPC channels** | `E6Connection.java:169-186` | Channel/FD leak, compounding S4-1 |
| S4-3 | `CURRENT_USER_FOR_IMPERSONATION` is `System.out.println`'d in cleartext, twice | `E6GrpcClient.java:340, :344` | Identity in stdout logs |
| S4-4 | `cluster-uuid` URL parameter is **silently ignored** — the constant `CLUSTER_UUID_ATTRIBUTE_NAME` has the value `"cluster-name"`, and there is no unknown-parameter validation | `E6URI.java:29`, `:117-124` | Typos and stale params fail silently; a repo test URL uses `cluster-uuid=` and is a no-op |
| S4-5 | `connectionTimeout` / `queryTimeout` are cast to `(Number)`, so `Properties.setProperty` (String) throws `ClassCastException` | `UserProperties.java:43, :49` | Surprising failure for standard `Properties` usage |
| S4-6 | `setClientInfo` is an empty stub — *"No need to implement"*; `getClientInfo` returns null | `E6Connection.java:413-422` | The standard JDBC channel for per-connection context is unavailable |
| S4-7 | Java and Python policy transformers emit **different keys** — `"database"` vs `"schema"` | `GovernanceTransformer.java:233` vs `transform_policies.py:44` | Divergent output; the Java path is dead code, so the Python behavior is authoritative |
| S4-8 | Row filter strings are concatenated into SQL and re-parsed with a **hardcoded dialect `"hive"` and schema `"tpcds_1000"`** | `QueryOptimizer.java:1888` | Filters are re-parsed in a fixed context regardless of the actual catalog/dialect |
| S4-9 | Column masking is **VARCHAR-only**; other types throw | `QueryOptimizer.java:1969-1971` | Numeric/date columns cannot be masked |
| S4-10 | `isUserAuthorizedForQueryExecution` catches `Throwable` with an empty body marked `// TODO log me` | `OpaQueryAccessAuthorizer.java:336-339` | Fails closed (good) but diagnostics are lost |

---

## Suggested triage order

1. **S1-1 / S1-2 together** — one fix (populate the session id on the JDBC path, or fail closed on an
   empty one) addresses both, and closes the silent-unfiltered-read hole.
2. **S1-3** — either implement the OPA overrides or make the SPI defaults fail closed. A default that
   means "no restrictions" is the wrong default for a governance interface.
3. **S2-3 + S3-1/2/3** — the cluster/failover cluster of issues, all in untested code.
4. **S2-1 / S2-2** — correctness gaps that will matter directly for ABAC parity.
5. **S4** — as capacity allows; S4-1 and S4-2 have the clearest performance payoff.
