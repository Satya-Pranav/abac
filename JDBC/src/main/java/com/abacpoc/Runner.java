package com.abacpoc;

import com.abacpoc.cases.Case;
import com.abacpoc.cases.Cases;
import com.abacpoc.cases.Expect;
import com.abacpoc.cases.Expect.Kind;
import com.abacpoc.engine.Capability;
import com.abacpoc.engine.DatabricksEngine;
import com.abacpoc.engine.E6DataEngine;
import com.abacpoc.engine.Engine;
import com.abacpoc.scenario.Dr2HotSwap;
import com.abacpoc.util.Jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class Runner {

    public static Engine select() {
        String which = System.getenv().getOrDefault("ENGINE", "databricks").trim().toLowerCase();
        switch (which) {
            case "databricks": return new DatabricksEngine();
            case "e6data":     return new E6DataEngine();
            default: throw new IllegalStateException(
                "Unknown ENGINE '" + which + "' (expected 'databricks' or 'e6data')");
        }
    }

    public static void main(String[] args) throws Exception {
        Engine engine = select();
        engine.printBanner();

        Connection c;
        try {
            c = engine.connect();
        } catch (Exception e) {
            System.err.println();
            System.err.println("!! Connection FAILED before any test ran: "
                             + e.getClass().getSimpleName() + ": " + e.getMessage());
            System.err.println(engine.connectionHelp());
            throw e;
        }

        try (c) {
            boolean seeded = setUpFixture(engine, c);
            try {
                runAll(engine, c, Cases.all(engine), seeded);
            } finally {
                if (seeded) {
                    try { dropFixture(engine, c); System.out.println(" Fixture: dropped."); }
                    catch (Exception e) {
                        System.out.println(" Fixture: teardown FAILED, remove manually: " + e.getMessage());
                    }
                }
            }
        }
    }

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
        int pass = 0, fail = 0, skip = 0, info = 0, error = 0;

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
            java.util.Optional<Capability> missing = cs.requires().stream()
                    .filter(cap -> !e.supports(cap)).findFirst();
            if (missing.isPresent()) {
                System.out.println("   verdict: SKIP (" + e.name() + " lacks " + missing.get() + ")");
                skip++;
                continue;
            }

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
                    List<String> actualIds = Jdbc.firstColumn(c, cs.sql());
                    boolean ok = actualIds.equals(exp.ids);
                    System.out.println("   actual : " + actualIds);
                    System.out.println("   verdict: " + (ok ? "PASS" : "FAIL"));
                    if (ok) pass++; else fail++;
                } else if (exp.kind == Kind.INFO) {
                    List<String> ids = Jdbc.firstColumn(c, cs.sql());
                    System.out.println("   actual : " + ids);
                    System.out.println("   verdict: INFO");
                    info++;
                } else if (exp.kind == Kind.ERR) {
                    try {                                          // this case EXPECTS the query to fail
                        long actual = Jdbc.count(c, cs.sql());
                        System.out.println("   actual : " + actual + " rows (no error)");
                        System.out.println("   verdict: FAIL (expected an error)");
                        fail++;
                    } catch (SQLException qe) {
                        boolean ok = qe.getMessage() != null && qe.getMessage().contains(exp.text);
                        System.out.println("   actual : <error> " + Jdbc.shortErr(qe.getMessage()));
                        System.out.println("   verdict: " + (ok ? "PASS (got the expected error)" : "FAIL (different error)"));
                        if (ok) pass++; else fail++;
                    }
                } else {
                    long actual = Jdbc.count(c, cs.sql());              // captured under the case claim
                    boolean ok = check(e, c, exp, actual, cs.sql());  // ALL probes the DISABLE total AFTER this
                    System.out.println("   actual : " + actual);
                    System.out.println("   verdict: " + (ok ? "PASS" : "FAIL"));
                    if (ok) pass++; else fail++;
                }
            } catch (SQLException ex) {
                System.out.println("   actual : <error> " + Jdbc.shortErr(ex.getMessage()));
                System.out.println("   verdict: ERROR");
                error++;
            }
        }

        Dr2HotSwap dr2Scenario = new Dr2HotSwap();   // stateful ABAC has_tag() hot-swap scenario (DR2a/b/c)
        java.util.Optional<Capability> missingDr2 = dr2Scenario.requires().stream()
                .filter(cap -> !e.supports(cap)).findFirst();
        if (missingDr2.isPresent()) {
            System.out.println("   verdict: SKIP (" + e.name() + " lacks " + missingDr2.get() + ")");
            skip++;
        } else {
            int[] dr2 = dr2Scenario.run(e, c);
            pass += dr2[0]; fail += dr2[1]; skip += dr2[2]; error += dr2[3];
        }

        System.out.println();
        System.out.println("================================================================");
        System.out.println(" SUMMARY  ->  PASS " + pass
                         + "   FAIL " + fail + "   SKIP " + skip
                         + "   INFO " + info + "   ERROR " + error);
        System.out.println("================================================================");
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
            for (String sql : fixtureInserts(e)) Jdbc.exec(c, sql);
            return true;
        } catch (SQLException e2) {
            System.out.println(" Fixture setup skipped: " + e2.getMessage());
            return false;
        }
    }

    /** Delete ONLY the suite-namespaced rows (safe: never touches the real seed). */
    public static void dropFixture(Engine e, Connection c) throws SQLException {
        for (String sql : fixtureDeletes(e)) Jdbc.exec(c, sql);
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
        return Jdbc.count(c, sql);
    }
}
