package com.abacpoc.engine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * e6data engine binding, wired for the OneTrust deployment specifically.
 *
 * The e6data connection itself (E6_USER/E6_PASSWORD) is a SEPARATE, orthogonal identity from the
 * one row-filter policies are evaluated against: e6data's engine team fetches the actual Unity
 * Catalog policy definitions for a table via the Databricks API, resolves them against the caller's
 * identity, and applies the row filter during query planning -- the caller identity comes from a
 * Databricks OAuth token carrying a custom_claim, attached per-statement via
 * {@code Connection.setClientInfo("oauth_token", token)}. This is the exact mechanism demonstrated
 * in e6-jdbc-abac-e2e/lib/e6-jdbc-abac-runner.jar's io.e6.jdbc.AbacStandaloneJDBCTest (decompiled
 * for reference; not a dependency of this class).
 *
 * The token is minted with ONETRUST_CLIENT_ID/ONETRUST_CLIENT_SECRET, not the primary
 * CLIENT_ID/CLIENT_SECRET: our Unity Catalog policies are bound TO the OneTrust SP specifically
 * (see sql_onetrust/07_oauth_wiring.sql), independent of whatever authenticates the e6data
 * connection itself. This engine is therefore scoped to OneTrust cases only -- see
 * Runner.main()'s e6data branch.
 */
public final class E6DataEngine implements Engine {

    private final String host, port, catalog, database, user, password;
    private final String claimClientId, claimClientSecret, workspaceHost;

    public E6DataEngine() {
        this.host     = env("E6_HOST");
        this.port     = System.getenv().getOrDefault("E6_PORT", "443");
        this.catalog  = env("E6_CATALOG");
        this.database = env("E6_DATABASE");
        this.user     = env("E6_USER");
        this.password = env("E6_PASSWORD");
        this.claimClientId     = env("ONETRUST_CLIENT_ID");
        this.claimClientSecret = env("ONETRUST_CLIENT_SECRET");
        this.workspaceHost     = env("WORKSPACE_HOST");
    }

    @Override public String name() { return "e6data"; }

    @Override public String qualify(String table) { return catalog + "." + database + "." + table; }

    /** Only CLAIM_SWAP is proven -- every OnetrustCases case requires exactly this and nothing
     *  else, so this alone unblocks all 119 cases. DDL-adjacent capabilities (POLICY_DDL, TAGS,
     *  CLASSIC_RLS, VIEWS, SCHEMA_SCOPE) stay unsupported: only scenarios need them, and those
     *  multi-step DDL-swap/polling behaviors haven't been validated against e6data. Cases requiring
     *  them report SKIP, not FAIL -- same safe-by-default behavior as before this change. */
    @Override public boolean supports(Capability c) { return c == Capability.CLAIM_SWAP; }

    @Override public Connection connect() throws SQLException {
        // The shaded jar-with-dependencies build overwrites META-INF/services/java.sql.Driver
        // rather than merging entries, so the e6data driver's own service registration does not
        // survive into the fat jar and DriverManager's ServiceLoader lookup never finds it.
        // Explicitly loading the class here is the standard JDBC idiom to force registration.
        try {
            Class.forName("io.e6.jdbc.driver.E6Driver");
        } catch (ClassNotFoundException cnfe) {
            throw new SQLException("e6data JDBC driver (io.e6.jdbc.driver.E6Driver) is not on the classpath. "
                + "Check the com.e6data:e6-jdbc-driver dependency in JDBC/pom.xml.", cnfe);
        }
        String url = "jdbc:e6data://" + host + ":" + port
                   + "/database=" + database + "&catalog=" + catalog;
        Properties props = new Properties();
        props.put("user", user);
        props.put("password", password);
        return DriverManager.getConnection(url, props);
    }

    @Override public void applyIdentity(Connection c, String ctxJson) throws SQLException {
        try {
            c.setClientInfo("oauth_token", mintOAuthToken(ctxJson));
        } catch (IOException ioe) {
            throw new SQLException("Failed to mint the e6data identity token: " + ioe.getMessage(), ioe);
        }
    }

    /** Same /oidc/v1/token + custom_claim flow used everywhere else in this suite (see
     *  sql_onetrust/oauth_validate.py, AbacJdbcClient.mintCustomClaimToken) -- reimplemented here
     *  as a plain HTTP POST rather than reusing AbacJdbcClient's version, which unwraps a
     *  DatabricksConnection and is unusable against an e6data Connection. */
    private String mintOAuthToken(String ctxJson) throws IOException {
        URL url = new URL("https://" + workspaceHost + "/oidc/v1/token");
        String body = "grant_type=client_credentials&scope=" + encode("all-apis")
                    + "&custom_claim=" + encode(ctxJson);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(30_000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Authorization", basicAuth(claimClientId, claimClientSecret));
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setFixedLengthStreamingMode(payload.length);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(payload);
        }

        int status = conn.getResponseCode();
        String response = readAll(status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream());
        if (status < 200 || status >= 300) {
            throw new IOException("Token mint failed (HTTP " + status + "): " + response);
        }
        Matcher m = Pattern.compile("\"access_token\"\\s*:\\s*\"([^\"]+)\"").matcher(response);
        if (!m.find()) {
            throw new IOException("Databricks OAuth response did not contain access_token: " + response);
        }
        return m.group(1);
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is always supported", e);
        }
    }

    private static String basicAuth(String clientId, String clientSecret) {
        String creds = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) != -1) buf.write(chunk, 0, n);
        return buf.toString(StandardCharsets.UTF_8.name());
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
