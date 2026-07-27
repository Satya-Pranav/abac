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
