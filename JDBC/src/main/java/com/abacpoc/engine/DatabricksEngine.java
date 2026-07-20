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
        String url = "jdbc:databricks://" + host + ":443/default";
        Properties props = new Properties();
        props.put("httpPath", "/sql/1.0/warehouses/" + warehouseId);
        props.put("AuthMech", "11");
        props.put("Auth_Flow", "1");
        props.put("OAuth2ClientId", clientId);
        props.put("OAuth2Secret", clientSecret);
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
