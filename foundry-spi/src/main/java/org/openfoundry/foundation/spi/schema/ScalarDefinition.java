package org.openfoundry.foundation.spi.schema;

/** Named opaque JSON value; platform scalar names retain their built-in validation. */
public record ScalarDefinition(String name, String description) {
    public ScalarDefinition {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*") || name.startsWith("__")) {
            throw new IllegalArgumentException("Invalid scalar name: " + name);
        }
    }

    public ScalarDefinition(String name) {
        this(name, null);
    }
}
