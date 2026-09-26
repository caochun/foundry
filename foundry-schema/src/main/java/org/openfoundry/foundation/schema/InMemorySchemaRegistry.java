package org.openfoundry.foundation.schema;

import org.openfoundry.foundation.spi.schema.OntologySchema;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Immutable-snapshot registry used by tests and as the first runtime baseline. */
public final class InMemorySchemaRegistry implements SchemaRegistry {
    private final SchemaDiffer differ;
    private final List<SchemaVersion> versions = new ArrayList<>();

    public InMemorySchemaRegistry() {
        this(new SchemaDiffer());
    }

    public InMemorySchemaRegistry(SchemaDiffer differ) {
        this.differ = differ;
    }

    @Override
    public synchronized OntologySchema current() {
        if (versions.isEmpty()) throw new IllegalStateException("no schema has been applied");
        return versions.getLast().schema();
    }

    @Override
    public synchronized OntologySchema atVersion(int version) {
        return versions.stream().filter(snapshot -> snapshot.version() == version)
                .findFirst().orElseThrow(() -> new IllegalArgumentException("schema version not found: " + version)).schema();
    }

    @Override
    public synchronized List<SchemaVersion> history() {
        return List.copyOf(versions);
    }

    @Override
    public synchronized SchemaVersion apply(OntologySchema schema, MigrationPlan migrationPlan) {
        SchemaDiff diff = versions.isEmpty() ? new SchemaDiff(List.of()) : differ.diff(current(), schema);
        MigrationClass classification = diff.classification();
        if (classification == MigrationClass.BREAKING
                && (migrationPlan == null || !migrationPlan.approved())) {
            throw new SchemaValidationException(List.of("breaking schema change requires an approved migration plan"));
        }
        SchemaVersion result = new SchemaVersion(versions.size() + 1, schema, Instant.now(), diff, classification);
        versions.add(result);
        return result;
    }
}
