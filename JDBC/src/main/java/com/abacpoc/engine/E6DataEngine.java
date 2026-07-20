package com.abacpoc.engine;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Placeholder so {@code Runner} compiles against ENGINE=e6data before Task 6 lands.
 * Every method throws; Task 6 replaces this class with a real implementation.
 */
public final class E6DataEngine implements Engine {

    @Override public String name() {
        throw new UnsupportedOperationException("E6DataEngine: implemented in Task 6");
    }

    @Override public Connection connect() throws SQLException {
        throw new UnsupportedOperationException("E6DataEngine: implemented in Task 6");
    }

    @Override public void applyIdentity(Connection c, String ctxJson) throws SQLException {
        throw new UnsupportedOperationException("E6DataEngine: implemented in Task 6");
    }

    @Override public String qualify(String table) {
        throw new UnsupportedOperationException("E6DataEngine: implemented in Task 6");
    }

    @Override public boolean supports(Capability c) {
        throw new UnsupportedOperationException("E6DataEngine: implemented in Task 6");
    }

    @Override public void printBanner() {
        throw new UnsupportedOperationException("E6DataEngine: implemented in Task 6");
    }

    @Override public String connectionHelp() {
        throw new UnsupportedOperationException("E6DataEngine: implemented in Task 6");
    }
}
