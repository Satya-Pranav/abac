package com.abacpoc;

import com.abacpoc.cases.Case;
import com.abacpoc.cases.Cases;
import com.abacpoc.cases.Expect;
import com.abacpoc.cases.Expect.Kind;
import com.abacpoc.engine.Engine;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Final ABAC test suite — runs EVERY case from JDBC_CASES.md through the customer's real
 * OAuth token hot-swap mechanism (AbacJdbcClient.injectCustomClaim re-mints a token carrying
 * a fresh custom_claim per case), and logs for each case: what it probes, the claim, the SQL,
 * the expected result, the actual result, and a PASS/FAIL verdict.
 *
 * It targets the full 3-branch abac_row_filter (sql/05_dataset_udfs.sql = the customer's
 * create_row_filter.sql verbatim): branch 1 DISABLE, branch 2 the non-root permissions check,
 * branch 3 the root check (RBAC_ABAC org-subtree OR per-row EXISTS assignment; additive).
 * Each case carries a single expected result.
 *
 * Build:  cd JDBC && mvn -q package
 * Run:    java -cp target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar com.abacpoc.AbacTestSuite
 * Env:    CLIENT_ID, CLIENT_SECRET, WORKSPACE_HOST (no trailing /), WAREHOUSE_ID
 *         (same as AbacJdbcClient — the SP the policies are bound TO).
 */
public class AbacTestSuite {

    // ---- Self-seeding fixture (namespaced; inserted at start, dropped at end) ----
    // Uses REAL entity ids (2012/3006/118144) + REAL dummy emails, but via suite-only assignment
    // ids ('suite_a_*') and a suite-only org parent ('SUITE_ORG'), so teardown removes ONLY the
    // suite's rows and never the real seed. If the row already exists in the real seed, the extra
    // suite row is harmless (EXISTS just needs >=1). Requires the SP to have MODIFY on the metadata
    // tables (see sql/09); if it doesn't, the suite skips seeding and uses whatever is already there.

    static String[] fixtureInserts(Engine e) {
        return new String[] {
            "INSERT INTO " + e.qualify("ABAC_Assignment") + " VALUES "
                + "('suite_a_customer',true,false),('suite_a_item',true,false),('suite_a_sales',true,false)",
            "INSERT INTO " + e.qualify("ABAC_EntitySubjectAssignment") + " VALUES "
                + "('2012','Customer','suite_a_customer','USER_ID','u.analyst1@example.com',false),"
                + "('3006','Item','suite_a_item','USER_ID','u.vendor.mgr@example.com',false),"
                + "('118144','StoreSale','suite_a_sales','USER_ID','u.developer@example.com',false)",
            "INSERT INTO " + e.qualify("orgHierarchy") + " VALUES ('" + Cases.SUITE_ORG + "','" + Cases.SUITE_ORG + "',false)",
            "INSERT INTO " + e.qualify("orgHierarchy") + " SELECT DISTINCT CAST(c_current_addr_sk AS STRING),'"
                + Cases.SUITE_ORG + "',false FROM " + e.qualify("customer") + " WHERE c_current_addr_sk IS NOT NULL ORDER BY 1 LIMIT 5"
        };
    }

    static String[] fixtureDeletes(Engine e) {
        return new String[] {
            "DELETE FROM " + e.qualify("ABAC_Assignment") + " WHERE id IN ('suite_a_customer','suite_a_item','suite_a_sales')",
            "DELETE FROM " + e.qualify("ABAC_EntitySubjectAssignment") + " WHERE assignmentID IN ('suite_a_customer','suite_a_item','suite_a_sales')",
            "DELETE FROM " + e.qualify("orgHierarchy") + " WHERE orgID='" + Cases.SUITE_ORG + "' OR parentOrgID='" + Cases.SUITE_ORG + "'"
        };
    }

    public static void runAll(Engine e, Connection c, List<Case> cases, boolean seeded) {
        int pass = 0, fail = 0, info = 0, error = 0;

        System.out.println("================================================================");
        System.out.println(" ABAC JDBC test suite — " + cases.size() + " cases + DR2 hot-swap scenario (3 checks)");
        System.out.println(" Auth: OAuth M2M as the service principal + per-case custom_claim hot-swap");
        System.out.println(" Fixture: " + (seeded
            ? "seeded namespaced rows (suite_a_*, " + Cases.SUITE_ORG + ") — dropped at the end"
            : "NOT seeded (SP needs MODIFY on the metadata tables) — using the existing seed"));
        System.out.println(" Target filter: full 3-branch (DISABLE + permissions + root/RBAC_ABAC/EXISTS).");
        System.out.println(" If a whole group (A9/B*/R*) returns 0/ALL unexpectedly, redeploy");
        System.out.println(" sql/05_dataset_udfs.sql's abac_row_filter — the live filter is not 3-branch.");
        System.out.println("================================================================");

        for (Case cs : cases) {
            Expect exp = cs.exp();

            System.out.println();
            System.out.println("[" + cs.id() + "] (" + cs.group() + ") " + cs.purpose());
            System.out.println("   detail : " + cs.description());
            System.out.println("   claim  : " + cs.claim());
            System.out.println("   sql    : " + cs.sql());
            System.out.println("   expect : " + exp.describe());

            try {
                e.applyIdentity(c, cs.claim());
                if (exp.kind == Kind.IDLIST) {                      // assert the projected id list exactly
                    List<String> actualIds = firstColumn(c, cs.sql());
                    boolean ok = actualIds.equals(exp.ids);
                    System.out.println("   actual : " + actualIds);
                    System.out.println("   verdict: " + (ok ? "PASS" : "FAIL"));
                    if (ok) pass++; else fail++;
                } else if (exp.kind == Kind.INFO) {
                    List<String> ids = firstColumn(c, cs.sql());
                    System.out.println("   actual : " + ids);
                    System.out.println("   verdict: INFO");
                    info++;
                } else if (exp.kind == Kind.ERR) {
                    try {                                          // this case EXPECTS the query to fail
                        long actual = count(c, cs.sql());
                        System.out.println("   actual : " + actual + " rows (no error)");
                        System.out.println("   verdict: FAIL (expected an error)");
                        fail++;
                    } catch (SQLException qe) {
                        boolean ok = qe.getMessage() != null && qe.getMessage().contains(exp.text);
                        System.out.println("   actual : <error> " + shortErr(qe.getMessage()));
                        System.out.println("   verdict: " + (ok ? "PASS (got the expected error)" : "FAIL (different error)"));
                        if (ok) pass++; else fail++;
                    }
                } else {
                    long actual = count(c, cs.sql());              // captured under the case claim
                    boolean ok = check(e, c, exp, actual, cs.sql());  // ALL probes the DISABLE total AFTER this
                    System.out.println("   actual : " + actual);
                    System.out.println("   verdict: " + (ok ? "PASS" : "FAIL"));
                    if (ok) pass++; else fail++;
                }
            } catch (SQLException ex) {
                System.out.println("   actual : <error> " + shortErr(ex.getMessage()));
                System.out.println("   verdict: ERROR");
                error++;
            }
        }

        int[] dr2 = runDr2Swap(e, c);          // stateful ABAC has_tag() hot-swap scenario (DR2a/b/c)
        pass += dr2[0]; fail += dr2[1]; error += dr2[2];

        System.out.println();
        System.out.println("================================================================");
        System.out.println(" SUMMARY  ->  PASS " + pass
                         + "   FAIL " + fail + "   INFO " + info + "   ERROR " + error);
        System.out.println("================================================================");
    }

    /** The DR2 hot-swap scenario: the ABAC has_tag() policy on `income_band` binds the stable
     *  dr2_wrapper -> the SP-owned inner dr2_row_filter. Assert the baseline, CREATE OR REPLACE the
     *  INNER udf (the policy binding is untouched, so no "function in use" conflict), wait 10s, and
     *  re-assert the changed count; then revert so the suite is re-runnable. Returns {pass,fail,error}. */
    static int[] runDr2Swap(Engine e, Connection c) {
        int pass = 0, fail = 0, error = 0;
        final String CNT = "SELECT count(*) FROM " + e.qualify(Cases.DR2_TBL);
        System.out.println();
        System.out.println("---------------- DR2 hot-swap scenario (income_band, ABAC has_tag() policy) ----------------");
        System.out.println(" Policy binds dr2_wrapper -> dr2_row_filter (SP-owned, swappable). Change the INNER UDF,");
        System.out.println(" wait 10s, re-assert, then revert. Tables/UDFs come from sql/15.");
        try {
            e.applyIdentity(c, Cases.DISABLE_CLAIM);   // dr2_wrapper calls get_user_context() -> session must carry a claim
            // DR2a — baseline: original inner cutoff <= 10 -> 10 of 20 rows
            long a1 = count(c, CNT);
            boolean ok1 = (a1 == 10);
            dr2Print("DR2a", "baseline (ABAC policy; dr2_row_filter cutoff <= 10): 10 of 20 rows",
                     CNT, "10", String.valueOf(a1), ok1);
            if (ok1) pass++; else fail++;

            // change the row-filter definition: CREATE OR REPLACE the inner UDF to cutoff <= 5
            long t0 = System.nanoTime();
            exec(c, dr2Def(e, 5));
            System.out.println();
            System.out.println("   [swapped dr2_row_filter -> cutoff <= 5; waiting 10s before re-asserting ...]");
            sleep(10_000);
            long a2 = count(c, CNT);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            boolean ok2 = (a2 == 5);
            dr2Print("DR2b", "after CREATE OR REPLACE (cutoff <= 5) + 10s delay: 5 of 20 rows"
                             + "  [swap->reflected in " + ms + " ms incl. the 10s wait]",
                     CNT, "5", String.valueOf(a2), ok2);
            if (ok2) pass++; else fail++;

            // revert the inner UDF to its original definition
            exec(c, dr2Def(e, 10));
            long a3 = count(c, CNT);
            boolean ok3 = (a3 == 10);
            dr2Print("DR2c", "reverted dr2_row_filter -> cutoff <= 10: visible count back to 10",
                     CNT, "10", String.valueOf(a3), ok3);
            if (ok3) pass++; else fail++;
        } catch (SQLException e2) {
            System.out.println("   actual : <error> " + shortErr(e2.getMessage()));
            System.out.println("   verdict: ERROR (DR2 scenario). Ensure sql/15 ran and the SP OWNS dr2_row_filter"
                             + " (CREATE OR REPLACE needs ownership — see sql/15's GRANT CREATE FUNCTION fallback).");
            error++;
            try { exec(c, dr2Def(e, 10)); } catch (SQLException ignore) { /* best-effort revert */ }
        }
        return new int[]{pass, fail, error};
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

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * Insert the namespaced fixture. Idempotent (clears leftovers first). Returns false if the SP
     * lacks MODIFY.
     *
     * The fixture reads `customer` (a policy-protected table) to pick real org children. The deployed
     * get_user_context() calls current_oauth_custom_identity_claim(), which HARD-ERRORS
     * (OAUTH_CUSTOM_IDENTITY_CLAIM_NOT_PROVIDED) if the session token carries no custom_claim. The
     * initial connection token has none — so we inject a DISABLE claim first: it satisfies the claim
     * requirement AND returns all rows, so the SELECT FROM customer succeeds.
     */
    public static boolean setUpFixture(Engine e, Connection c) {
        try {
            e.applyIdentity(c, Cases.DISABLE_CLAIM);  // fixture reads `customer`; session must carry a claim
            dropFixture(e, c);                                      // clear any leftovers from an aborted run
            for (String sql : fixtureInserts(e)) exec(c, sql);
            return true;
        } catch (SQLException e2) {
            System.out.println(" Fixture setup skipped: " + e2.getMessage());
            return false;
        }
    }

    /** Delete ONLY the suite-namespaced rows (safe: never touches the real seed). */
    public static void dropFixture(Engine e, Connection c) throws SQLException {
        for (String sql : fixtureDeletes(e)) exec(c, sql);
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) { st.execute(sql); }
    }

    static boolean check(Engine e, Connection c, Expect exp, long actual, String sql) throws SQLException {
        switch (exp.kind) {
            case ZERO:    return actual == 0;
            case NONZERO: return actual > 0;
            case EXACT:   return actual == exp.n;
            case ATLEAST: return actual >= exp.n;
            case ALL:     { long total = totalUnderDisable(e, c, sql); return total > 0 && actual == total; }
            default:      return true;
        }
    }

    /** Total rows the query returns unfiltered, measured by re-running it under a DISABLE claim. */
    static long totalUnderDisable(Engine e, Connection c, String sql) throws SQLException {
        e.applyIdentity(c, Cases.DISABLE_CLAIM);
        return count(c, sql);
    }

    static long count(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    /** Collapse a huge multi-line driver error into one readable line. */
    static String shortErr(String msg) {
        if (msg == null) return "(no message)";
        String s = msg.replaceAll("\\s+", " ").trim();
        return s.length() > 260 ? s.substring(0, 260) + " …" : s;
    }

    static List<String> firstColumn(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) throw new IllegalStateException("Missing env var: " + name);
        return v;
    }
}
