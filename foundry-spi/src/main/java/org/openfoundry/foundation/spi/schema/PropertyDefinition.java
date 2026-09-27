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
        boolean immutable,
        boolean readOnly,
        boolean hasDefault,
        Object defaultValue,
        java.util.List<String> constraints) {

    public PropertyDefinition(String name, String type, boolean required, boolean primary, boolean unique,
                              boolean indexed, boolean sensitive, boolean immutable) {
        this(name, type, required, primary, unique, indexed, sensitive, immutable, false, false, null, java.util.List.of());
    }

    public PropertyDefinition {
        requireText(name, "name");
        requireText(type, "type");
        constraints = java.util.List.copyOf(constraints);
        defaultValue = PropertyValues.immutableValue(defaultValue);
        if (!hasDefault && defaultValue != null) throw new IllegalArgumentException("Unexpected default value");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
