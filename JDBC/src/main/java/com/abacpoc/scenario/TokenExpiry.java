package com.abacpoc.scenario;

import com.abacpoc.AbacJdbcClient;
import com.abacpoc.cases.Cases;
import com.abacpoc.engine.Capability;
import com.abacpoc.engine.DatabricksEngine;
import com.abacpoc.engine.Engine;
import com.abacpoc.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

/**
 * EXP: an EXPIRED OAuth access token (carrying its own custom_claim) must be REJECTED, never honored.
 *
 * The token is supplied raw via env ABAC_EXPIRED_TOKEN and injected through
 * DatabricksEngine.connectWithAccessToken -- Auth_Flow=0 (token pass-through, NO refresh) -- so the
 * expired token is sent to the server as-is rather than silently swapped for a fresh one (which the
 * normal M2M path would do). The test is deliberately a pure-AUTH probe: `SELECT 1` touches no
 * governed table, so it isolates the authentication decision from ABAC row-filtering. That matters
 * because THIS token's embedded claim (permissions are ".view" strings that match nothing; its user
 * has no assignment) would return 0 on a governed table whether the token were accepted or not --
 * making "rejected" and "accepted-but-filtered" indistinguishable. `SELECT 1` has no such ambiguity.
 *
 * Verdicts:
 *   - `SELECT 1` returns a row  -> FAIL. The expired token AUTHENTICATED and ran a query. A security
 *                                  failure: an expired token must never be accepted, claim or not.
 *   - an AUTH-looking error     -> PASS. Fail-closed: the server rejected the expired token.
 *   - any OTHER error           -> ERROR. No data leaked, but the failure is not clearly an auth
 *                                  rejection -- likely a token/property/setup problem to investigate,
 *                                  not a validated fail-closed result.
 *
 * Databricks-auth-specific: SKIPs cleanly if the engine is not Databricks, or if ABAC_EXPIRED_TOKEN
 * is not set (the expected default state on a normal run).
 */
public class TokenExpiry implements Scenario {

    @Override public String id() { return "EXP"; }

    @Override public Set<Capability> requires() { return Set.of(); }

    @Override public int[] run(Engine e, Connection c) {
        if (!(e instanceof DatabricksEngine)) {
            System.out.println();
            System.out.println("[EXP] verdict: SKIP (Databricks-auth-specific; engine is " + e.name() + ")");
            return new int[]{0, 0, 1, 0};
        }

        String token = System.getenv("ABAC_EXPIRED_TOKEN");
        if (token == null || token.isEmpty()) {
            System.out.println();
            System.out.println("[EXP] verdict: SKIP (set ABAC_EXPIRED_TOKEN to a raw, EXPIRED OAuth access"
                             + " token to run -- an expired token is not a live credential)");
            return new int[]{0, 0, 1, 0};
        }

        int pass = 0, fail = 0, error = 0;
        System.out.println();
        System.out.println("---------------- EXP token-expiry scenario (expired bearer must fail closed) ----------------");
        System.out.println(" Injects a raw token via Auth_Flow=0 (token pass-through, NO refresh) and runs `SELECT 1`,");
        System.out.println(" a pure authentication probe. EXP0 is a CONTROL (a FRESH token must be accepted through the");
        System.out.println(" same path); EXP1 is the test (the EXPIRED token must be rejected). Both must hold.");

        // EXP0 -- CONTROL: a freshly-minted VALID token, run through the SAME Auth_Flow=0 path, must
        // AUTHENTICATE and return 1. Without this, an expired-token rejection below could be a broken
        // pass-through mechanism (e.g. a 403 that happens regardless of the token) rather than a
        // genuine expiry rejection. Same rigor as EX2 controlling EX1.
        System.out.println();
        System.out.println("[EXP0] (EXP) CONTROL: a FRESH valid token through the same pass-through path must authenticate");
        System.out.println("   sql    : SELECT 1");
        System.out.println("   expect : 1 (the pass-through path accepts a good token)");
        boolean controlOk = false;
        try {
            String fresh = AbacJdbcClient.mintCustomClaimToken(c, Cases.DISABLE_CLAIM);
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

        // EXP1 -- TEST: the EXPIRED token must be rejected. Meaningful only if EXP0 passed.
        System.out.println();
        System.out.println("[EXP1] (EXP) expired OAuth token + embedded claim, static bearer, no refresh");
        System.out.println("   sql    : SELECT 1   (isolates AUTH from ABAC filtering)");
        System.out.println("   expect : REJECTED at auth (fail-closed) -- NOT a returned row"
                         + (controlOk ? "" : "   [WARNING: EXP0 control did NOT pass -- result below is unverified]"));
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
                                 + " through the same path was accepted (EXP0), so this rejection is due to EXPIRY");
                pass++;
            } else if (authLooking) {
                System.out.println("   verdict: ERROR -- looks like an auth rejection, but EXP0 control did not pass,"
                                 + " so it cannot be attributed to expiry rather than a mechanism problem.");
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
