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
 * SEC: the same SP-A, authenticated with a SECOND OAuth secret (CLIENT_SECRET_ALT, same CLIENT_ID),
 * must reach IDENTICAL ABAC decisions to the primary connection -- the secret is an auth credential,
 * not a policy input. Opens a second connection as SP-A via DatabricksEngine.connectAs(CLIENT_ID,
 * CLIENT_SECRET_ALT), then for three representative claim/sql probes (each hitting a different
 * abac_row_filter branch), injects the SAME claim on both connections and asserts the two counts
 * are EQUAL. No specific number is asserted -- only that the primary and alt-secret connections agree.
 *
 * Databricks-auth-specific: SKIPs cleanly if the engine is not Databricks, or if CLIENT_SECRET_ALT
 * is not set (the expected default state on a normal run).
 */
public class SecretInvariance implements Scenario {

    @Override public String id() { return "SEC"; }

    @Override public Set<Capability> requires() { return Set.of(Capability.CLAIM_SWAP); }

    @Override public int[] run(Engine e, Connection c) {
        if (!(e instanceof DatabricksEngine)) {
            System.out.println();
            System.out.println("[SEC] verdict: SKIP (Databricks-auth-specific; engine is " + e.name() + ")");
            return new int[]{0, 0, 1, 0};
        }

        String clientId = System.getenv("CLIENT_ID");
        String secretAlt = System.getenv("CLIENT_SECRET_ALT");
        if (secretAlt == null || secretAlt.isEmpty()) {
            System.out.println();
            System.out.println("[SEC] verdict: SKIP (set CLIENT_SECRET_ALT (a 2nd OAuth secret for the same SP) to run)");
            return new int[]{0, 0, 1, 0};
        }

        int pass = 0, fail = 0, error = 0;
        System.out.println();
        System.out.println("---------------- SEC secret-invariance scenario (same SP-A, 2nd OAuth secret) ----------------");
        System.out.println(" Opens a SECOND connection authenticating as the SAME SP-A (CLIENT_ID unchanged) but with");
        System.out.println(" CLIENT_SECRET_ALT instead of CLIENT_SECRET. For each probe, inject the SAME claim on BOTH");
        System.out.println(" connections and assert the counts are EQUAL -- the secret must not change the ABAC decision.");

        Connection conn2 = null;
        try {
            conn2 = ((DatabricksEngine) e).connectAs(clientId, secretAlt);

            String[][] probes = {
                {"SEC1", Cases.claim("u.analyst1@example.com", "100", "ABAC", "Customer", "[]"),
                    "SELECT count(*) FROM " + e.qualify("customer"), "branch 3b per-row assignment"},
                {"SEC2", Cases.claim("u.analyst1@example.com", "100", "ABAC", "Customer", "[\"Item\",\"StoreSale\"]"),
                    "SELECT count(*) FROM " + e.qualify("item"), "branch 2 permissions"},
                {"SEC3", Cases.DISABLE_CLAIM,
                    "SELECT count(*) FROM " + e.qualify("store_sales"), "branch 1 DISABLE"}
            };

            for (String[] p : probes) {
                String pid = p[0], claim = p[1], sql = p[2], branch = p[3];

                e.applyIdentity(c, claim);
                long n1 = Jdbc.count(c, sql);

                e.applyIdentity(conn2, claim);
                long n2 = Jdbc.count(conn2, sql);

                boolean ok = (n1 == n2);
                System.out.println();
                System.out.println("[" + pid + "] (SEC) same SP-A, 2nd secret -- " + branch + ": counts must be EQUAL");
                System.out.println("   sql    : " + sql);
                System.out.println("   claim  : " + claim);
                System.out.println("   expect : primary connection == alt-secret connection");
                System.out.println("   actual : primary=" + n1 + "  alt-secret=" + n2);
                System.out.println("   verdict: " + (ok ? "PASS" : "FAIL"));
                if (ok) pass++; else fail++;
            }
        } catch (SQLException ex) {
            System.out.println("   actual : <error> " + Jdbc.shortErr(ex.getMessage()));
            System.out.println("   verdict: ERROR (SEC scenario)");
            error++;
        } finally {
            if (conn2 != null) {
                try { conn2.close(); } catch (SQLException ignore) { /* best-effort */ }
            }
        }
        return new int[]{pass, fail, 0, error};
    }
}
