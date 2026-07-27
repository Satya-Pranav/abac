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
