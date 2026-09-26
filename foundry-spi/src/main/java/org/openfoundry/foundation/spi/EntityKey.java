package org.openfoundry.foundation.spi;

import java.util.Objects;

/** Stable reference to an object in the ontology. */
public record EntityKey(String type, String id) {
    public EntityKey {
        requireText(type, "type");
        requireText(id, "id");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
