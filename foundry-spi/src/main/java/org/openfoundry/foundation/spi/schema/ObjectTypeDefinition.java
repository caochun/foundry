package org.openfoundry.foundation.spi.schema;

import java.util.List;
import java.util.Objects;

public record ObjectTypeDefinition(String name, List<PropertyDefinition> properties, List<String> interfaces, List<String> constraints, List<LinkFieldDefinition> linkFields) {
    public ObjectTypeDefinition(String name, List<PropertyDefinition> properties, List<String> interfaces, List<String> constraints) {
        this(name, properties, interfaces, constraints, List.of());
    }

    public ObjectTypeDefinition(String name, List<PropertyDefinition> properties) {
        this(name, properties, List.of(), List.of());
    }

    public ObjectTypeDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        linkFields = List.copyOf(linkFields);
        interfaces = List.copyOf(interfaces);
        constraints = List.copyOf(constraints);
        properties = List.copyOf(Objects.requireNonNull(properties, "properties must not be null"));
    }
}
