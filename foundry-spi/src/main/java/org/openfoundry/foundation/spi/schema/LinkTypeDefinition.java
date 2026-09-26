package org.openfoundry.foundation.spi.schema;

import java.util.List;
import java.util.Objects;

public record LinkTypeDefinition(
        String name,
        String fromType,
        String toType,
        Cardinality cardinality,
        List<PropertyDefinition> properties) {

    public LinkTypeDefinition {
        requireText(name, "name");
        requireText(fromType, "fromType");
        requireText(toType, "toType");
        Objects.requireNonNull(cardinality, "cardinality must not be null");
        properties = List.copyOf(Objects.requireNonNull(properties, "properties must not be null"));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
