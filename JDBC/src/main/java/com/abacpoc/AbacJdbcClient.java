package com.abacpoc;

import com.databricks.internal.sdk.core.DatabricksConfig;
import com.databricks.internal.sdk.core.DatabricksException;
import com.databricks.internal.sdk.core.oauth.AuthParameterPosition;
import com.databricks.internal.sdk.core.oauth.CachedTokenSource;
import com.databricks.internal.sdk.core.oauth.ClientCredentials;
import com.databricks.internal.sdk.core.oauth.OAuthHeaderFactory;
import com.databricks.internal.sdk.core.oauth.OAuthM2MServicePrincipalCredentialsProvider;
import com.databricks.internal.sdk.core.oauth.OpenIDConnectEndpoints;
import com.databricks.internal.sdk.core.oauth.Token;
import com.databricks.jdbc.api.impl.DatabricksConnection;
import com.databricks.jdbc.api.internal.IDatabricksConnectionContext;
import com.databricks.jdbc.auth.DatabricksTokenFederationProvider;
import com.databricks.jdbc.dbclient.IDatabricksClient;
import com.databricks.jdbc.exception.DatabricksParsingException;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Properties;

/**
 * Standalone reproduction of Java/config/DatabricksConnectionProxy.java's auth mechanism,
 * using the real Databricks JDBC driver instead of the raw curl + SQL Statement Execution
 * API approach in scripts/run_as_context.sh.
 *
 * Two-step auth, matching the customer's checkClaim()/newProvider():
 *   1. Open a normal JDBC connection using the driver's documented OAuth M2M properties
 *      (AuthMech=11, Auth_Flow=1, OAuth2ClientId, OAuth2Secret). This mints an initial
 *      token with NO custom_claim.
 *   2. Unwrap to the driver-internal DatabricksConnection, build a NEW credentials
 *      provider that injects custom_claim into the OIDC token request (identical
 *      structure to the customer's anonymous OAuthM2MServicePrincipalCredentialsProvider
 *      override), and hot-swap the connection's live access token to one that carries it.
 *
 * Usage:
 *   java -jar target/jdbc-client-1.0-SNAPSHOT-jar-with-dependencies.jar '<ctx json>' '<sql>'
 *
 * Required environment variables: CLIENT_ID, CLIENT_SECRET, WORKSPACE_HOST, WAREHOUSE_ID
 * (same names/meaning as scripts/run_as_context.sh, so you can reuse the same shell
 * exports for either).
 */
public class AbacJdbcClient {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: AbacJdbcClient '<ctx json>' '<sql statement>'");
            System.exit(1);
        }
        String ctxJson = args[0];
        String sql = args[1];

        String clientId = requireEnv("CLIENT_ID");
        String clientSecret = requireEnv("CLIENT_SECRET");
        String workspaceHost = requireEnv("WORKSPACE_HOST");
        String warehouseId = requireEnv("WAREHOUSE_ID");

        String url = "jdbc:databricks://" + workspaceHost + ":443/default";
        Properties props = new Properties();
        props.put("httpPath", "/sql/1.0/warehouses/" + warehouseId);
        props.put("AuthMech", "11");
        props.put("Auth_Flow", "1");
        props.put("OAuth2ClientId", clientId);
        props.put("OAuth2Secret", clientSecret);

        System.out.println("--- ctx: " + ctxJson);
        System.out.println("--- sql: " + sql);

        try (Connection connection = DriverManager.getConnection(url, props)) {
            injectCustomClaim(connection, ctxJson);
            runStatement(connection, sql);
        }
    }

    /**
     * Reproduces DatabricksConnectionProxy.checkClaim() + newProvider().
     * Public + static so DatabricksEngine (com.abacpoc.engine) can delegate to it per test case.
     */
    public static void injectCustomClaim(Connection connection, String ctxJson) throws SQLException {
        DatabricksConnection dconnection = connection.unwrap(DatabricksConnection.class);
        IDatabricksClient client = dconnection.getSession().getDatabricksClient();
        IDatabricksConnectionContext ctx = client.getConnectionContext();
        DatabricksConfig config = client.getDatabricksConfig();

        DatabricksTokenFederationProvider newProvider = new DatabricksTokenFederationProvider(
            ctx,
            new OAuthM2MServicePrincipalCredentialsProvider() {
                @Override
                public OAuthHeaderFactory configure(DatabricksConfig config) {
                    // Identical structure to DatabricksConnectionProxy.newProvider(), just
                    // with endpointParametersSupplier set to inject custom_claim.
                    try {
                        OpenIDConnectEndpoints jsonResponse = config.getOidcEndpoints();
                        ClientCredentials clientCredentials = new ClientCredentials.Builder()
                            .withHttpClient(config.getHttpClient())
                            .withEndpointParametersSupplier(() -> Map.of("custom_claim", ctxJson))
                            .withClientId(ctx.getClientId())
                            .withClientSecret(ctx.getClientSecret())
                            .withTokenUrl(jsonResponse.getTokenEndpoint())
                            .withScopes(config.getScopes())
                            .withAuthParameterPosition(AuthParameterPosition.HEADER)
                            .build();

                        CachedTokenSource cachedTokenSource = new CachedTokenSource.Builder(clientCredentials)
                            .setAsyncDisabled(config.getDisableAsyncTokenRefresh())
                            .build();

                        return OAuthHeaderFactory.fromTokenSource(cachedTokenSource);
                    } catch (IOException | DatabricksParsingException e) {
                        throw new DatabricksException("Unable to fetch OIDC endpoint: " + e.getMessage(), e);
                    }
                }
            }
        );

        newProvider.configure(config);
        config.setCredentialsProvider(newProvider);

        Token token = newProvider.getToken();
        client.resetAccessToken(token.getAccessToken());
    }

    private static void runStatement(Connection connection, String sql) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            boolean isQuery = stmt.execute(sql);
            if (isQuery) {
                try (ResultSet rs = stmt.getResultSet()) {
                    printResultSet(rs);
                }
            } else {
                System.out.println("Statement executed. Update count: " + stmt.getUpdateCount());
            }
        }
    }

    private static void printResultSet(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        while (rs.next()) {
            StringBuilder row = new StringBuilder();
            for (int i = 1; i <= columnCount; i++) {
                if (i > 1) {
                    row.append(" | ");
                }
                row.append(meta.getColumnLabel(i)).append("=").append(rs.getString(i));
            }
            System.out.println(row);
        }
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }
}
