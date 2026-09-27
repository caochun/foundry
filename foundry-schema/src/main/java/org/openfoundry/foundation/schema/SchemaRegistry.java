package org.openfoundry.foundation.schema;

import org.openfoundry.foundation.spi.schema.OntologySchema;

import java.util.List;

public interface SchemaRegistry {
    OntologySchema current();

    OntologySchema atVersion(int version);

    List<SchemaVersion> history();

    default SchemaVersion apply(OntologySchema schema, MigrationPlan migrationPlan) {
        return apply(schema, migrationPlan, null, false);
    }

    /** Atomic compare-and-append; expectedVersion zero requires an empty registry. */
    default SchemaVersion apply(OntologySchema schema, MigrationPlan migrationPlan, int expectedVersion) {
        return apply(schema, migrationPlan, expectedVersion, false);
    }

    /** Atomic startup registration without minting a version for the same configuration. */
    default SchemaVersion applyIfChanged(OntologySchema schema, MigrationPlan migrationPlan) {
        return apply(schema, migrationPlan, null, true);
    }

    SchemaVersion apply(OntologySchema schema, MigrationPlan migrationPlan, Integer expectedVersion, boolean onlyIfChanged);

    default int currentVersion() {
        var versions = history();
        return versions.isEmpty() ? 0 : versions.getLast().version();
    }

    /** Read-time startup gate; this does not lock subsequent StorageProvider operations. */
    default SchemaVersion requireCurrent(OntologySchema configured) {
        new SchemaCompiler().compile(configured);
        var versions = history();
        if (versions.isEmpty() || !SchemaFingerprint.of(versions.getLast().schema()).equals(SchemaFingerprint.of(configured))) {
            throw new SchemaDriftException();
        }
        return versions.getLast();
    }
}
