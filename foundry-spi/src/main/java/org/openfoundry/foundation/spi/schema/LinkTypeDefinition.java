package org.openfoundry.foundation.spi.schema;

import java.util.List;
import java.util.Objects;

public record LinkTypeDefinition(
        String name,
        String fromType,
        String toType,
        Cardinality cardinality,
        List<PropertyDefinition> properties, List<String> interfaces, List<String> constraints, List<LinkFieldDefinition> linkFields, List<ComputedFieldDefinition> computedFields) {

    public LinkTypeDefinition(String name, String fromType, String toType, Cardinality cardinality,
                              List<PropertyDefinition> properties, List<String> interfaces, List<String> constraints,
                              List<LinkFieldDefinition> linkFields) {
        this(name, fromType, toType, cardinality, properties, interfaces, constraints, linkFields, List.of());
    }

    public LinkTypeDefinition(String name, String fromType, String toType, Cardinality cardinality,
                              List<PropertyDefinition> properties, List<String> interfaces, List<String> constraints) {
        this(name, fromType, toType, cardinality, properties, interfaces, constraints, List.of());
    }

    public LinkTypeDefinition(String name, String fromType, String toType, Cardinality cardinality, List<PropertyDefinition> properties) {
        this(name, fromType, toType, cardinality, properties, List.of(), List.of());
    }

    public LinkTypeDefinition {
        requireText(name, "name");
        requireText(fromType, "fromType");
        requireText(toType, "toType");
        Objects.requireNonNull(cardinality, "cardinality must not be null");
        computedFields = List.copyOf(computedFields);
        linkFields = List.copyOf(linkFields);
        interfaces = List.copyOf(interfaces);
        constraints = List.copyOf(constraints);
        properties = List.copyOf(Objects.requireNonNull(properties, "properties must not be null"));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
