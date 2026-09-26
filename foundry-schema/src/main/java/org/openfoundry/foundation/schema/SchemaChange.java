package org.openfoundry.foundation.schema;

import java.util.Objects;

public record SchemaChange(String path, String detail, MigrationClass migrationClass) {
    public SchemaChange {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("path must not be blank");
        if (detail == null || detail.isBlank()) throw new IllegalArgumentException("detail must not be blank");
        Objects.requireNonNull(migrationClass, "migrationClass must not be null");
    }
}
