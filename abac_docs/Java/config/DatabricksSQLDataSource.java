package com.onetrust.otinsightscommand.databrickssql.config;

import com.onetrust.otinsightscommand.databrickssql.service.TenantDBNameResolver;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.WeakHashMap;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class DatabricksSQLDataSource extends HikariDataSource {
    private static final WeakHashMap<Connection, WeakReference<DatabricksConnectionProxy>> PROXIES = new WeakHashMap<>();

    @Getter
    protected final TenantDBNameResolver tenantDBNameResolver;

    protected final CatalogResolver catalogResolver;

    protected final boolean oauth;

    public DatabricksSQLDataSource(TenantDBNameResolver resolver, CatalogResolver catalogResolver, HikariConfig config, boolean oauth) {
        super(config);

        this.tenantDBNameResolver = resolver;
        this.catalogResolver = catalogResolver;
        this.oauth = oauth;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();

        return getProxy(connection);
    }

    private synchronized Connection getProxy(Connection connection) throws SQLException {
        WeakReference<DatabricksConnectionProxy> ref = PROXIES.get(connection);

        DatabricksConnectionProxy proxy = ref == null ? null : ref.get();
        if (proxy == null) {
            proxy = proxy(connection);
            PROXIES.put(connection, new WeakReference<>(proxy));
        }

        return proxy.getProxy();
    }

    protected DatabricksConnectionProxy proxy(Connection connection) throws SQLException {
        return new DatabricksConnectionProxy(tenantDBNameResolver, catalogResolver, connection, oauth);
    }
}
