package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.schema.LinkTypeDefinition;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;

/** Generates a stable read/action contract; resolver execution stays in ApplicationService. */
public final class GraphqlContractGenerator {
    public String generate(OntologySchema schema) {
        StringBuilder result = new StringBuilder("scalar JSON\n\n");
        for (ObjectTypeDefinition object : schema.objectTypes()) {
            result.append("type ").append(object.name()).append(" {\n");
            object.properties().forEach(property -> result.append("  ").append(property.name()).append(": ")
                    .append(graphqlType(property.type())).append(property.required() ? "!" : "").append("\n"));
            result.append("}\n\n");
        }
        result.append("type Query {\n");
        schema.objectTypes().forEach(object -> result.append("  ").append(lower(object.name())).append("(id: ID!): ")
                .append(object.name()).append("\n  ").append(lower(object.name())).append("s(first: Int, offset: Int): [")
                .append(object.name()).append("!]!\n"));
        result.append("}\n\n");
        result.append("type Mutation {\n");
        schema.actionTypes().forEach(action -> result.append("  ").append(lower(action.name())).append("(input: JSON!): JSON!\n"));
        result.append("}\n");
        return result.toString();
    }

    private static String graphqlType(String type) {
        return switch (type) {
            case "ID", "String", "Int", "Float", "Boolean", "Date", "DateTime", "JSON" -> type;
            default -> type;
        };
    }

    private static String lower(String value) { return Character.toLowerCase(value.charAt(0)) + value.substring(1); }
}
