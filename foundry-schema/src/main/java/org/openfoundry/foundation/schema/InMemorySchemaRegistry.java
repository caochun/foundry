package org.openfoundry.foundation.schema;

import org.openfoundry.foundation.spi.schema.OntologySchema;

import java.util.ArrayList;
import java.util.List;

/** Immutable-snapshot registry used by tests and as the first runtime baseline. */
public final class InMemorySchemaRegistry implements SchemaRegistry {
    private final SchemaDiffer differ;
    private final java.time.Clock clock;
    private final List<SchemaVersion> versions = new ArrayList<>();

    public InMemorySchemaRegistry() {
        this(new SchemaDiffer());
    }

    public InMemorySchemaRegistry(SchemaDiffer differ) {
        this(differ, java.time.Clock.systemUTC());
    }

    public InMemorySchemaRegistry(SchemaDiffer differ, java.time.Clock clock) {
        this.differ = java.util.Objects.requireNonNull(differ);
        this.clock = java.util.Objects.requireNonNull(clock);
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
    public synchronized SchemaVersion apply(OntologySchema schema, MigrationPlan migrationPlan, Integer expectedVersion, boolean onlyIfChanged) {
        new SchemaCompiler().compile(schema);
        if (expectedVersion != null && expectedVersion < 0) throw new IllegalArgumentException("Expected schema version must not be negative");
        if (expectedVersion != null && expectedVersion != versions.size()) throw new SchemaVersionConflictException(expectedVersion, versions.size());
        if (onlyIfChanged && !versions.isEmpty() && SchemaFingerprint.of(current()).equals(SchemaFingerprint.of(schema))) return versions.getLast();
        SchemaDiff diff = versions.isEmpty() ? new SchemaDiff(List.of()) : differ.diff(current(), schema);
        MigrationClass classification = diff.classification();
        if (classification == MigrationClass.BREAKING
                && (migrationPlan == null || !migrationPlan.approved())) {
            throw new SchemaValidationException(List.of("breaking schema change requires an approved migration plan"));
        }
        SchemaVersion result = new SchemaVersion(versions.size() + 1, schema, clock.instant(), diff, classification, migrationPlan);
        versions.add(result);
        return result;
    }
}
