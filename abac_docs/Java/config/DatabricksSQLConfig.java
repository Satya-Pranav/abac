package com.onetrust.otinsightscommand.databrickssql.config;

import com.azure.security.keyvault.secrets.SecretClient;
import com.onetrust.framework.multitenant.constants.Qualifiers;
import com.onetrust.otinsightscommand.condition.DatabricksCondition;
import com.onetrust.otinsightscommand.constant.OTInsightsConstants;
import com.onetrust.otinsightscommand.databrickssql.service.TenantDBNameResolver;
import com.onetrust.otinsightscommand.gateway.access.IdentityGateway;
import com.querydsl.sql.SQLQueryFactory;
import com.querydsl.sql.SQLTemplates;
import com.querydsl.sql.spring.SpringConnectionProvider;
import com.querydsl.sql.spring.SpringExceptionTranslator;
import com.querydsl.sql.types.DateTimeType;
import com.querydsl.sql.types.LocalDateType;
import com.zaxxer.hikari.HikariConfig;
import jakarta.persistence.EntityManagerFactory;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import javax.sql.DataSource;

import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.orm.jpa.EntityManagerFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

@Slf4j
@Configuration
@Profile("!test && !it")
@Conditional(DatabricksCondition.class)
@EnableJpaRepositories(
    basePackages = {
        "com.onetrust.otinsightscommand.repository.databricks.tenant",
        "com.onetrust.otinsightscommand.repository.databricks.shared"
    },
    entityManagerFactoryRef = "databricksEntityManager",
    transactionManagerRef = Qualifiers.TransactionManager.TENANT
)
public class DatabricksSQLConfig {
    @Data
    @Configuration
    @ConfigurationProperties("onetrust.ot-insights.databricks-sql")
    public static class CommonProperties {
        private Map<String, String> commonProperties;
    }

    @Autowired
    private CommonProperties commonProperties;

    @Autowired(required = false)
    private SecretClient secretClient;

    @Value("${onetrust.databricks.oauth.id:}")
    private String oauthID;

    @Value("${onetrust.databricks.oauth.secret:}")
    private String oauthSecret;

    @Value("${onetrust.databricks.oauth.id-akv-name:}")
    private String oauthIDName;

    @Value("${onetrust.databricks.oauth.secret-akv-name:}")
    private String oauthSecretName;

    @Value("${onetrust.ot-insights.databricks-sql.sp2.datasourceUsername:}")
    private String tokenUsername;

    @Value("${onetrust.ot-insights.databricks-sql.sp2.datasourcePassword:}")
    private String token;

    @Getter
    private boolean oauth;

    private HikariConfig newHikariConfig() {
        HikariConfig hc = new HikariConfig();
        Properties properties = hc.getDataSourceProperties();
        properties.putAll(commonProperties.getCommonProperties());

        String secret = null;
        String applicationID = null;
        if (StringUtils.isNotBlank(oauthSecret)) {
            // OAuth secret is set, so use the config provided values
            applicationID = oauthID;
            secret = oauthSecret;
        } else if (StringUtils.isNotBlank(oauthSecretName)) {
            // OAuth secret name is set, so lookup values from azure key vault
            applicationID = secretClient.getSecret(oauthIDName).getValue();
            secret = secretClient.getSecret(oauthSecretName).getValue();
        }

        if (secret != null) {
            // Use OAuth
            properties.put("AuthMech", "11");
            properties.put("Auth_Flow", "1");
            properties.put("OAuth2ClientId", applicationID);
            properties.put("OAuth2Secret", secret);
            oauth = true;
        } else {
            // If OAuth is not configured, fallback to PAT
            properties.put("AuthMech", "3");
            properties.put("UID", tokenUsername);
            properties.put("PWD", token);
        }

        return hc;
    }

    @Bean
    @ConfigurationProperties("onetrust.ot-insights.databricks-sql.primary")
    public HikariConfig databricksPrimaryConfig() {
        return newHikariConfig();
    }

    @Bean
    @ConfigurationProperties("onetrust.ot-insights.databricks-sql.secondary")
    public HikariConfig databricksSecondaryConfig() {
        return newHikariConfig();
    }

    @Bean
    public CatalogResolver catalogResolver(
        IdentityGateway identityGateway,
        @Value("${onetrust.ot-insights.databricks.silverIn.enabled}") boolean silverInEnabled,
        @Value("${onetrust.ot-insights.databricks-sql.catalog-v1.name}") String catalogV1Name,
        @Value("${onetrust.ot-insights.databricks-sql.catalog-v2.name}") String catalogV2Name,
        SilverInMigrationProperties silverInMigrationProperties
    ) {
        return new CatalogResolver(silverInEnabled, identityGateway, silverInMigrationProperties.getFtEntityMapping(), catalogV1Name, catalogV2Name);
    }

    @Bean
    public DataSource databricksPrimaryDatasource(
        TenantDBNameResolver resolver,
        CatalogResolver catalogResolver,
        @Qualifier("databricksPrimaryConfig") HikariConfig config
    ) {
        return new DatabricksSQLDataSource(resolver, catalogResolver, config, oauth);
    }

    @Bean
    public DataSource databricksSecondaryDatasource(
        TenantDBNameResolver resolver,
        CatalogResolver catalogResolver,
        @Qualifier("databricksSecondaryConfig") HikariConfig config
    ) {
        return new DatabricksSQLDataSource(resolver, catalogResolver, config, oauth);
    }

    @Bean
    @Primary
    public JdbcTemplate databricksJDBCTemplate(@Qualifier("databricksPrimaryDatasource") DataSource datasource) {
        return new JdbcTemplate(datasource);
    }

    com.querydsl.sql.Configuration querydslConfiguration() {
        SQLTemplates templates = new DatabricksQueryDSLTemplates();

        com.querydsl.sql.Configuration config = new com.querydsl.sql.Configuration(templates);
        config.register(new DateTimeType());
        config.register(new LocalDateType());

        config.setExceptionTranslator(new SpringExceptionTranslator() {
            @Override
            public RuntimeException translate(SQLException e) {
                RuntimeException ex = super.translate(e);
                if (ex == null) {
                    ex = new RuntimeException("Unknown SQL error", e);
                }

                return ex;
            }

            @Override
            public RuntimeException translate(String sql, List<Object> bindings, SQLException e) {
                RuntimeException ex = super.translate(sql, bindings, e);
                if (ex == null) {
                    ex = new RuntimeException("Unknown SQL error", e);
                }

                return ex;
            }
        });

        return config;
    }

    @Primary
    @Bean(name = OTInsightsConstants.DatabricksBeans.QUERY_FACTORY)
    public SQLQueryFactory databricksPrimaryQueryFactory(@Qualifier("databricksPrimaryDatasource") DataSource source) {
        SpringConnectionProvider provider = new SpringConnectionProvider(source);
        return new SQLQueryFactory(querydslConfiguration(), provider);
    }

    @Bean(name = OTInsightsConstants.DatabricksBeans.SECONDARY_QUERY_FACTORY)
    public SQLQueryFactory databricksSecondaryQueryFactory(@Qualifier("databricksSecondaryDatasource") DataSource source) {
        SpringConnectionProvider provider = new SpringConnectionProvider(source);
        return new SQLQueryFactory(querydslConfiguration(), provider);
    }

    private LocalContainerEntityManagerFactoryBean entityManager(EntityManagerFactoryBuilder builder, DataSource datasource, String unit) {
        Map<String, String> properties = new TreeMap<>();
        properties.put("hibernate.default_schema", null);
        properties.put("hibernate.dialect", DatabricksDialect.class.getName());

        return builder
            .dataSource(datasource)
            .persistenceUnit(unit)
            .packages(
                "com.onetrust.otinsightscommand.domain.databricks.tenant",
                "com.onetrust.otinsightscommand.domain.tenant.databricks",
                "com.onetrust.otinsightscommand.domain.databricks.shared"
            )
            .properties(properties)
            .build();
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean databricksEntityManager(
        EntityManagerFactoryBuilder builder,
        @Qualifier("databricksPrimaryDatasource") DataSource datasource
    ) {
        return entityManager(builder, datasource, "databricksPrimary");
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean databricksSecondaryEntityManager(
        EntityManagerFactoryBuilder builder,
        @Qualifier("databricksSecondaryDatasource") DataSource datasource
    ) {
        return entityManager(builder, datasource, "databricksSecondary");
    }

    /**
     * Creates a transaction manager for Databricks database WRITE operations.
     * <p>
     * This bean provides explicit transaction management for Databricks write operations
     * since the standard {@code @Transactional} annotation does not work reliably with
     * the Databricks SQL connector. The transaction manager is configured to work with
     * the Databricks EntityManagerFactory to ensure proper transaction boundaries.
     * </p>
     *
     * @param factory the Databricks EntityManagerFactory used for database operations
     * @return a {@link PlatformTransactionManager} configured for Databricks transactions
     */
    @Bean(name = "databricksTransactionManager")
    public PlatformTransactionManager databricksTransactionManager(
        @Qualifier("databricksEntityManager") EntityManagerFactory factory
    ) {
        return new JpaTransactionManager(factory);
    }
}
