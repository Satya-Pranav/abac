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
