package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;

/** Generates a stable read/action contract; resolver execution stays in ApplicationService. */
public final class GraphqlContractGenerator {
    public String generate(OntologySchema schema) {
        return generate(schema, GraphqlApiRuntime.ActionMode.TYPED);
    }

    public String generate(OntologySchema schema, GraphqlApiRuntime.ActionMode mode) {
        GraphqlActionTypes.validateNames(schema, mode == GraphqlApiRuntime.ActionMode.TYPED ? schema.actionTypes() : java.util.List.of());
        StringBuilder result = new StringBuilder("scalar JSON\nscalar Date\nscalar DateTime\nscalar Duration\nscalar URI\nscalar GeoPoint\n\n");
        schema.enums().forEach((name, values) -> result.append("enum ").append(name).append(" { ").append(String.join(" ", values)).append(" }\n"));
        for (var type : schema.interfaces()) {
            result.append("interface ").append(type.name()).append(implementsTypes(type.interfaces())).append(" {\n");
            type.properties().forEach(property -> appendProperty(result, property));
            type.linkFields().forEach(field -> appendLinkField(result, field));
            type.computedFields().forEach(field -> result.append("  ").append(field.name()).append(": ").append(field.type()).append("\n"));
            result.append("}\n\n");
        }
        for (ObjectTypeDefinition object : schema.objectTypes()) {
            result.append("type ").append(object.name()).append(implementsTypes(object.interfaces())).append(" {\n");
            object.properties().forEach(property -> appendProperty(result, property));
            object.linkFields().forEach(field -> appendLinkField(result, field));
            object.computedFields().forEach(field -> result.append("  ").append(field.name()).append(": ").append(field.type()).append("\n"));
            result.append("}\n\n");
        }
        for (var type : schema.linkTypes()) {
            result.append("type ").append(type.name()).append(implementsTypes(type.interfaces())).append(" {\n");
            type.properties().forEach(property -> appendProperty(result, property));
            type.linkFields().forEach(field -> appendLinkField(result, field));
            type.computedFields().forEach(field -> result.append("  ").append(field.name()).append(": ").append(field.type()).append("\n"));
            result.append("}\n\n");
        }
        result.append(GraphqlQueryTypes.sdl(schema));
        result.append("type Query {\n");
        for (var object : schema.objectTypes()) {
            result.append("  search").append(object.name()).append("s(query: String!, fields: [String!], filter: ").append(object.name())
                    .append("Filter, mode: SearchMode, first: Int, offset: Int, after: String): SearchResult_").append(object.name()).append("!\n");
            String singular = lower(object.name());
            result.append("  ").append(singular).append("Aggregate(filter: ").append(object.name())
                    .append("Filter, groupBy: [String!], fields: [AggregateFieldInput!]!, orderBy: [AggregateOrderInput!], limit: Int, offset: Int): AggregateResult!\n");
            String arguments = "filter: " + object.name() + "Filter, orderBy: " + object.name() + "OrderBy, first: Int = 100, offset: Int = 0";
            result.append("  ").append(singular).append("(id: ID!): ").append(object.name()).append("\n");
            result.append("  ").append(singular).append("s(").append(arguments).append("): [").append(object.name()).append("!]!\n");
            result.append("  ").append(singular).append("sConnection(").append(arguments).append(", after: String): ").append(object.name()).append("Connection!\n");
        }
        result.append("}\n\n");
        if (!schema.actionTypes().isEmpty()) {
            if (mode == GraphqlApiRuntime.ActionMode.TYPED) {
                result.append(GraphqlActionTypes.sharedSdl());
                for (var action : schema.actionTypes()) {
                    if (!action.parameters().isEmpty()) {
                        result.append("input ").append(action.name()).append("Input {\n");
                        action.parameters().forEach(parameter -> result.append("  ").append(parameter.name()).append(": ")
                                .append(GraphqlActionTypes.inputTypeName(parameter.type(), schema)).append(parameter.required() ? "!" : "").append("\n"));
                        result.append("}\n");
                    }
                    result.append("type ").append(action.name()).append("Result { success: Boolean! actionId: ID! status: String! errors: [ActionError!] affectedObjects: [AffectedObject!] }\n");
                }
            }
            result.append("type Mutation {\n");
            for (var action : schema.actionTypes()) {
                result.append("  ").append(lower(action.name()));
                if (mode == GraphqlApiRuntime.ActionMode.LEGACY_JSON) result.append("(input: String!): String\n");
                else {
                    if (!action.parameters().isEmpty()) result.append("(input: ").append(action.name()).append("Input!)");
                    result.append(": ").append(action.name()).append("Result!\n");
                }
            }
            result.append("}\n");
        }
        return result.toString();
    }

    private static String implementsTypes(java.util.List<String> interfaces) {
        return interfaces.isEmpty() ? "" : " implements " + String.join(" & ", interfaces);
    }

    private static void appendProperty(StringBuilder result, org.openfoundry.foundation.spi.schema.PropertyDefinition property) {
        result.append("  ").append(property.name()).append(": ").append(property.type()).append(property.primary() ? "!" : "").append("\n");
    }

    private static void appendLinkField(StringBuilder result, org.openfoundry.foundation.spi.schema.LinkFieldDefinition field) {
        result.append("  ").append(field.name());
        if (field.many()) result.append("(first: Int = 100, offset: Int = 0)");
        result.append(": ").append(field.type()).append("\n");
    }

    private static String lower(String value) { return Character.toLowerCase(value.charAt(0)) + value.substring(1); }
}
