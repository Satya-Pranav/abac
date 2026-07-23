package com.abacpoc.engine;

import com.abacpoc.AbacJdbcClient;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class DatabricksEngine implements Engine {

    /** Unchanged prefix — the refactor must not move any table. */
    private static final String PREFIX = "abac_tpcds.tpcds_1_delta.";

    private final String host, warehouseId, clientId, clientSecret;

    public DatabricksEngine() {
        this.clientId     = env("CLIENT_ID");
        this.clientSecret = env("CLIENT_SECRET");
        this.warehouseId  = env("WAREHOUSE_ID");
        this.host = env("WORKSPACE_HOST").trim()
                .replaceFirst("^https?://", "")
                .replaceAll("/+$", "");
    }

    @Override public String name() { return "databricks"; }

    @Override public String qualify(String table) { return PREFIX + table; }

    @Override public boolean supports(Capability c) { return true; }

    @Override public Connection connect() throws SQLException {
        return connectAs(clientId, clientSecret);
    }

    /**
     * Open a NEW Databricks JDBC connection against the SAME host/httpPath/warehouse as
     * {@link #connect()}, but authenticating with the GIVEN OAuth clientId/clientSecret instead of
     * the env-derived defaults. Lets a scenario open a second, independently-authenticated
     * connection (a different secret for this SP, or a wholly different service principal)
     * without duplicating the URL/props construction.
     */
    public Connection connectAs(String clientId, String clientSecret) throws SQLException {
        String url = "jdbc:databricks://" + host + ":443/default";
        Properties props = new Properties();
        props.put("httpPath", "/sql/1.0/warehouses/" + warehouseId);
        props.put("AuthMech", "11");
        props.put("Auth_Flow", "1");
        props.put("OAuth2ClientId", clientId);
        props.put("OAuth2Secret", clientSecret);
        return DriverManager.getConnection(url, props);
    }

    /**
     * Open a connection that uses {@code accessToken} as a STATIC OAuth bearer, with NO refresh:
     * {@code Auth_Flow=0} (token pass-through) supplies no client credentials, so the driver cannot
     * mint or refresh a token — it sends this one as-is. That is exactly what a token-expiry test
     * needs: an EXPIRED token must be sent unchanged and rejected server-side, not silently swapped
     * for a fresh one (which the M2M {@link #connectAs} path would do). The token may carry its own
     * embedded custom_claim; no separate claim injection is used or needed here.
     */
    public Connection connectWithAccessToken(String accessToken) throws SQLException {
        String url = "jdbc:databricks://" + host + ":443/default";
        Properties props = new Properties();
        props.put("httpPath", "/sql/1.0/warehouses/" + warehouseId);
        props.put("AuthMech", "11");
        props.put("Auth_Flow", "0");                 // 0 = token pass-through (no refresh)
        props.put("Auth_AccessToken", accessToken);
        return DriverManager.getConnection(url, props);
    }

    @Override public void applyIdentity(Connection c, String ctxJson) throws SQLException {
        AbacJdbcClient.injectCustomClaim(c, ctxJson);
    }

    @Override public void printBanner() {
        System.out.println("Connecting: host=" + host + "  warehouse=" + warehouseId
                         + "  clientId=" + mask(clientId));
        System.out.println("URL: jdbc:databricks://" + host + ":443/default"
                         + "   httpPath=/sql/1.0/warehouses/" + warehouseId);
    }

    @Override public String connectionHelp() {
        return "   Check: WORKSPACE_HOST is a bare host with NO trailing '/' or 'https://' (using '"
             + host + "'),\n"
             + "          WAREHOUSE_ID='" + warehouseId
             + "' is correct, and CLIENT_ID/CLIENT_SECRET are a valid OAuth M2M pair.";
    }

    /** Keep the first 8 chars of a secret/id, hide the rest. */
    public static String mask(String s) {
        if (s == null || s.length() <= 8) return "********";
        return s.substring(0, 8) + "…";
    }

    private static String env(String name) {
        String v = System.getenv(name);
        if (v == null || v.isEmpty()) throw new IllegalStateException("Missing env var: " + name);
        return v;
    }
}
