package com.onetrust.otinsightscommand.databrickssql.config;

import com.onetrust.otinsightscommand.config.ApplicationContextProvider;
import com.onetrust.otinsightscommand.databrickssql.service.TenantDBNameResolver;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.dialect.MySQLSqlAstTranslator;
import org.hibernate.engine.jdbc.spi.JdbcServices;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.JdbcMapping;
import org.hibernate.sql.ast.spi.ParameterMarkerStrategy;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.ast.tree.cte.CteStatement;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.Literal;
import org.hibernate.sql.ast.tree.expression.LiteralAsParameter;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.type.descriptor.jdbc.JdbcLiteralFormatter;

import java.util.Optional;

@Slf4j
public final class DatabricksASTTranslator<T extends JdbcOperation> extends MySQLSqlAstTranslator<T> {
    private static final ThreadLocal<Boolean> IN_CTE = new ThreadLocal<>();
    private final ParameterMarkerStrategy parameterMarkerStrategy;

    public DatabricksASTTranslator(SessionFactoryImplementor sessionFactory, Statement statement) {
        super(sessionFactory, statement);
        final JdbcServices jdbcServices = sessionFactory.getJdbcServices();
        parameterMarkerStrategy = jdbcServices.getParameterMarkerStrategy();

        String db = null;
        try {
            db = ApplicationContextProvider.getApplicationContext().getBean(TenantDBNameResolver.class).getDBNameForTenantUUID();
        } catch (Exception e) {
            log.warn("Failed to get tenant database", e);
        }

        if (db != null) {
            appendSql("/* ");
            appendSql(db);
            appendSql(" */");
        }
    }

    @Override
    protected void renderCombinedLimitClause(Expression offsetExpression, Expression fetchExpression) {
        renderLimitOffsetClause(offsetExpression, fetchExpression);
    }

    @Override
    protected void renderLimitOffsetClause(Expression offsetExpression, Expression fetchExpression) {
        if (Optional.ofNullable(IN_CTE.get()).orElse(false)) {
            // Don't render LIMIT or OFFSET inside the CTE
            return;
        }

        if (fetchExpression != null) {
            appendSql(" limit ");
            fetchExpression.accept(this);
        }

        if (offsetExpression != null) {
            appendSql(" offset ");
            offsetExpression.accept(this);
        }
    }

    @Override
    protected void visitCteDefinition(CteStatement cte) {
        Boolean inCTE = IN_CTE.get();
        try {
            IN_CTE.set(true);
            super.visitCteDefinition(cte);
        } finally {
            if (inCTE == null) {
                IN_CTE.remove();
            } else {
                IN_CTE.set(inCTE);
            }
        }
    }

    @Override
    protected boolean supportsWithClause() {
        return true;
    }

    @Override
    protected boolean supportsWithClauseInSubquery() {
        return true;
    }

    /**
     * Overridden from AbstractSqlAstTranslator
     * to enforce parameter binding for character literal and prevent incorrect escape sequence interpretation
     *
     * @param literal
     * @param castParameter
     */
    @Override
    @SuppressWarnings("unchecked")
    protected void renderLiteral(Literal literal, boolean castParameter) {
        assert literal.getExpressionType().getJdbcTypeCount() == 1;

        final JdbcMapping jdbcMapping = literal.getJdbcMapping();
        final JdbcLiteralFormatter<Object> literalFormatter = jdbcMapping.getJdbcLiteralFormatter();

        if (literalFormatter == null || literal.getLiteralValue() instanceof CharSequence) {
            getParameterBinders().add(literal);
            final String marker = parameterMarkerStrategy.createMarker(
                    getParameterBinders().size(),
                    literal.getJdbcMapping().getJdbcType());
            final LiteralAsParameter<Object> jdbcParameter = new LiteralAsParameter<>(literal, marker);
            if (castParameter) {
                renderCasted(jdbcParameter);
            } else {
                appendSql(PARAM_MARKER);
            }
        } else {
            literalFormatter.appendJdbcLiteral(
                    this,
                    literal.getLiteralValue(),
                    getDialect(),
                    getWrapperOptions()
            );
        }
    }
}
