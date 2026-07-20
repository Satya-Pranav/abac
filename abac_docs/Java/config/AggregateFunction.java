package com.onetrust.otinsightscommand.databrickssql.config;

import java.util.List;
import java.util.function.Supplier;

import org.hibernate.metamodel.mapping.BasicValuedMapping;
import org.hibernate.metamodel.mapping.MappingModelExpressible;
import org.hibernate.query.ReturnableType;
import org.hibernate.query.sqm.function.FunctionKind;
import org.hibernate.query.sqm.function.NamedSqmFunctionDescriptor;
import org.hibernate.query.sqm.produce.function.FunctionReturnTypeResolver;
import org.hibernate.query.sqm.tree.SqmTypedNode;
import org.hibernate.sql.ast.SqlAstNodeRenderingMode;
import org.hibernate.sql.ast.tree.SqlAstNode;
import org.hibernate.type.BasicTypeReference;
import org.hibernate.type.spi.TypeConfiguration;

/**
 * Copy of {@link org.hibernate.dialect.function.StandardSQLFunction}, but with FunctionKind set to AGGREGATE.
 */
public class AggregateFunction<T> extends NamedSqmFunctionDescriptor {
    private final BasicTypeReference<T> type;

    public AggregateFunction(String name) {
        this(name, null);
    }

    public AggregateFunction(String name, BasicTypeReference<T> type) {
        super(
            name,
            true,
            null,
            new FunctionReturnTypeResolver() {
                @Override
                public ReturnableType<?> resolveFunctionReturnType(
                    ReturnableType<?> impliedType,
                    Supplier<MappingModelExpressible<?>> inferredTypeSupplier,
                    List<? extends SqmTypedNode<?>> arguments,
                    TypeConfiguration typeConfiguration
                ) {
                    return type == null ? null : typeConfiguration.getBasicTypeRegistry().resolve(type);
                }

                @Override
                public BasicValuedMapping resolveFunctionReturnType(
                    Supplier<BasicValuedMapping> impliedTypeAccess,
                    List<? extends SqlAstNode> arguments
                ) {
                    return type == null || impliedTypeAccess == null ? null : impliedTypeAccess.get();
                }
            },
            null,
            name,
            FunctionKind.AGGREGATE,
            null,
            SqlAstNodeRenderingMode.DEFAULT
        );

        this.type = type;
    }

    public BasicTypeReference<T> getType() {
        return type;
    }
}
