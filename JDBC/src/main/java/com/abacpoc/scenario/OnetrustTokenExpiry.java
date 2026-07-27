package com.abacpoc.scenario;

import com.abacpoc.AbacJdbcClient;
import com.abacpoc.engine.Capability;
import com.abacpoc.engine.DatabricksEngine;
import com.abacpoc.engine.Engine;
import com.abacpoc.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/** OneTrust duplicate of TokenExpiry (EXP): an EXPIRED OAuth access token must be REJECTED, never
 *  honored. Uses ONETRUST_ABAC_EXPIRED_TOKEN (distinct from TPC-DS's ABAC_EXPIRED_TOKEN). Same
 *  pure-AUTH probe (SELECT 1) and EXP0 fresh-token control as the TPC-DS original. */
public class OnetrustTokenExpiry implements Scenario {

    @Override public String id() { return "OT-EXP"; }

    @Override public Set<Capability> requires() { return Set.of(); }

    @Override public int[] run(Engine e, Connection c) {
        if (!(e instanceof DatabricksEngine)) {
            System.out.println();
            System.out.println("[OT-EXP] verdict: SKIP (Databricks-auth-specific; engine is " + e.name() + ")");
            return new int[]{0, 0, 1, 0};
        }

        String token = System.getenv("ONETRUST_ABAC_EXPIRED_TOKEN");
        if (token == null || token.isEmpty()) {
            System.out.println();
            System.out.println("[OT-EXP] verdict: SKIP (set ONETRUST_ABAC_EXPIRED_TOKEN to a raw, EXPIRED OAuth"
                             + " access token minted for the OneTrust SP to run)");
            return new int[]{0, 0, 1, 0};
        }

        int pass = 0, fail = 0, error = 0;
        System.out.println();
        System.out.println("---------------- OT-EXP token-expiry scenario (expired bearer must fail closed) ----------------");
        System.out.println(" Injects a raw token via Auth_Flow=0 (token pass-through, NO refresh) and runs `SELECT 1`,");
        System.out.println(" a pure authentication probe. OT-EXP0 is a CONTROL; OT-EXP1 is the test.");

        System.out.println();
        System.out.println("[OT-EXP0] (OT-EXP) CONTROL: a FRESH valid token through the same pass-through path must authenticate");
        System.out.println("   sql    : SELECT 1");
        System.out.println("   expect : 1 (the pass-through path accepts a good token)");
        boolean controlOk = false;
        try {
            String fresh = AbacJdbcClient.mintCustomClaimToken(c,
                "{\"tenant\":1,\"user\":\"probe\",\"org\":\"100\",\"mode\":\"DISABLE\",\"root\":\"ASSESSMENT\",\"permissions\":[]}");
            try (Connection ctrl = ((DatabricksEngine) e).connectWithAccessToken(fresh)) {
                long n = Jdbc.count(ctrl, "SELECT 1");
                controlOk = (n == 1);
                System.out.println("   actual : " + n);
                System.out.println("   verdict: " + (controlOk ? "PASS" : "FAIL"));
                if (controlOk) pass++; else fail++;
            }
        } catch (SQLException ce) {
            System.out.println("   actual : <error> " + Jdbc.shortErr(ce.getMessage()));
            System.out.println("   verdict: ERROR -- the pass-through mechanism itself failed on a FRESH token;"
                             + " the expiry result below cannot be trusted until this is fixed.");
            error++;
        }

        System.out.println();
        System.out.println("[OT-EXP1] (OT-EXP) expired OAuth token + embedded claim, static bearer, no refresh");
        System.out.println("   sql    : SELECT 1   (isolates AUTH from ABAC filtering)");
        System.out.println("   expect : REJECTED at auth (fail-closed) -- NOT a returned row"
                         + (controlOk ? "" : "   [WARNING: OT-EXP0 control did NOT pass -- result below is unverified]"));
        Connection conn = null;
        try {
            conn = ((DatabricksEngine) e).connectWithAccessToken(token);
            long n = Jdbc.count(conn, "SELECT 1");
            System.out.println("   actual : returned " + n + " -- the expired token AUTHENTICATED");
            System.out.println("   verdict: FAIL -- SECURITY: an expired token was accepted and ran a query."
                             + " Expired tokens must be rejected regardless of the claim they carry.");
            fail++;
        } catch (SQLException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            boolean authLooking = msg.matches("(?s).*(token|expir|401|403|unauthor|authenticat|"
                                            + "credential|invalid|denied|forbidden|oauth).*");
            System.out.println("   actual : <error> " + Jdbc.shortErr(ex.getMessage()));
            if (authLooking && controlOk) {
                System.out.println("   verdict: PASS -- expired token REJECTED at auth (fail-closed); a fresh token"
                                 + " through the same path was accepted (OT-EXP0), so this rejection is due to EXPIRY");
                pass++;
            } else if (authLooking) {
                System.out.println("   verdict: ERROR -- looks like an auth rejection, but OT-EXP0 control did not"
                                 + " pass, so it cannot be attributed to expiry rather than a mechanism problem.");
                error++;
            } else {
                System.out.println("   verdict: ERROR -- no data returned, but the error is not clearly an auth"
                                 + " rejection. Verify the token and the Auth_Flow=0 / Auth_AccessToken path.");
                error++;
            }
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (SQLException ignore) { /* best-effort */ }
            }
        }
        return new int[]{pass, fail, 0, error};
    }
}
