package com.abacpoc.scenario;

import com.abacpoc.cases.Cases;
import com.abacpoc.engine.Capability;
import com.abacpoc.engine.Engine;
import com.abacpoc.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/** VP: change a policy's underlying row-filter UDF, then prove the change is reflected when the
 *  table is queried THROUGH A VIEW rather than directly -- a view must not freeze a stale plan or
 *  otherwise bypass a live policy change. Reuses EXISTING objects only, creates nothing new:
 *  sql/15's swappable dr2_row_filter (SP-owned) bound via the stable dr2_wrapper by
 *  income_band_dr2_policy on income_band (20 fixed rows), and sql/16's
 *  v_income_band_governed view over that same table.
 *
 *  Modeled closely on Dr2HotSwap (same structure, printing style, and error handling): assert the
 *  baseline THROUGH THE VIEW, CREATE OR REPLACE the inner UDF to a different cutoff, wait 10s,
 *  re-assert THROUGH THE VIEW, then revert -- so the suite is re-runnable. MUST self-revert: on
 *  success or on SQLException, cutoff is restored to 10 before returning, exactly as Dr2HotSwap
 *  does for the base-table case. */
public class ViewPolicySwap implements Scenario {

    @Override public String id() { return "VP"; }

    @Override public Set<Capability> requires() {
        return Set.of(Capability.POLICY_DDL, Capability.TAGS, Capability.CLAIM_SWAP, Capability.VIEWS);
    }

    @Override public int[] run(Engine e, Connection c) {
        int[] r = runViewSwap(e, c);
        return new int[]{r[0], r[1], 0, r[2]};
    }

    /** Returns {pass,fail,error}. */
    static int[] runViewSwap(Engine e, Connection c) {
        int pass = 0, fail = 0, error = 0;
        final String VIEW = "abac_tpcds.tpcds_1_delta.v_income_band_governed";
        final String CNT = "SELECT count(*) FROM " + VIEW;
        System.out.println();
        System.out.println("---------------- VP view+policy-swap scenario (v_income_band_governed, DR2 ABAC policy through a VIEW) ----------------");
        System.out.println(" Reuses sql/15's dr2_row_filter (SP-owned, swappable) bound via dr2_wrapper by income_band_dr2_policy,");
        System.out.println(" queried through sql/16's v_income_band_governed VIEW. Change the INNER UDF, POLL until reflected THROUGH");
        System.out.println(" THE VIEW (measuring latency), then revert. Runs AFTER Dr2HotSwap, which reverted cutoff to 10, so baseline holds.");
        try {
            e.applyIdentity(c, Cases.DISABLE_CLAIM);   // dr2_wrapper calls get_user_context() -> session must carry a claim
            // VP1 — baseline through the view: original inner cutoff <= 10 -> 10 of 20 rows
            long a1 = Jdbc.count(c, CNT);
            boolean ok1 = (a1 == 10);
            vpPrint("VP1", "baseline THROUGH THE VIEW (ABAC policy; dr2_row_filter cutoff <= 10): 10 of 20 rows",
                     CNT, "10", String.valueOf(a1), ok1);
            if (ok1) pass++; else fail++;

            // change the row-filter definition: CREATE OR REPLACE the inner UDF to cutoff <= 5, then
            // POLL the VIEW query until it flips to 5 -- measuring how long the change takes to become
            // visible THROUGH the view. The value (5) is asserted; the latency is only reported.
            Jdbc.exec(c, Dr2HotSwap.dr2Def(e, 5));
            System.out.println();
            System.out.println("   [swapped dr2_row_filter -> cutoff <= 5; polling the VIEW until reflected ...]");
            long ms = Jdbc.pollUntilCount(c, CNT, 5, 30_000, 250);
            boolean ok2 = (ms >= 0);
            vpPrint("VP2", "after CREATE OR REPLACE (cutoff <= 5), THROUGH THE VIEW: 5 of 20 rows"
                             + (ms >= 0 ? "  [swap->reflected in " + ms + " ms, measured by polling]"
                                        : "  [DID NOT reflect within 30s -- change never propagated through the view]"),
                     CNT, "5", ok2 ? "5 (reached)" : "still not 5 after 30s", ok2);
            if (ok2) pass++; else fail++;

            // revert the inner UDF to its original definition
            Jdbc.exec(c, Dr2HotSwap.dr2Def(e, 10));
            long a3 = Jdbc.count(c, CNT);
            boolean ok3 = (a3 == 10);
            vpPrint("VP3", "reverted dr2_row_filter -> cutoff <= 10: visible count THROUGH THE VIEW back to 10",
                     CNT, "10", String.valueOf(a3), ok3);
            if (ok3) pass++; else fail++;
        } catch (SQLException e2) {
            System.out.println("   actual : <error> " + Jdbc.shortErr(e2.getMessage()));
            System.out.println("   verdict: ERROR (VP scenario). Ensure sql/15 and sql/16 ran and the SP OWNS dr2_row_filter"
                             + " (CREATE OR REPLACE needs ownership — see sql/15's GRANT CREATE FUNCTION fallback).");
            error++;
            try { Jdbc.exec(c, Dr2HotSwap.dr2Def(e, 10)); } catch (SQLException ignore) { /* best-effort revert */ }
        }
        return new int[]{pass, fail, error};
    }

    static void vpPrint(String id, String purpose, String sql, String expect, String actual, boolean ok) {
        System.out.println();
        System.out.println("[" + id + "] (VP) " + purpose);
        System.out.println("   sql    : " + sql);
        System.out.println("   expect : " + expect);
        System.out.println("   actual : " + actual);
        System.out.println("   verdict: " + (ok ? "PASS" : "FAIL"));
    }
}
