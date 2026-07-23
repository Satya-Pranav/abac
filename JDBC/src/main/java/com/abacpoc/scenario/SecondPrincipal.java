package com.abacpoc.scenario;

import com.abacpoc.cases.Cases;
import com.abacpoc.engine.Capability;
import com.abacpoc.engine.DatabricksEngine;
import com.abacpoc.engine.Engine;
import com.abacpoc.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/**
 * MSP: income_band is governed by income_band_dr2_policy (sql/15), which binds TO SP-A ONLY.
 * Opens a connection as a DIFFERENT service principal (SP-B, via SP2_CLIENT_ID/SP2_CLIENT_SECRET --
 * NOT the one the policy binds to) and queries income_band with NO claim injected: the policy is
 * not bound to SP-B, so get_user_context() never runs for it, and SP-B should see the table RAW --
 * ALL 20 rows, unfiltered.
 *
 * This demonstrates the principal-targeting semantic: a policy bound TO one principal does not
 * govern a different principal even when that principal has SELECT on the same table -- governance
 * completeness requires EVERY SELECT-capable principal to be in the policy's TO (or a group in it),
 * else it bypasses the filter entirely.
 *
 * Databricks-auth-specific: SKIPs cleanly if the engine is not Databricks, or if SP2_CLIENT_ID /
 * SP2_CLIENT_SECRET are not set (the expected default state on a normal run).
 */
public class SecondPrincipal implements Scenario {

    @Override public String id() { return "MSP"; }

    @Override public Set<Capability> requires() { return Set.of(Capability.CLAIM_SWAP); }

    @Override public int[] run(Engine e, Connection c) {
        if (!(e instanceof DatabricksEngine)) {
            System.out.println();
            System.out.println("[MSP] verdict: SKIP (Databricks-auth-specific; engine is " + e.name() + ")");
            return new int[]{0, 0, 1, 0};
        }

        String spClientId = System.getenv("SP2_CLIENT_ID");
        String spSecret = System.getenv("SP2_CLIENT_SECRET");
        if (spClientId == null || spClientId.isEmpty() || spSecret == null || spSecret.isEmpty()) {
            System.out.println();
            System.out.println("[MSP] verdict: SKIP (set SP2_CLIENT_ID and SP2_CLIENT_SECRET (a 2nd, DIFFERENT"
                             + " service principal, granted SELECT on income_band) to run)");
            return new int[]{0, 0, 1, 0};
        }

        int pass = 0, fail = 0, error = 0;
        final String SQL = "SELECT count(*) FROM " + e.qualify(Cases.DR2_TBL);
        System.out.println();
        System.out.println("---------------- MSP second-principal scenario (income_band, principal-targeting) ----------------");
        System.out.println(" income_band_dr2_policy (sql/15) binds TO SP-A ONLY. Query income_band as a DIFFERENT");
        System.out.println(" service principal (SP-B) with NO claim injected -- the policy should not apply to it at all,");
        System.out.println(" so SP-B should see ALL 20 rows raw (a non-TO principal is simply not governed).");

        System.out.println();
        System.out.println("[MSP1] (MSP) SP-B (not in the policy's TO set) queries income_band, no claim injected");
        System.out.println("   sql    : " + SQL);

        // Two distinct failure modes need DIFFERENT operator guidance, so connect and query are
        // caught separately: a failure opening the SESSION means SP-B cannot use the warehouse at
        // all (a workspace/warehouse-access gap); a failure on the QUERY means it can connect but
        // lacks SELECT on the table. Conflating them sends the operator to fix the wrong thing.
        Connection connB;
        try {
            connB = ((DatabricksEngine) e).connectAs(spClientId, spSecret);
        } catch (SQLException ce) {
            System.out.println("   actual : <error> " + Jdbc.shortErr(ce.getMessage()));
            System.out.println("   verdict: ERROR -- SP-B cannot open a session on this warehouse (it failed to CONNECT,"
                             + " before any query). Grant SP-B workspace access AND `Can use` on the SQL warehouse,"
                             + " then also GRANT SELECT ON TABLE " + e.qualify(Cases.DR2_TBL) + " TO <SP-B application id>.");
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
                             + " GRANT SELECT ON TABLE " + e.qualify(Cases.DR2_TBL) + " TO <SP-B application id>");
            error++;
        } finally {
            try { connB.close(); } catch (SQLException ignore) { /* best-effort */ }
        }
        return new int[]{pass, fail, 0, error};
    }
}
