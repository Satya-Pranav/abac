package com.abacpoc.engine;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * e6data engine binding. Connection surface only.
 *
 * The ABAC identity flow on e6data is still being built. {@link #applyIdentity} is the ONE seam
 * that changes when it lands — nothing else in the suite should need to move.
 */
public final class E6DataEngine implements Engine {

    private final String host, port, catalog, database, user, password;

    public E6DataEngine() {
        this.host     = env("E6_HOST");
        this.port     = System.getenv().getOrDefault("E6_PORT", "443");
        this.catalog  = env("E6_CATALOG");
        this.database = env("E6_DATABASE");
        this.user     = env("E6_USER");
        this.password = env("E6_PASSWORD");
    }

    @Override public String name() { return "e6data"; }

    @Override public String qualify(String table) { return catalog + "." + database + "." + table; }

    /** Nothing ABAC-related is claimed yet. Cases requiring these report SKIP, not FAIL. */
    @Override public boolean supports(Capability c) { return false; }

    @Override public Connection connect() throws SQLException {
        String url = "jdbc:e6data://" + host + ":" + port
                   + "/database=" + database + "&catalog=" + catalog;
        Properties props = new Properties();
        props.put("user", user);
        props.put("password", password);
        return DriverManager.getConnection(url, props);
    }

    /**
     * SEAM — implement when the e6data ABAC identity flow exists.
     *
     * Throwing (rather than silently no-op'ing) is deliberate: a no-op would let cases run with
     * NO identity and quietly pass against unfiltered data, which is the single most misleading
     * outcome a governance suite can produce.
     */
    @Override public void applyIdentity(Connection c, String ctxJson) throws SQLException {
        throw new SQLException("E6DataEngine.applyIdentity is not implemented — "
            + "the e6data ABAC identity flow is not available yet. "
            + "Implement this method when it lands; nothing else needs to change.");
    }

    @Override public void printBanner() {
        System.out.println("Connecting: engine=e6data host=" + host + ":" + port
                         + "  catalog=" + catalog + "  database=" + database
                         + "  user=" + DatabricksEngine.mask(user));
        System.out.println("URL: jdbc:e6data://" + host + ":" + port
                         + "/database=" + database + "&catalog=" + catalog);
    }

    @Override public String connectionHelp() {
        return "   Check: E6_HOST/E6_PORT reachable, E6_CATALOG='" + catalog
             + "' and E6_DATABASE='" + database + "' exist, and E6_USER/E6_PASSWORD are valid.";
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) throw new IllegalStateException("Missing env var: " + name);
        return v;
    }
}
