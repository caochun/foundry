package org.openfoundry.foundation.api;

import graphql.Scalars;
import graphql.schema.*;
import org.openfoundry.foundation.actions.ActionResult;
import org.openfoundry.foundation.spi.schema.*;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared runtime/SDL contract for generated action inputs and results. */
final class GraphqlActionTypes {
    private GraphqlActionTypes() {}

    static void validateNames(OntologySchema schema, Collection<ActionTypeDefinition> actions) {
        var names = new HashSet<String>();
        schema.objectTypes().forEach(type -> names.add(type.name()));
        schema.linkTypes().forEach(type -> names.add(type.name()));
        schema.interfaces().forEach(type -> names.add(type.name()));
        names.addAll(schema.enums().keySet());
        for (String scalar : PropertyValues.SCALARS) if (names.contains(scalar)) throw new IllegalArgumentException("Reserved GraphQL scalar name: " + scalar);
        for (String name : List.of("Query", "Mutation")) if (names.contains(name)) throw new IllegalArgumentException("Reserved GraphQL type name: " + name);
        if (actions.isEmpty()) return;
        for (String name : List.of("ActionError", "AffectedObject", "ChangeType")) reserve(names, name);
        var mutations = new HashSet<String>();
        for (var action : actions) {
            if (!action.parameters().isEmpty()) reserve(names, action.name() + "Input");
            reserve(names, action.name() + "Result");
            String field = Character.toLowerCase(action.name().charAt(0)) + action.name().substring(1);
            if (!mutations.add(field)) throw new IllegalArgumentException("Duplicate generated mutation: " + field);
        }
    }

    private static void reserve(Set<String> names, String name) {
        if (!names.add(name)) throw new IllegalArgumentException("Generated GraphQL type conflicts with ontology: " + name);
    }

    static GraphQLInputObjectType input(ActionTypeDefinition action, OntologySchema schema, Map<String, GraphQLEnumType> enums) {
        var input = GraphQLInputObjectType.newInputObject().name(action.name() + "Input");
        for (var parameter : action.parameters()) {
            GraphQLInputType type = inputType(parameter.type(), schema, enums);
            if (parameter.required()) type = GraphQLNonNull.nonNull(type);
            input.field(GraphQLInputObjectField.newInputObjectField().name(parameter.name()).type(type));
        }
        return input.build();
    }

    private static GraphQLInputType inputType(String type, OntologySchema schema, Map<String, GraphQLEnumType> enums) {
        if (type.endsWith("!")) return GraphQLNonNull.nonNull(inputType(type.substring(0, type.length() - 1), schema, enums));
        if (type.startsWith("[") && type.endsWith("]")) return GraphQLList.list(inputType(type.substring(1, type.length() - 1), schema, enums));
        if (schema.objectTypes().stream().anyMatch(object -> object.name().equals(type))) return Scalars.GraphQLID;
        if (enums.containsKey(type)) return enums.get(type);
        if (GraphqlValueScalars.TYPES.containsKey(type)) return GraphqlValueScalars.TYPES.get(type);
        return switch (type) {
            case "ID" -> Scalars.GraphQLID;
            case "String" -> Scalars.GraphQLString;
            case "Int" -> Scalars.GraphQLInt;
            case "Float" -> Scalars.GraphQLFloat;
            case "Boolean" -> Scalars.GraphQLBoolean;
            default -> throw new IllegalArgumentException("Unsupported Action input type: " + type);
        };
    }

    static String inputTypeName(String type, OntologySchema schema) {
        if (type.endsWith("!")) return inputTypeName(type.substring(0, type.length() - 1), schema) + "!";
        if (type.startsWith("[") && type.endsWith("]")) return "[" + inputTypeName(type.substring(1, type.length() - 1), schema) + "]";
        if (schema.objectTypes().stream().anyMatch(object -> object.name().equals(type))) return "ID";
        if (PropertyValues.SCALARS.contains(type) || schema.enums().containsKey(type)) return type;
        throw new IllegalArgumentException("Unsupported Action input type: " + type);
    }

    static GraphQLObjectType errors() {
        return GraphQLObjectType.newObject().name("ActionError")
                .field(GraphQLFieldDefinition.newFieldDefinition().name("code").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("message").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("field").type(Scalars.GraphQLString))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("sideEffect").type(Scalars.GraphQLString)).build();
    }

    static GraphQLObjectType affected() {
        var changes = GraphQLEnumType.newEnum().name("ChangeType");
        for (var value : ActionResult.ChangeType.values()) changes.value(value.name(), value);
        return GraphQLObjectType.newObject().name("AffectedObject")
                .field(GraphQLFieldDefinition.newFieldDefinition().name("typeName").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("id").type(GraphQLNonNull.nonNull(Scalars.GraphQLID)))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("changeType").type(GraphQLNonNull.nonNull(changes.build()))).build();
    }

    static GraphQLObjectType result(ActionTypeDefinition action, GraphQLObjectType errors, GraphQLObjectType affected) {
        return GraphQLObjectType.newObject().name(action.name() + "Result")
                .field(GraphQLFieldDefinition.newFieldDefinition().name("success").type(GraphQLNonNull.nonNull(Scalars.GraphQLBoolean)))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("actionId").type(GraphQLNonNull.nonNull(Scalars.GraphQLID)))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("status").type(GraphQLNonNull.nonNull(Scalars.GraphQLString)))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("errors").type(GraphQLList.list(GraphQLNonNull.nonNull(errors))))
                .field(GraphQLFieldDefinition.newFieldDefinition().name("affectedObjects").type(GraphQLList.list(GraphQLNonNull.nonNull(affected)))
                        .dataFetcher(environment -> ((ActionResult) environment.getSource()).changes())).build();
    }

    static Map<String, Object> legacyResult(ActionResult result) {
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("success", result.success());
        response.put("actionId", result.actionId());
        response.put("affected", result.affected());
        response.put("status", result.status());
        response.put("errors", result.errors().stream().map(error -> Map.of("code", error.code(), "sideEffect", error.sideEffect())).toList());
        return response;
    }

    static String sharedSdl() {
        return """
                enum ChangeType { CREATED UPDATED DELETED RESTORED UNKNOWN }
                type ActionError { code: String! message: String! field: String sideEffect: String }
                type AffectedObject { typeName: String! id: ID! changeType: ChangeType! }

                """;
    }
}
