package org.openfoundry.foundation.spi.schema;

import java.util.List;
import java.util.Objects;

public record ObjectTypeDefinition(String name, List<PropertyDefinition> properties) {
    public ObjectTypeDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        properties = List.copyOf(Objects.requireNonNull(properties, "properties must not be null"));
    }
}
