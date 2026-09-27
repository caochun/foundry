package org.openfoundry.foundation.schema;

import org.openfoundry.foundation.spi.schema.OntologySchema;

import java.time.Instant;

public record SchemaVersion(int version, OntologySchema schema, Instant appliedAt,
                            SchemaDiff diff, MigrationClass classification, MigrationPlan migrationPlan) {
    public SchemaVersion(int version, OntologySchema schema, Instant appliedAt, SchemaDiff diff, MigrationClass classification) {
        this(version, schema, appliedAt, diff, classification, null);
    }

    public SchemaVersion {
        if (version < 1) throw new IllegalArgumentException("version must be positive");
    }
}
