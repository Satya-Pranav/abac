package com.onetrust.otinsightscommand.databrickssql.config;

import com.onetrust.otinsightscommand.constant.OTInsightsConstants;
import com.querydsl.sql.MySQLTemplates;
import com.querydsl.sql.SQLQueryFactory;
import com.querydsl.sql.SQLTemplates;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.function.Supplier;
import javax.sql.DataSource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("!it")
@ConditionalOnMissingBean(DatabricksSQLConfig.class)
public class NoOpDatabricksSqlConfig {
    private SQLQueryFactory queryFactory() {
        log.info("Do Nothing! This is meant for env where dbx is not enabled.");
        Supplier<Connection> connectionFactory = () -> {
            throw new RuntimeException("NoOp databricks connection factory");
        };

        SQLTemplates sqlTemplates = MySQLTemplates.builder().build();
        return new SQLQueryFactory(new com.querydsl.sql.Configuration(sqlTemplates), connectionFactory);
    }

    @Primary
    @Bean(name = OTInsightsConstants.DatabricksBeans.QUERY_FACTORY)
    public SQLQueryFactory databricksPrimaryQueryFactory() {
        return queryFactory();
    }

    @Bean(name = OTInsightsConstants.DatabricksBeans.SECONDARY_QUERY_FACTORY)
    public SQLQueryFactory databricksSecondaryQueryFactory() {
        return queryFactory();
    }

    @Bean
    public JdbcTemplate databricksJDBCTemplate() {
        log.info("Do Nothing! This is meant for env where dbx is not enabled.");
        DataSource datasource = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[]{DataSource.class},
                (object, method, args) -> {
                    throw new RuntimeException("NoOp databricks DataSource");
                });

        return new JdbcTemplate(datasource);
    }
}
