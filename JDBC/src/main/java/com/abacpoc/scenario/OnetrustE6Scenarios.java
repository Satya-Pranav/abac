package com.abacpoc.scenario;

import com.abacpoc.engine.Capability;
import com.abacpoc.engine.Engine;

import java.sql.Connection;
import java.util.List;
import java.util.Set;

/** OneTrust duplicate of E6Scenarios -- e6data-specific placeholders (planner topology, caching,
 *  pooling, token lifecycle, errors). All require CLAIM_SWAP, which no engine advertises until the
 *  e6data ABAC identity flow ships, so each unconditionally reports SKIP. Mechanical port -- these
 *  placeholders have no OneTrust-specific content to adapt; only the id() prefix differs. */
public final class OnetrustE6Scenarios {

    public static List<Scenario> all() {
        return List.of(
            simple("OT-E6-PLANNER",  "Authenticate on planner A, query planner B — identity is honored, not reused or dropped"),
            simple("OT-E6-CACHE",    "After a policy change, a subsequent query reflects it (ASSERT the new result; REPORT how long it took)"),
            simple("OT-E6-POOL",     "Two identities over a reused connection do not bleed into each other"),
            simple("OT-E6-EXPIRY",   "Token expiry mid-flow yields a clean categorized error, never unfiltered rows"),
            simple("OT-E6-RETRY",    "A transient connect failure recovers within a bounded ATTEMPT COUNT (a count, not a duration)"),
            simple("OT-E6-BREAKER",  "Sustained downstream failure surfaces an error to the client (REPORT time to surface; do not assert on it)"),
            simple("OT-E6-ERRCLASS", "Client errors are distinguishable from internal errors")
        );
    }

    private static Scenario simple(String id, String intent) {
        return new Scenario() {
            @Override public String id() { return id; }
            @Override public Set<Capability> requires() { return Set.of(Capability.CLAIM_SWAP); }
            @Override public int[] run(Engine e, Connection c) {
                long t0 = System.nanoTime();
                System.out.println();
                System.out.println("[" + id + "] (OT-E6) " + intent);
                System.out.println("   verdict: SKIP (awaiting the e6data ABAC identity flow)");
                System.out.println("   elapsed: " + (System.nanoTime() - t0) / 1_000_000 + " ms");
                return new int[]{0, 0, 1, 0};
            }
        };
    }

    private OnetrustE6Scenarios() {}
}
