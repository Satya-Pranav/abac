package com.onetrust.otinsightscommand.databrickssql.config;

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
import com.onetrust.otinsightscommand.databrickssql.service.TenantDBNameResolver;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@RequiredArgsConstructor
public class DatabricksConnectionProxy implements InvocationHandler {
    protected static void validateCatalogName(String catalogName) {
        if (catalogName == null || catalogName.isEmpty()) {
            throw new IllegalArgumentException("Catalog name cannot be null or empty");
        } else if (catalogName.matches("^[a-zA-Z0-9-]+-insights-uc-catalog(-v2)?$") == false) {
            // Catalog name format: environmentId + '-insights-uc-catalog' + '-v2' or nothing (empty)
            // Allow alphanumeric, hyphens in environmentId
            throw new IllegalArgumentException("Invalid catalog name format: " + catalogName);
        }
    }

    private final TenantDBNameResolver resolver;
    private final CatalogResolver catalogResolver;
    private final Connection connection;
    private final DatabricksConnection dconnection;
    private final Connection proxy;
    private final DatabricksTokenFederationProvider originalCredentialsProvider;
    private final boolean oauth;

    private String catalog;
    private String schema;
    private DatabricksSessionContext claim;

    public DatabricksConnectionProxy(
        TenantDBNameResolver resolver,
        CatalogResolver catalogResolver,
        Connection connection,
        boolean oauth
    ) throws SQLException {
        this.resolver = resolver;
        this.catalogResolver = catalogResolver;
        this.connection = connection;
        this.proxy = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, this);
        this.schema = connection.getSchema();
        this.oauth = oauth;

        if (oauth) {
            dconnection = connection.unwrap(DatabricksConnection.class);
            originalCredentialsProvider =
                (DatabricksTokenFederationProvider) dconnection.getSession().getDatabricksClient().getDatabricksConfig().getCredentialsProvider();
        } else {
            dconnection = null;
            originalCredentialsProvider = null;
        }
    }

    @Override
    public synchronized Object invoke(Object proxy, Method method, Object[] args) throws Exception {
        if (this.proxy != proxy) {
            throw new IllegalArgumentException("This object is for a different proxy");
        }

        if (method.getName().equals("unwrap")) {
            @SuppressWarnings("unchecked")
            Class<?> cls = (Class) args[0];
            return unwrap(cls);
        } else if (method.getDeclaringClass() != Connection.class) {
            return callReal(method, args);
        }

        return invoke(method, args);
    }

    private Object invoke(Method method, Object[] args) throws Exception {
        String name = method.getName();
        switch (name) {
            case "abort":
            case "clearWarnings":
            case "close":
            case "commit":
            case "createArrayOf":
            case "createBlob":
            case "createClob":
            case "createNClob":
            case "createSQLXML":
            case "createStruct":
            case "getClientInfo":
            case "getHoldability":
            case "getMetaData":
            case "getNetworkTimeout":
            case "getTransactionIsolation":
            case "getTypeMap":
            case "getWarnings":
            case "isClosed":
            case "isReadOnly":
            case "isValid":
            case "rollback":
            case "setClientInfo":
            case "setHoldability":
            case "setNetworkTimeout":
            case "setReadOnly":
            case "setTransactionIsolation":
            case "setTypeMap":
                return callReal(method, args);
            case "getSchema":
                return schema;
            case "setSchema":
                schema = (String) args[0];
                connection.setSchema(schema);
                return null;
            default:
                checkSchema();
                checkClaim();
                return callReal(method, args);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface == DatabricksConnectionProxy.class) {
            return (T) this;
        } else {
            return (T) connection.unwrap(iface);
        }
    }

    protected Object callReal(Method method, Object[] args) throws Exception {
        log.trace("Current claim = {}", claim);
        return method.invoke(connection, args);
    }

    protected void checkSchema() throws SQLException {
        String required = resolver.getDBNameForTenantUUID();
        if (required.equals(schema)) {
            return;
        }

        schema = required;
        connection.setSchema(required);
    }

    protected void checkCatalog() throws SQLException {
        String required = catalogResolver.getCatalogName();
        if (required.equals(catalog)) {
            return;
        }

        catalog = required;
        useCatalog(catalog);
    }

    protected void useCatalog(String catalogName) throws SQLException {
        validateCatalogName(catalogName);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("USE CATALOG `" + catalogName + "`");
            log.debug("Setting catalog to: {}", catalogName);
        }
    }

    protected void checkClaim() throws SQLException {
        if (oauth == false) {
            return;
        }

        DatabricksSessionContext context = DatabricksSessionContext.ensure();

        {
            // Variable to work around sonar
            boolean upToDate = context == null && claim == null;
            upToDate |= context != null && context.matches(claim) && claim.hasToken();
            if (upToDate) {
                log.trace("Current claim is up to date: {}", claim);
                return;
            }
        }

        log.debug("Updating claim to {}", context);

        IDatabricksClient client = dconnection.getSession().getDatabricksClient();
        IDatabricksConnectionContext ctx = client.getConnectionContext();
        DatabricksConfig config = client.getDatabricksConfig();

        String json = context.serialize();

        DatabricksTokenFederationProvider newProvider = newProvider(ctx, json);

        newProvider.configure(config);
        config.setCredentialsProvider(newProvider);

        // Provide the new token to the connection
        Token t = newProvider.getToken();
        String token = t.getAccessToken();
        client.resetAccessToken(token);

        context.setToken(token, t.getExpiry());

        claim = context;
        log.debug("Updated claim to {}", claim);
    }

    protected DatabricksTokenFederationProvider newProvider(IDatabricksConnectionContext ctx, String json) {
        DatabricksTokenFederationProvider newProvider = new DatabricksTokenFederationProvider(
            ctx,
            new OAuthM2MServicePrincipalCredentialsProvider() {
                @Override
                public OAuthHeaderFactory configure(DatabricksConfig config) {
                    // This is a copy of the super method, but with endpointParametersSupplier set
                    try {
                        OpenIDConnectEndpoints jsonResponse = config.getOidcEndpoints();
                        ClientCredentials clientCredentials = new ClientCredentials.Builder()
                            .withHttpClient(config.getHttpClient())
                            .withEndpointParametersSupplier(() -> Map.of("custom_claim", json))
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

        return newProvider;
    }
}
