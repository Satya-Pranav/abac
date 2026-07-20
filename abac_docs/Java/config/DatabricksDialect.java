package com.onetrust.otinsightscommand.databrickssql.config;

import java.sql.CallableStatement;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;
import java.util.function.BiConsumer;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.TypeContributions;
import org.hibernate.dialect.DatabaseVersion;
import org.hibernate.dialect.MySQLDialect;
import org.hibernate.dialect.function.StandardSQLFunction;
import org.hibernate.dialect.pagination.AbstractLimitHandler;
import org.hibernate.dialect.pagination.LimitHandler;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelper;
import org.hibernate.engine.jdbc.env.spi.IdentifierHelperBuilder;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.query.ReturnableType;
import org.hibernate.query.spi.Limit;
import org.hibernate.query.sqm.function.SqmFunctionRegistry;
import org.hibernate.service.ServiceRegistry;
import org.hibernate.sql.ast.SqlAstNodeRenderingMode;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.SqlAstTranslatorFactory;
import org.hibernate.sql.ast.SqlAstWalker;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.spi.StandardSqlAstTranslatorFactory;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.sql.ast.tree.Statement;
import org.hibernate.sql.ast.tree.expression.Distinct;
import org.hibernate.sql.ast.tree.expression.Expression;
import org.hibernate.sql.ast.tree.expression.QueryLiteral;
import org.hibernate.sql.ast.tree.predicate.Predicate;
import org.hibernate.sql.ast.tree.select.SortSpecification;
import org.hibernate.sql.exec.spi.JdbcOperation;
import org.hibernate.type.SqlTypes;
import org.hibernate.type.StandardBasicTypes;
import org.hibernate.type.descriptor.ValueExtractor;
import org.hibernate.type.descriptor.WrapperOptions;
import org.hibernate.type.descriptor.java.JavaType;
import org.hibernate.type.descriptor.jdbc.BasicExtractor;
import org.hibernate.type.descriptor.jdbc.DateJdbcType;
import org.hibernate.type.descriptor.jdbc.JsonAsStringJdbcType;
import org.hibernate.type.descriptor.jdbc.spi.JdbcTypeRegistry;
import org.hibernate.type.descriptor.sql.internal.DdlTypeImpl;
import org.hibernate.type.descriptor.sql.spi.DdlTypeRegistry;

@Slf4j
public class DatabricksDialect extends MySQLDialect {
    private static final LimitHandler HANDLER = new AbstractLimitHandler() {
        @Override
        public String processSql(String sql, Limit limit) {
            final boolean hasOffset = AbstractLimitHandler.hasFirstRow(limit);

            // OFFSET is supported in the preview databricks version 2022.35, may need to change this to SQLServer2012LimitHandler instead
            // Note that SQLServer2012LimitHandler uses top when there is no offset, so that will need to be overridden
            return sql + (hasOffset ? " limit ? offset ?" : " limit ?");
        }

        @Override
        public boolean supportsLimit() {
            return true;
        }

        @Override
        public boolean bindLimitParametersInReverseOrder() {
            return true;
        }
    };

    public DatabricksDialect() {
        // Since this extends MySQLDialect, set version to 8 to enable certain features.
        super(DatabaseVersion.make(8));
    }

    @Override
    public SqlAstTranslatorFactory getSqlAstTranslatorFactory() {
        return new StandardSqlAstTranslatorFactory() {
            @Override
            protected <T extends JdbcOperation> SqlAstTranslator<T> buildTranslator(SessionFactoryImplementor sessionFactory, Statement statement) {
                return new DatabricksASTTranslator<>(sessionFactory, statement);
            }
        };
    }

    @Override
    public void initializeFunctionRegistry(FunctionContributions functionContributions) {
        super.initializeFunctionRegistry(functionContributions);

        registerFunctions(functionContributions);
    }

    void registerFunctions(FunctionContributions functionContributions) {
        SqmFunctionRegistry registry = functionContributions.getFunctionRegistry();

        registry.register(
            "all",
            new StandardSQLFunction("all", StandardBasicTypes.STRING) {
                @Override
                public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
                    sql.append(" ALL ");
                }
            }
        );

        registry.register("array_contains", new StandardSQLFunction("array_contains", StandardBasicTypes.BOOLEAN));
        registry.register("array_sort", new StandardSQLFunction("array_sort", StandardBasicTypes.STRING));
        registry.register("sort_array", new StandardSQLFunction("sort_array", StandardBasicTypes.STRING));
        registry.register("arrays_zip", new StandardSQLFunction("arrays_zip", StandardBasicTypes.STRING));
        registry.register("explode", new StandardSQLFunction("explode", StandardBasicTypes.STRING));
        registry.register("flatten", new StandardSQLFunction("flatten", StandardBasicTypes.STRING));
        registry.register("named_struct", new StandardSQLFunction("named_struct", StandardBasicTypes.STRING));

        registry.register(
            "null",
            new StandardSQLFunction("null", StandardBasicTypes.STRING) {
                @Override
                public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
                    sql.append("NULL");
                }
            }
        );

        // TODO: See if there is a way to remove this.
        registry.register(
            "dynamic_cast",
            new StandardSQLFunction("dynamic_cast", StandardBasicTypes.OBJECT_TYPE) {
                @Override
                public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
                    sql.append("cast(");
                    arguments.get(0).accept(translator);
                    sql.append(" as ");

                    @SuppressWarnings("unchecked")
                    QueryLiteral<String> type = (QueryLiteral<String>) arguments.get(1);
                    sql.append(type.getLiteralValue());
                    sql.append(")");
                }
            }
        );

        registry.register("to_json", new StandardSQLFunction("to_json", StandardBasicTypes.STRING));

        registry.register("array_agg", new AggregateFunction<>("array_agg", StandardBasicTypes.STRING));

        registry.register(
            "array_agg_distinct",
            new AggregateFunction<>("array_agg", StandardBasicTypes.STRING) {
                private List<? extends SqlAstNode> makeDistinct(List<? extends SqlAstNode> arguments) {
                    Expression e = (Expression) arguments.get(0);
                    Distinct d = new Distinct(e);
                    return List.of(d);
                }

                @Override
                public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
                    super.render(sql, makeDistinct(arguments), rt, translator);
                }

                @Override
                public void render(
                    SqlAppender sql,
                    List<? extends SqlAstNode> arguments,
                    Predicate filter,
                    Boolean respectNulls,
                    Boolean fromFirst,
                    ReturnableType<?> rt,
                    SqlAstTranslator<?> walker
                ) {
                    super.render(sql, makeDistinct(arguments), filter, respectNulls, fromFirst, rt, walker);
                }

                @Override
                public void render(
                    SqlAppender sql,
                    List<? extends SqlAstNode> arguments,
                    Predicate filter,
                    List<SortSpecification> withinGroup,
                    ReturnableType<?> rt,
                    SqlAstTranslator<?> translator
                ) {
                    super.render(sql, makeDistinct(arguments), filter, withinGroup, rt, translator);
                }

                @Override
                public void render(
                    SqlAppender sql,
                    List<? extends SqlAstNode> arguments,
                    Predicate filter,
                    ReturnableType<?> rt,
                    SqlAstTranslator<?> translator
                ) {
                    super.render(sql, makeDistinct(arguments), filter, rt, translator);
                }
            }
        );

        registry.register(
            "transform_extract",
            new StandardSQLFunction("transform", StandardBasicTypes.STRING) {
                @Override
                @SuppressWarnings("unchecked")
                public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
                    SqlAstNode lambda = (SqlAstWalker saw) -> {
                        sql.append("x -> x");
                        for (int i = 1; i < arguments.size(); i++) {
                            QueryLiteral<String> field = (QueryLiteral<String>) arguments.get(i);
                            String fieldName = field.getLiteralValue();

                            sql.append(".`");
                            sql.append(fieldName);
                            sql.append("`");
                        }
                    };

                    super.render(sql, List.of(arguments.get(0), lambda), rt, translator);
                }
            }
        );

        registry.register(
            "transform_extract_struct",
            new StandardSQLFunction("transform", StandardBasicTypes.STRING) {
                @Override
                @SuppressWarnings("unchecked")
                public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
                    SqlAstNode lambda = (SqlAstWalker saw) -> {
                        sql.append("x -> struct(");
                        for (int i = 1; i < arguments.size(); i++) {
                            QueryLiteral<String> field = (QueryLiteral<String>) arguments.get(i);
                            String fieldName = field.getLiteralValue();

                            if (i > 1) {
                                sql.append(", ");
                            }

                            sql.append("x.`");
                            sql.append(fieldName);
                            sql.append("`");
                        }

                        sql.append(")");
                    };

                    super.render(sql, List.of(arguments.get(0), lambda), rt, translator);
                }
            }
        );

        registry.register(
            "transform_extract_value",
            new StandardSQLFunction("transform", StandardBasicTypes.STRING) {
                @Override
                @SuppressWarnings("unchecked")
                public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
                    SqlAstNode lambda = (SqlAstWalker saw) -> sql.append("x -> x.value");
                    ((List) arguments).add(lambda);
                    super.render(sql, arguments, rt, translator);
                }
            }
        );

        registry.register(
            "array_sort_byID",
            new StandardSQLFunction("array_sort", StandardBasicTypes.STRING) {
                @Override
                @SuppressWarnings("unchecked")
                public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
                    super.render(sql, getSortSQL(sql, arguments), rt, translator);
                }
            }
        );

        registry.register(
            "array_sort_byValue",
            new StandardSQLFunction("array_sort", StandardBasicTypes.STRING) {
                @Override
                @SuppressWarnings("unchecked")
                public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
                    super.render(sql, getSortSQLByValue(sql, arguments), rt, translator);
                }
            }
        );

        registry.register(
            "array_sort_byValueKey",
            new StandardSQLFunction("array_sort", StandardBasicTypes.STRING) {
                @Override
                @SuppressWarnings("unchecked")
                public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
                    super.render(sql, getSortSQLByValueKey(sql, arguments), rt, translator);
                }
            }
        );

        registry.register(
            "filter_non_null",
            new StandardSQLFunction("filter", StandardBasicTypes.STRING) {
                @Override
                @SuppressWarnings("unchecked")
                public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
                    SqlAstNode lambda = (SqlAstWalker saw) -> sql.append("x -> x IS NOT NULL");
                    ((List<SqlAstNode>) arguments).add(lambda);
                    super.render(sql, arguments, rt, translator);
                }
            }
        );

        registry.register(
            "filter_field_non_null",
            new StandardSQLFunction("filter", StandardBasicTypes.STRING) {
                @Override
                @SuppressWarnings("unchecked")
                public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
                    SqlAstNode lambda = (SqlAstWalker saw) -> {
                        sql.append("x -> x");

                        for (int i = 1; i < arguments.size(); i++) {
                            @SuppressWarnings("unchecked")
                            QueryLiteral<String> field = (QueryLiteral<String>) arguments.get(i);
                            String fieldName = field.getLiteralValue();

                            sql.append(".`");
                            sql.append(fieldName.replace("`", "``"));
                            sql.append("`");
                        }

                        sql.append(" IS NOT NULL");
                    };

                    super.render(sql, List.of(arguments.get(0), lambda), rt, translator);
                }
            }
        );

        registry.register("element_at", new StandardSQLFunction("element_at", StandardBasicTypes.STRING));

        registry.register(
            "field_at",
            new StandardSQLFunction("field_at", StandardBasicTypes.STRING) {
                @Override
                public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
                    SqlAstNode parent = arguments.get(0);

                    translator.render(parent, SqlAstNodeRenderingMode.DEFAULT);

                    for (int i = 1; i < arguments.size(); i++) {
                        @SuppressWarnings("unchecked")
                        QueryLiteral<String> field = (QueryLiteral<String>) arguments.get(i);
                        String fieldName = field.getLiteralValue();

                        sql.append(".`");
                        sql.append(fieldName.replace("`", "``"));
                        sql.append("`");
                    }
                }
            }
        );

        class WithCommentFunction extends StandardSQLFunction {
            private final boolean prefix;

            private WithCommentFunction(boolean prefix) {
                super("with_comment_" + (prefix ? "prefix" : "postfix"), StandardBasicTypes.STRING);

                this.prefix = prefix;
            }

            @Override
            public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
                QueryLiteral<?> comment = (QueryLiteral<?>) arguments.get(1);
                Object value = comment.getLiteralValue();
                SqlAstNode expr = arguments.get(0);

                if (prefix == false) {
                    expr.accept(translator);
                }

                if (value != null) {
                    sql.append(" /* ");
                    sql.append(value.toString());
                    sql.append(" */ ");
                }

                if (prefix) {
                    expr.accept(translator);
                }
            }
        }

        registry.register("with_comment_prefix", new WithCommentFunction(true));
        registry.register("with_comment_postfix", new WithCommentFunction(false));

        LambdaFunction.registerLambdaFunctions(registry::register, "exists", 2);
    }

    protected <T extends SqlAstNode> List<T> getSortSQL(SqlAppender sql, List<? extends SqlAstNode> arguments) {
        return getSortSQL(sql, arguments, "id");
    }

    protected <T extends SqlAstNode> List<T> getSortSQLByValue(SqlAppender sql, List<? extends SqlAstNode> arguments) {
        return getSortSQL(sql, arguments, "value");
    }

    protected <T extends SqlAstNode> List<T> getSortSQLByValueKey(SqlAppender sql, List<? extends SqlAstNode> arguments) {
        return getSortSQL(sql, arguments, "valueKey");
    }

    @SuppressWarnings("unchecked")
    protected <T extends SqlAstNode> List<T> getSortSQL(SqlAppender sql, List<? extends SqlAstNode> arguments, String field) {
        List<SqlAstNode> args = (List<SqlAstNode>) arguments;
        SqlAstNode lambda = (SqlAstWalker saw) -> sql.append(
            """
                    (a, b) ->
                    case when a['%s'] < b['%s'] then -1
                    when a['%s'] = b['%s'] then 0
                    else 1
                    end
                """.formatted(field, field, field, field)
        );

        args.add(lambda);
        return (List) arguments;
    }

    @Override
    public void appendBooleanValueString(SqlAppender appender, boolean bool) {
        appender.appendSql("" + bool);
    }

    @Override
    public LimitHandler getLimitHandler() {
        return HANDLER;
    }

    @Override
    public String castType(int code) {
        switch (code) {
            case Types.BIT:
            case Types.BOOLEAN:
                return "boolean";
            case Types.TINYINT:
                return "byte";
            case Types.SMALLINT:
                return "short";
            case Types.INTEGER:
                return "int";
            case Types.BIGINT:
                return "long";
            case Types.FLOAT:
                return "float";
            case Types.DOUBLE:
                return "double";
            case Types.REAL:
                return "decimal($p, $s)";
            case Types.CHAR:
            case Types.VARCHAR:
            case Types.NVARCHAR:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
                return "string";
            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                return "binary";
            default:
                return super.castType(code);
        }
    }

    @Override
    protected void registerColumnTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
        super.registerColumnTypes(typeContributions, serviceRegistry);

        DdlTypeRegistry typeRegistry = typeContributions.getTypeConfiguration().getDdlTypeRegistry();

        BiConsumer<Integer, String> r = (type, name) -> typeRegistry.addDescriptor(new DdlTypeImpl(type, name, name, this));

        r.accept(SqlTypes.DATE, "date");
        r.accept(SqlTypes.TIME, "timestamp");
        r.accept(SqlTypes.TIMESTAMP, "timestamp");
        r.accept(SqlTypes.TIMESTAMP_UTC, "timestamp");
        r.accept(SqlTypes.BOOLEAN, "boolean");
        r.accept(SqlTypes.FLOAT, "float");
        r.accept(SqlTypes.VARCHAR, "string");
        r.accept(SqlTypes.NVARCHAR, "string");
        r.accept(SqlTypes.LONGVARCHAR, "string");
        r.accept(SqlTypes.LONGNVARCHAR, "string");
        r.accept(SqlTypes.VARBINARY, "binary");
        r.accept(SqlTypes.LONGVARBINARY, "binary");
        r.accept(SqlTypes.JSON, "string");
    }

    @Override
    public void contributeTypes(TypeContributions typeContributions, ServiceRegistry serviceRegistry) {
        super.contributeTypes(typeContributions, serviceRegistry);

        JdbcTypeRegistry registry = typeContributions.getTypeConfiguration().getJdbcTypeRegistry();
        registry.addDescriptor(SqlTypes.JSON, JsonAsStringJdbcType.VARCHAR_INSTANCE);

        registry.addDescriptor(
            SqlTypes.DATE,
            new DateJdbcType() {
                @Override
                public <X> ValueExtractor<X> getExtractor(JavaType<X> javaType) {
                    return new BasicExtractor<X>(javaType, this) {
                        private java.sql.Date extract(Object o, SQLException e) {
                            java.sql.Date d;
                            if (o == null) {
                                d = null;
                            } else if (o instanceof java.util.Date jd) {
                                d = new java.sql.Date(jd.getTime());
                            } else {
                                throw new UnsupportedOperationException("Failed to extract java.sql.Date from type " + o.getClass() + ", " + o, e);
                            }

                            return d;
                        }

                        @Override
                        protected X doExtract(ResultSet rs, int paramIndex, WrapperOptions options) throws SQLException {
                            java.sql.Date d;
                            try {
                                d = rs.getDate(paramIndex);
                            } catch (SQLException e) {
                                Object o = rs.getObject(paramIndex);
                                d = extract(o, e);
                            }

                            return javaType.wrap(d, options);
                        }

                        @Override
                        protected X doExtract(CallableStatement statement, int index, WrapperOptions options) throws SQLException {
                            java.sql.Date d;
                            try {
                                d = statement.getDate(index);
                            } catch (SQLException e) {
                                Object o = statement.getObject(index);
                                d = extract(o, e);
                            }

                            return javaType.wrap(d, options);
                        }

                        @Override
                        protected X doExtract(CallableStatement statement, String name, WrapperOptions options) throws SQLException {
                            java.sql.Date d;
                            try {
                                d = statement.getDate(name);
                            } catch (SQLException e) {
                                Object o = statement.getObject(name);
                                d = extract(o, e);
                            }

                            return javaType.wrap(d, options);
                        }
                    };
                }
            }
        );
    }

    @Override
    public String getTableTypeString() {
        return "";
    }

    @Override
    public String getNullColumnString(String columnType) {
        return "";
    }

    @Override
    public char openQuote() {
        return '`';
    }

    @Override
    public char closeQuote() {
        return '`';
    }

    @Override
    public IdentifierHelper buildIdentifierHelper(IdentifierHelperBuilder builder, DatabaseMetaData dbMetaData) throws SQLException {
        builder.setGloballyQuoteIdentifiers(false);
        return super.buildIdentifierHelper(builder, dbMetaData);
    }

    @Override
    public boolean supportsNonQueryWithCTE() {
        return true;
    }

    @Override
    public int getPreferredSqlTypeCodeForBoolean() {
        return Types.BOOLEAN;
    }
}
