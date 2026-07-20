package com.onetrust.otinsightscommand.databrickssql.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.dialect.function.StandardSQLFunction;
import org.hibernate.query.ReturnableType;
import org.hibernate.sql.ast.SqlAstTranslator;
import org.hibernate.sql.ast.spi.SqlAppender;
import org.hibernate.sql.ast.tree.SqlAstNode;

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

@Slf4j
public class LambdaFunction extends StandardSQLFunction {
    @RequiredArgsConstructor
    public static enum OP {
        EQ("="),
        NE("!="),
        LT("<"),
        LE("<="),
        GT(">"),
        GE(">="),
        IN("IN", -1),
        NOT_IN("NOT IN", -1),
        BW("BETWEEN", 2),
        GELT(">=", 2),
        IS_NULL("IS NULL", 0),
        RE("REGEXP_LIKE", 1);

        final String operator;
        final int expectedArgs;

        OP(String operator) {
            this(operator, 1);
        }
    }

    public static void registerLambdaFunctions(BiConsumer<String, StandardSQLFunction> register, String function, int arity) {
        Set<String> fields = Set.of("value", "valueKey");
        for (OP op : OP.values()) {
            LambdaFunction f = new LambdaFunction(function, arity, op);
            register.accept(f.getName(), f);

            fields.stream().map(field -> new LambdaFunction(function, arity, op, field)).forEach(func -> register.accept(func.getName(), func));
        }
    }

    private final String function;
    private final int arity;
    private final OP op;
    private final String field;

    public LambdaFunction(String function, int arity, OP op) {
        this(function, arity, op, null);
    }

    public LambdaFunction(String function, int arity, OP op, String field) {
        super(function + "_" + op.name().toLowerCase() + (field != null ? "_" + field : ""));
        this.function = function;
        this.arity = arity;
        this.op = op;
        this.field = field;
    }

    protected void validate(List<?> arguments) {
        int expected = arity - 1;
        if (op.expectedArgs > 0) {
            expected += op.expectedArgs;
        } else if (op.expectedArgs < 0) {
            if (arguments.size() < expected) {
                throw new IllegalArgumentException(getName() + " expects at least " + expected + " argument(s), not " + arguments.size());
            }

            return;
        }

        if (arguments.size() != expected) {
            throw new IllegalArgumentException(getName() + " expects " + expected + " argument(s), not " + arguments.size());
        }
    }

    @Override
    public void render(SqlAppender sql, List<? extends SqlAstNode> arguments, ReturnableType<?> rt, SqlAstTranslator<?> translator) {
        log.debug("{} arguments = {}", getName(), arguments);

        validate(arguments);

        List<? extends SqlAstNode> functionArguments = arguments.subList(0, arity - 1);
        List<? extends SqlAstNode> lambdaArguments = arguments.subList(arity - 1, arguments.size());

        sql.append(function);
        sql.append("(");
        for (int i = 0; i < functionArguments.size(); i++) {
            functionArguments.get(i).accept(translator);
            if (i != functionArguments.size() - 1) {
                sql.append(", ");
            }
        }

        String lambdaArg = "x";
        if (field != null) {
            lambdaArg += "." + field;
        }

        sql.append(", x -> ");

        if (op == OP.NOT_IN) {
            sql.append(lambdaArg);
            sql.append(" IS NULL OR ");
        } else if (op != OP.IS_NULL) {
            // IS NOT NULL is required to prevent a null return value
            sql.append(lambdaArg);
            sql.append(" IS NOT NULL AND ");
        }

        switch (op) {
            case RE:
                sql.append(op.operator);
                sql.append("(");
                sql.append(lambdaArg);
                sql.append(", ");
                break;
            default:
                sql.append(lambdaArg);
                sql.append(" ");
                sql.append(op.operator);
                sql.append(" ");
                break;
        }

        switch (op) {
            case IN:
            case NOT_IN:
                sql.append("(");

                for (int i = 0; i < lambdaArguments.size(); i++) {
                    lambdaArguments.get(i).accept(translator);
                    if (i != lambdaArguments.size() - 1) {
                        sql.append(", ");
                    }
                }

                sql.append(")");
                break;
            case BW:
                lambdaArguments.get(0).accept(translator);
                sql.append(" AND ");
                lambdaArguments.get(1).accept(translator);
                break;
            case RE:
                lambdaArguments.get(0).accept(translator);
                sql.append(")");
                break;
            case GELT:
                lambdaArguments.get(0).accept(translator);
                sql.append(" AND ");
                sql.append(lambdaArg);
                sql.append(" < ");
                lambdaArguments.get(1).accept(translator);

                break;
            default:
                if (op.expectedArgs > 0) {
                    lambdaArguments.get(0).accept(translator);
                }

                break;
        }

        sql.append(")");
    }
}
