package com.abacpoc.engine;

import java.sql.Connection;
import java.sql.SQLException;

/** The only abstraction between the case catalog and a query engine. */
public interface Engine {

    /** Short name used in report headers, e.g. "databricks". */
    String name();

    /** Open a connection. Throws if configuration is missing or the engine is unreachable. */
    Connection connect() throws SQLException;

    /** Make subsequent statements on {@code c} run as the identity described by {@code ctxJson}. */
    void applyIdentity(Connection c, String ctxJson) throws SQLException;

    /** Fully-qualify an unqualified table name for this engine. */
    String qualify(String table);

    /** Whether this engine supports {@code c}. Cases requiring an unsupported capability SKIP. */
    boolean supports(Capability c);

    /** Print the engine-specific connection banner (kept engine-side to preserve exact output). */
    void printBanner();

    /** Operator hint printed when {@link #connect()} fails. */
    String connectionHelp();
}
