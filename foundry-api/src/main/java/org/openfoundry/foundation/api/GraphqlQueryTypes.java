package org.openfoundry.foundation.api;

import graphql.Scalars;
import graphql.schema.*;
import org.openfoundry.foundation.spi.schema.*;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared query declarations. Existing list fields are retained; connections are additive. */
final class GraphqlQueryTypes {
    private GraphqlQueryTypes() {}

    static Set<String> names(OntologySchema schema) {
        var names = new HashSet<String>();
        reserve(names, "SortDirection");
        reserve(names, "PageInfo");
        reserve(names, "SearchMode");
        for (String name : GraphqlAggregateTypes.NAMES) reserve(names, name);
        for (String scalar : scalarTypes(schema)) reserve(names, scalar + "Filter");
        var queries = new HashSet<String>();
        for (var object : schema.objectTypes()) {
            for (String suffix : List.of("Filter", "OrderBy", "Connection", "Edge")) reserve(names, object.name() + suffix);
            reserve(names, "SearchHit_" + object.name());
            reserve(names, "SearchResult_" + object.name());
            reserve(queries, "search" + object.name() + "s");
            String singular = Character.toLowerCase(object.name().charAt(0)) + object.name().substring(1);
            for (String suffix : List.of("", "s", "sConnection", "Aggregate")) reserve(queries, singular + suffix);
            for (var field : object.properties()) {
                if (Set.of("AND", "OR", "NOT").contains(field.name())) {
                    throw new IllegalArgumentException("Query logical operator conflicts with property: " + field.name());
                }
            }
        }
        var declared = new HashSet<>(schema.enums().keySet());
        schema.objectTypes().forEach(type -> declared.add(type.name()));
        schema.linkTypes().forEach(type -> declared.add(type.name()));
        schema.interfaces().forEach(type -> declared.add(type.name()));
        for (String name : names) if (declared.contains(name)) throw new IllegalArgumentException("Generated query type conflict: " + name);
        return names;
    }

    private static void reserve(Set<String> names, String name) {
        if (!names.add(name)) throw new IllegalArgumentException("Duplicate generated query name: " + name);
    }

    private static Set<String> scalarTypes(OntologySchema schema) {
        var types = new java.util.TreeSet<String>();
        for (var object : schema.objectTypes()) for (var field : object.properties()) {
            if (!ObjectQueryPlan.operators(field.type(), schema).isEmpty()) types.add(field.type());
        }
        return types;
    }

    static Map<String, GraphQLInputObjectType> inputs(OntologySchema schema, Map<String, GraphQLEnumType> enums) {
        var result = new LinkedHashMap<String, GraphQLInputObjectType>();
        var direction = GraphQLEnumType.newEnum().name("SortDirection").value("ASC", "ASC").value("DESC", "DESC").build();
        for (String type : scalarTypes(schema)) {
            var input = GraphQLInputObjectType.newInputObject().name(type + "Filter");
            GraphQLInputType scalar = GraphqlActionTypes.inputType(type, schema, enums);
            for (String operator : ObjectQueryPlan.operators(type, schema)) {
                GraphQLInputType operand = operator.equals("exists") ? Scalars.GraphQLBoolean
                        : operator.equals("in") ? GraphQLList.list(GraphQLNonNull.nonNull(scalar)) : scalar;
                input.field(GraphQLInputObjectField.newInputObjectField().name(operator).type(operand));
            }
            result.put(type + "Filter", input.build());
        }
        for (var object : schema.objectTypes()) {
            var filter = GraphQLInputObjectType.newInputObject().name(object.name() + "Filter");
            var order = GraphQLInputObjectType.newInputObject().name(object.name() + "OrderBy");
            for (var field : object.properties()) {
                if (!ObjectQueryPlan.operators(field.type(), schema).isEmpty()) {
                    filter.field(GraphQLInputObjectField.newInputObjectField().name(field.name()).type(result.get(field.type() + "Filter")));
                }
                if (ObjectQueryPlan.orderable(field.type(), schema)) {
                    order.field(GraphQLInputObjectField.newInputObjectField().name(field.name()).type(direction));
                }
            }
            var self = GraphQLTypeReference.typeRef(object.name() + "Filter");
            for (String logical : List.of("AND", "OR")) {
                filter.field(GraphQLInputObjectField.newInputObjectField().name(logical).type(GraphQLList.list(GraphQLNonNull.nonNull(self))));
            }
            filter.field(GraphQLInputObjectField.newInputObjectField().name("NOT").type(self));
            result.put(object.name() + "Filter", filter.build());
            result.put(object.name() + "OrderBy", order.build());
        }
        return result;
    }

    static GraphQLObjectType pageInfo() {
        return GraphQLObjectType.newObject().name("PageInfo")
                .field(GraphQLFieldDefinition.newFieldDefinition().name("hasNextPage").type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("hasPreviousPage").type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("startCursor").type(Scalars.GraphQLString))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("endCursor").type(Scalars.GraphQLString)).build();
    }

    static GraphQLObjectType connection(GraphQLObjectType object, GraphQLObjectType pageInfo) {
        var edge = GraphQLObjectType.newObject().name(object.getName() + "Edge")
                .field(GraphQLFieldDefinition.newFieldDefinition().name("node").type(GraphQLNonNull.nonNull(object)))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("cursor").type(GraphQLNonNull.nonNull(Scalars.GraphQLString))).build();
        return GraphQLObjectType.newObject().name(object.getName() + "Connection")
                .field(GraphQLFieldDefinition.newFieldDefinition().name("edges").type(GraphQLNonNull.nonNull(GraphQLList.list(GraphQLNonNull.nonNull(edge)))))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("pageInfo").type(GraphQLNonNull.nonNull(pageInfo)))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("totalCount").type(GraphQLNonNull.nonNull(Scalars.GraphQLInt))).build();
    }

    static GraphQLFieldDefinition.Builder arguments(GraphQLFieldDefinition.Builder field, String name,
                                                     Map<String, GraphQLInputObjectType> inputs, boolean connection) {
        field.argument(GraphQLArgument.newArgument().name("filter").type(inputs.get(name + "Filter")))
                .argument(GraphQLArgument.newArgument().name("orderBy").type(inputs.get(name + "OrderBy")))
                .argument(GraphQLArgument.newArgument().name("offset").type(Scalars.GraphQLInt).defaultValue(0));
        var first = GraphQLArgument.newArgument().name("first").type(Scalars.GraphQLInt);
        if (!connection) first.defaultValue(100);
        field.argument(first);
        if (connection) {
            field.argument(GraphQLArgument.newArgument().name("after").type(Scalars.GraphQLString));
            field.argument(GraphQLArgument.newArgument().name("last").type(Scalars.GraphQLInt));
            field.argument(GraphQLArgument.newArgument().name("before").type(Scalars.GraphQLString));
        }
        return field;
    }

    static ObjectQuery query(DataFetchingEnvironment env) {
        return ObjectQuery.fromJson(queryArguments(env));
    }

    static ObjectConnectionQuery connectionQuery(DataFetchingEnvironment env) {
        return ObjectConnectionQuery.fromJson(queryArguments(env));
    }

    private static Map<String, Object> queryArguments(DataFetchingEnvironment env) {
        var arguments = new LinkedHashMap<String, Object>(env.getArguments());
        Map<String, String> order = env.getArgument("orderBy");
        // GraphQL input objects are unordered: schema declaration order defines multi-key priority.
        var ordered = new LinkedHashMap<String, String>();
        if (order != null) {
            var input = (GraphQLInputObjectType) env.getFieldDefinition().getArgument("orderBy").getType();
            for (var field : input.getFieldDefinitions()) if (order.containsKey(field.getName())) ordered.put(field.getName(), order.get(field.getName()));
        }
        arguments.put("orderBy", ordered);
        return arguments;
    }

    static String sdl(OntologySchema schema) {
        var result = new StringBuilder("enum SortDirection { ASC DESC }\ntype PageInfo { hasNextPage: Boolean! hasPreviousPage: Boolean! startCursor: String endCursor: String }\n");
        for (String type : scalarTypes(schema)) {
            result.append("input ").append(type).append("Filter {\n");
            for (String operator : ObjectQueryPlan.operators(type, schema)) {
                result.append("  ").append(operator).append(": ").append(operator.equals("exists") ? "Boolean" : operator.equals("in") ? "[" + type + "!]" : type).append("\n");
            }
            result.append("}\n");
        }
        for (var object : schema.objectTypes()) {
            String name = object.name();
            result.append("input ").append(name).append("Filter {\n");
            for (var field : object.properties()) if (!ObjectQueryPlan.operators(field.type(), schema).isEmpty()) {
                result.append("  ").append(field.name()).append(": ").append(field.type()).append("Filter\n");
            }
            result.append("  AND: [").append(name).append("Filter!]\n  OR: [").append(name).append("Filter!]\n  NOT: ").append(name).append("Filter\n}\n");
            result.append("input ").append(name).append("OrderBy {\n");
            for (var field : object.properties()) if (ObjectQueryPlan.orderable(field.type(), schema)) {
                result.append("  ").append(field.name()).append(": SortDirection\n");
            }
            result.append("}\n");
            result.append("type ").append(name).append("Edge { node: ").append(name).append("! cursor: String! }\n");
            result.append("type ").append(name).append("Connection { edges: [").append(name).append("Edge!]! pageInfo: PageInfo! totalCount: Int! }\n");
        }
        result.append("enum SearchMode { TERMS PHRASE }\n");
        schema.objectTypes().forEach(object -> result.append(GraphqlSearchTypes.sdl(object.name())));
        result.append(GraphqlAggregateTypes.sdl());
        return result.toString();
    }
}
