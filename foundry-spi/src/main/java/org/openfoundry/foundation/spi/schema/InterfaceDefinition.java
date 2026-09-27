package org.openfoundry.foundation.spi.schema;

import java.util.List;

/** Resolved inherited fields plus declared interface ancestry. */
public record InterfaceDefinition(String name, List<PropertyDefinition> properties, List<String> interfaces, List<String> constraints) {
    public InterfaceDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Interface name is required");
        properties = List.copyOf(properties);
        interfaces = List.copyOf(interfaces);
        constraints = List.copyOf(constraints);
    }
}
