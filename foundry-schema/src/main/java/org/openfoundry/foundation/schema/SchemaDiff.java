package org.openfoundry.foundation.schema;

import java.util.List;
import java.util.Objects;

public record SchemaDiff(List<SchemaChange> changes) {
    public SchemaDiff {
        changes = List.copyOf(Objects.requireNonNull(changes, "changes must not be null"));
    }

    public MigrationClass classification() {
        return changes.stream().map(SchemaChange::migrationClass)
                .reduce(MigrationClass.SAFE, MigrationClass::max);
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }
}
