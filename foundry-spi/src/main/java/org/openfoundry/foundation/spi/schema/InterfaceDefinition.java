package org.openfoundry.foundation.spi.schema;

import java.util.List;

/** Resolved inherited fields plus declared interface ancestry. */
public record InterfaceDefinition(String name, List<PropertyDefinition> properties, List<String> interfaces, List<String> constraints, List<LinkFieldDefinition> linkFields) {
    public InterfaceDefinition(String name, List<PropertyDefinition> properties, List<String> interfaces, List<String> constraints) {
        this(name, properties, interfaces, constraints, List.of());
    }

    public InterfaceDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Interface name is required");
        linkFields = List.copyOf(linkFields);
        properties = List.copyOf(properties);
        interfaces = List.copyOf(interfaces);
        constraints = List.copyOf(constraints);
    }
}
