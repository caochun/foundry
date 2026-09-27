package org.openfoundry.foundation.api;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.*;
import graphql.schema.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Wire scalars use the same normal forms as storage attributes. */
final class GraphqlValueScalars {
    private static final OntologySchema EMPTY = new OntologySchema("scalars", "1.0.0", List.of(), List.of(), List.of());
    static final Map<String, GraphQLScalarType> TYPES = java.util.stream.Stream.of("Date", "DateTime", "Duration", "URI", "JSON", "GeoPoint")
            .collect(java.util.stream.Collectors.toUnmodifiableMap(type -> type, GraphqlValueScalars::build));

    private static GraphQLScalarType build(String name) {
        return GraphQLScalarType.newScalar().name(name).coercing(new Coercing<Object, Object>() {
            @Override
            public Object serialize(Object value, GraphQLContext context, Locale locale) {
                try { return normalize(name, value); }
                catch (RuntimeException invalid) { throw new CoercingSerializeException("Invalid " + name + " value"); }
            }

            @Override
            public Object parseValue(Object value, GraphQLContext context, Locale locale) {
                try { return normalize(name, value); }
                catch (RuntimeException invalid) { throw new CoercingParseValueException("Invalid " + name + " value"); }
            }

            @Override
            public Object parseLiteral(Value<?> value, CoercedVariables variables, GraphQLContext context, Locale locale) {
                try { return normalize(name, literal(value)); }
                catch (RuntimeException invalid) { throw new CoercingParseLiteralException("Invalid " + name + " literal"); }
            }
        }).build();
    }

    private static Object normalize(String type, Object value) {
        return value == null ? null : PropertyValues.normalize(EMPTY, type, value, "$scalar");
    }

    private static Object literal(Value<?> input) {
        if (input instanceof StringValue value) return value.getValue();
        if (input instanceof BooleanValue value) return value.isValue();
        if (input instanceof IntValue value) return value.getValue();
        if (input instanceof FloatValue value) return value.getValue();
        if (input instanceof NullValue) return null;
        if (input instanceof EnumValue value) return value.getName();
        if (input instanceof ArrayValue value) return value.getValues().stream().map(GraphqlValueScalars::literal).toList();
        if (input instanceof ObjectValue value) {
            var result = new java.util.LinkedHashMap<String, Object>();
            for (var field : value.getObjectFields()) {
                if (result.containsKey(field.getName())) throw new IllegalArgumentException("Duplicate object field");
                result.put(field.getName(), literal(field.getValue()));
            }
            return result;
        }
        throw new IllegalArgumentException("Unsupported literal");
    }
}
