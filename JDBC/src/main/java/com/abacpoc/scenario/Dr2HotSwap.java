package com.abacpoc.scenario;

import com.abacpoc.cases.Cases;
import com.abacpoc.engine.Capability;
import com.abacpoc.engine.Engine;
import com.abacpoc.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/** The DR2 hot-swap scenario: the ABAC has_tag() policy on `income_band` binds the stable
 *  dr2_wrapper -> the SP-owned inner dr2_row_filter. Assert the baseline, CREATE OR REPLACE the
 *  INNER udf (the policy binding is untouched, so no "function in use" conflict), wait 10s, and
 *  re-assert the changed count; then revert so the suite is re-runnable. */
public class Dr2HotSwap implements Scenario {

    @Override public String id() { return "DR2"; }

    @Override public Set<Capability> requires() {
        return Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.CLAIM_SWAP);
    }

    @Override public int[] run(Engine e, Connection c) {
        int[] r = runDr2Swap(e, c);          // existing body, unchanged
        return new int[]{r[0], r[1], 0, r[2]};
    }

    /** Returns {pass,fail,error}. */
    static int[] runDr2Swap(Engine e, Connection c) {
        int pass = 0, fail = 0, error = 0;
        final String CNT = "SELECT count(*) FROM " + e.qualify(Cases.DR2_TBL);
        System.out.println();
        System.out.println("---------------- DR2 hot-swap scenario (income_band, ABAC has_tag() policy) ----------------");
        System.out.println(" Policy binds dr2_wrapper -> dr2_row_filter (SP-owned, swappable). Change the INNER UDF,");
        System.out.println(" POLL until the change is reflected (measuring real propagation latency), then revert.");
        System.out.println(" Tables/UDFs come from sql/15.");
        Thread guard = null;
        try {
            e.applyIdentity(c, Cases.DISABLE_CLAIM);   // dr2_wrapper calls get_user_context() -> session must carry a claim
            // DR2a — baseline: original inner cutoff <= 10 -> 10 of 20 rows
            long a1 = Jdbc.count(c, CNT);
            boolean ok1 = (a1 == 10);
            dr2Print("DR2a", "baseline (ABAC policy; dr2_row_filter cutoff <= 10): 10 of 20 rows",
                     CNT, "10", String.valueOf(a1), ok1);
            if (ok1) pass++; else fail++;

            // From here until the revert, the filter is in the SWAPPED state. Register a JVM
            // shutdown hook that reverts to cutoff 10, so a process KILL (SIGTERM from a timeout /
            // Ctrl-C) between the swap and the revert does not leak the swapped state into the next
            // run (the catch below only covers SQL errors, not process termination). SIGKILL still
            // can't be caught, but SIGTERM -- the common case -- is handled.
            guard = registerRevertGuard(e, c);

            // change the row-filter definition: CREATE OR REPLACE the inner UDF to cutoff <= 5,
            // then POLL until the visible count flips to 5 -- measuring the actual propagation delay
            // rather than waiting a fixed 10s. The value (5) is asserted; the latency is only reported.
            Jdbc.exec(c, dr2Def(e, 5));
            System.out.println();
            System.out.println("   [swapped dr2_row_filter -> cutoff <= 5; polling until reflected ...]");
            long ms = Jdbc.pollUntilCount(c, CNT, 5, 30_000, 250);
            boolean ok2 = (ms >= 0);
            dr2Print("DR2b", "after CREATE OR REPLACE (cutoff <= 5): 5 of 20 rows"
                             + (ms >= 0 ? "  [swap->reflected in " + ms + " ms, measured by polling]"
                                        : "  [DID NOT reflect within 30s -- change never propagated]"),
                     CNT, "5", ok2 ? "5 (reached)" : "still not 5 after 30s", ok2);
            if (ok2) pass++; else fail++;

            // revert the inner UDF to its original definition
            Jdbc.exec(c, dr2Def(e, 10));
            long a3 = Jdbc.count(c, CNT);
            boolean ok3 = (a3 == 10);
            dr2Print("DR2c", "reverted dr2_row_filter -> cutoff <= 10: visible count back to 10",
                     CNT, "10", String.valueOf(a3), ok3);
            if (ok3) pass++; else fail++;
        } catch (SQLException e2) {
            System.out.println("   actual : <error> " + Jdbc.shortErr(e2.getMessage()));
            System.out.println("   verdict: ERROR (DR2 scenario). Ensure sql/15 ran and the SP OWNS dr2_row_filter"
                             + " (CREATE OR REPLACE needs ownership — see sql/15's GRANT CREATE FUNCTION fallback).");
            error++;
            try { Jdbc.exec(c, dr2Def(e, 10)); } catch (SQLException ignore) { /* best-effort revert */ }
        } finally {
            removeGuard(guard);   // normal path reverted already; drop the hook so it can't double-fire
        }
        return new int[]{pass, fail, error};
    }

    /** Register a JVM shutdown hook that best-effort reverts dr2_row_filter to cutoff 10, guarding the
     *  window between swap and revert against process termination (SIGTERM/Ctrl-C). Returns the hook
     *  so it can be removed on the normal path. Shared by DR2 and ViewPolicySwap (both swap the same
     *  UDF). SIGKILL cannot be caught — that residual case is documented and self-heals on the next
     *  completed run (whose revert restores cutoff 10). */
    static Thread registerRevertGuard(Engine e, Connection c) {
        Thread hook = new Thread(() -> {
            try { Jdbc.exec(c, dr2Def(e, 10)); } catch (Throwable ignore) { /* best-effort during shutdown */ }
        }, "dr2-revert-guard");
        try { Runtime.getRuntime().addShutdownHook(hook); } catch (IllegalStateException alreadyShuttingDown) { return null; }
        return hook;
    }

    static void removeGuard(Thread hook) {
        if (hook == null) return;
        try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignore) { /* shutdown in progress */ }
    }

    /** CREATE OR REPLACE for the swappable inner DR2 filter — identical signature to sql/15, only the
     *  cutoff changes (so the wrapper's call keeps resolving and the policy binding is never touched). */
    static String dr2Def(Engine e, int cutoff) {
        return "CREATE OR REPLACE FUNCTION " + e.qualify(Cases.DR2_FN)
             + "(entity_id STRING, object_type STRING, org_id STRING,"
             + " ctx STRUCT<tenant:INT,user:STRING,org:STRING,mode:STRING,root:STRING,permissions:ARRAY<STRING>>)"
             + " RETURNS BOOLEAN RETURN try_cast(entity_id AS BIGINT) <= " + cutoff;
    }

    static void dr2Print(String id, String purpose, String sql, String expect, String actual, boolean ok) {
        System.out.println();
        System.out.println("[" + id + "] (DR2) " + purpose);
        System.out.println("   sql    : " + sql);
        System.out.println("   expect : " + expect);
        System.out.println("   actual : " + actual);
        System.out.println("   verdict: " + (ok ? "PASS" : "FAIL"));
    }
}
