package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;

/** Generates a stable read/action contract; resolver execution stays in ApplicationService. */
public final class GraphqlContractGenerator {
    public String generate(OntologySchema schema) {
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
        result.append("type Query {\n");
        schema.objectTypes().forEach(object -> result.append("  ").append(lower(object.name())).append("(id: ID!): ")
                .append(object.name()).append("\n  ").append(lower(object.name())).append("s(first: Int, offset: Int): [")
                .append(object.name()).append("!]!\n"));
        result.append("}\n\n");
        if (!schema.actionTypes().isEmpty()) {
            result.append("type Mutation {\n");
            schema.actionTypes().forEach(action -> result.append("  ").append(lower(action.name())).append("(input: String!): String\n"));
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
