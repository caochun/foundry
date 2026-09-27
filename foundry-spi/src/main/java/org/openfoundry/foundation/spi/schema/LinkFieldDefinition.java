package org.openfoundry.foundation.spi.schema;

import org.openfoundry.foundation.spi.StorageProvider.Direction;

import java.util.Objects;

/** A read projection through a first-class link; never a stored object attribute. */
public record LinkFieldDefinition(String name, String type, boolean required, String linkType,
                                  Direction direction, boolean history, boolean sensitive) {
    public LinkFieldDefinition {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid relationship field name");
        }
        if (type == null || !type.matches("[A-Za-z_][A-Za-z0-9_]*|\\[[A-Za-z_][A-Za-z0-9_]*!?\\]")) {
            throw new IllegalArgumentException("Relationship field must return an entity or a list of entities");
        }
        if (linkType == null || linkType.isBlank()) {
            throw new IllegalArgumentException("Relationship field requires a link type");
        }
        Objects.requireNonNull(direction, "direction");
        if (history && !type.startsWith("[")) {
            throw new IllegalArgumentException("History relationship fields must be lists");
        }
    }

    public boolean many() {
        return type.startsWith("[");
    }

    public String targetType() {
        return type.replace("[", "").replace("]", "").replace("!", "");
    }
}
