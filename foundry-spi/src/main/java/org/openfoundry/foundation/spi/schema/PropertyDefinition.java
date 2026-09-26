package org.openfoundry.foundation.spi.schema;

import java.util.Objects;

public record PropertyDefinition(
        String name,
        String type,
        boolean required,
        boolean primary,
        boolean unique,
        boolean indexed,
        boolean sensitive,
        boolean immutable) {

    public PropertyDefinition {
        requireText(name, "name");
        requireText(type, "type");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
