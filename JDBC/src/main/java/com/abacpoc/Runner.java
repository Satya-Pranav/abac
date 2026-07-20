package com.abacpoc;

import com.abacpoc.engine.DatabricksEngine;
import com.abacpoc.engine.E6DataEngine;
import com.abacpoc.engine.Engine;

import java.sql.Connection;

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
            boolean seeded = AbacTestSuite.setUpFixture(engine, c);
            try {
                AbacTestSuite.runAll(engine, c, AbacTestSuite.cases(engine), seeded);
            } finally {
                if (seeded) {
                    try { AbacTestSuite.dropFixture(engine, c); System.out.println(" Fixture: dropped."); }
                    catch (Exception e) {
                        System.out.println(" Fixture: teardown FAILED, remove manually: " + e.getMessage());
                    }
                }
            }
        }
    }
}
