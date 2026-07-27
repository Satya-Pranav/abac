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
