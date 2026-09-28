package org.openfoundry.foundation.storage.jdbc;

import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.schema.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SchemaRegistryPersistenceTest {
    @TempDir Path directory;
    static final String ODL = """
            extend schema @namespace(name: "registry", version: "1.0.0")
            enum State { READY DONE }
            interface Identifiable { id: ID! @primary }
            type Node implements Identifiable @objectType {
                title: String! state: State @default(value: "READY")
                data: JSON @default(value: {enabled:true, numbers:[1,2], fraction:0.12345678901234567890123456789})
                createdAt: DateTime @readonly
                targets: [Node!] @link(type: "Edge", direction: OUTBOUND)
                count: Int @computed(fn: "countLinks", args: {type:"Edge"})
            }
            type Other @objectType { id: ID! @primary }
            type Edge @linkType(from:"Node", to:"Node", cardinality:MANY_TO_MANY) { id: ID! @primary note: String }
            type Move @actionType(permission: "can_move") { first: Node! @param second: Node! @param }
            """;
    static final OntologySchema BASE = new OdlParser().parse(ODL);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-28T00:00:00.123456789Z"), ZoneOffset.UTC);

    @TestFactory
    Stream<DynamicTest> registryContracts() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "complete snapshots and approval evidence survive reads", this::snapshots),
                test(provider, "breaking changes cannot bypass approval", this::breaking),
                test(provider, "compare-and-append rejects stale plans", this::optimistic),
                test(provider, "invalid schemas and startup drift are rejected", this::validation),
                test(provider, "concurrent startup deduplicates and concurrent plans conflict", this::concurrency)));
    }

    private DynamicTest test(String provider, String label, Check check) {
        return DynamicTest.dynamicTest(provider + " " + label, () -> {
            if (provider.equals("memory")) {
                var shared = new InMemorySchemaRegistry(new SchemaDiffer(), CLOCK);
                check.run(() -> shared);
            } else {
                var data = data("jdbc:h2:mem:registry_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                check.run(() -> new JdbcSchemaRegistry(data, DatabaseDialect.h2(), "main", CLOCK));
            }
        });
    }

    private void snapshots(Supplier<SchemaRegistry> factory) {
        var registry = factory.get();
        assertEquals(0, registry.currentVersion());
        assertThrows(IllegalStateException.class, registry::current);
        assertThrows(IllegalArgumentException.class, () -> registry.atVersion(1));
        var first = registry.apply(BASE, null);
        assertEquals(1, first.version());
        assertEquals(CLOCK.instant(), factory.get().history().getFirst().appliedAt());
        assertEquals(BASE, factory.get().atVersion(1));
        assertEquals(first, factory.get().history().getFirst());
        assertEquals(1, registry.applyIfChanged(reordered(BASE), null).version());
        assertEquals(1, registry.currentVersion());
        var second = registry.apply(BASE, null);
        assertEquals(2, second.version());
        assertTrue(second.diff().isEmpty());
        var next = new OdlParser().parse(ODL.replace("title: String!", "title: String! optional: String"));
        assertEquals(MigrationClass.SAFE, registry.apply(next, null).classification());
        assertEquals(3, factory.get().history().size());
        assertEquals(BASE, factory.get().atVersion(1));
        assertThrows(UnsupportedOperationException.class, () -> registry.current().objectTypes().clear());
    }

    private void breaking(Supplier<SchemaRegistry> factory) {
        var registry = factory.get();
        registry.apply(BASE, null);
        var next = new OdlParser().parse(ODL.replace("title: String!", "title: String! required: Int!"));
        assertThrows(SchemaValidationException.class, () -> registry.applyIfChanged(next, null));
        assertThrows(SchemaValidationException.class, () -> registry.apply(next, new MigrationPlan("pending", false)));
        assertEquals(1, factory.get().currentVersion());
        var plan = new MigrationPlan("Backfill required and validate outside the registry", true);
        var changed = registry.apply(next, plan, 1);
        assertEquals(MigrationClass.BREAKING, changed.classification());
        assertEquals(plan, factory.get().history().getLast().migrationPlan());
        assertEquals(next, factory.get().current());
        assertThrows(SchemaDriftException.class, () -> factory.get().requireCurrent(BASE));
        assertEquals(2, factory.get().requireCurrent(next).version());
        var renamed = new OntologySchema("other", next.version(), next.objectTypes(), next.linkTypes(), next.actionTypes(), next.enums(), next.interfaces());
        assertEquals(MigrationClass.BREAKING, new SchemaDiffer().diff(next, renamed).classification());
        assertThrows(SchemaValidationException.class, () -> registry.apply(renamed, null));
    }

    private void optimistic(Supplier<SchemaRegistry> factory) {
        var registry = factory.get();
        registry.apply(BASE, null, 0);
        assertThrows(SchemaVersionConflictException.class, () -> factory.get().apply(BASE, null, 0));
        assertThrows(SchemaVersionConflictException.class, () -> factory.get().apply(BASE, null, 0, true));
        var next = version(BASE, "1.1.0");
        var result = registry.apply(next, null, 1);
        assertEquals(2, result.version());
        assertEquals(MigrationClass.SAFE, result.classification());
        assertFalse(result.diff().isEmpty());
        assertThrows(SchemaVersionConflictException.class, () -> factory.get().apply(version(BASE, "1.2.0"), null, 1));
        assertEquals(next, factory.get().current());
    }

    private void validation(Supplier<SchemaRegistry> factory) {
        var registry = factory.get();
        var invalid = new OdlParser().parse(ODL.replace("first: Node!", "first: Missing!"));
        assertThrows(SchemaValidationException.class, () -> registry.apply(invalid, new MigrationPlan("approved cannot bypass validity", true)));
        assertEquals(0, registry.currentVersion());
        assertThrows(SchemaDriftException.class, () -> registry.requireCurrent(BASE));
        assertThrows(IllegalArgumentException.class, () -> registry.apply(BASE, null, -1));
        registry.apply(BASE, null);
        assertEquals(1, registry.requireCurrent(reordered(BASE)).version());
        var switched = new OdlParser().parse(ODL.replace("first: Node! @param second: Node! @param", "second: Node! @param first: Node! @param"));
        // Compiler fingerprints retain their old compatibility behavior, registry identity must not lose permission-target order.
        assertEquals(new SchemaCompiler().compile(BASE).schemaDigest(), new SchemaCompiler().compile(switched).schemaDigest());
        assertNotEquals(SchemaFingerprint.of(BASE), SchemaFingerprint.of(switched));
        assertThrows(SchemaDriftException.class, () -> registry.requireCurrent(switched));
        assertThrows(SchemaValidationException.class, () -> registry.applyIfChanged(switched, null));
        assertEquals(1, factory.get().currentVersion());
    }

    private void concurrency(Supplier<SchemaRegistry> factory) throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<Future<Integer>>();
            for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> {
                start.await();
                return factory.get().applyIfChanged(BASE, null).version();
            }));
            start.countDown();
            for (var result : futures) assertEquals(1, result.get(30, TimeUnit.SECONDS));
            assertEquals(1, factory.get().history().size());
            var update = new CountDownLatch(1);
            var changes = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 8; i++) {
                var candidate = version(BASE, "1.1." + i);
                changes.add(executor.submit(() -> {
                    update.await();
                    try { factory.get().apply(candidate, null, 1); return true; }
                    catch (SchemaVersionConflictException conflict) { return false; }
                }));
            }
            update.countDown();
            int accepted = 0;
            for (var change : changes) if (change.get(30, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
            assertEquals(2, factory.get().currentVersion());
        }
    }

    @Test
    void fileRegistryRestartsAndScopesRemainIndependent() {
        String url = "jdbc:h2:file:" + directory.resolve("versions") + ";WRITE_DELAY=0";
        var first = new JdbcSchemaRegistry(data(url), DatabaseDialect.h2(), "scope/α", CLOCK);
        first.apply(BASE, null);
        var plan = new MigrationPlan("reviewed parameter target change", true);
        var changed = new OdlParser().parse(ODL.replace("first: Node! @param second: Node! @param", "second: Node! @param first: Node! @param"));
        first.apply(changed, plan);
        var reopened = new JdbcSchemaRegistry(data(url), DatabaseDialect.h2(), "scope/α", CLOCK);
        assertEquals(changed, reopened.current());
        assertEquals(plan, reopened.history().getLast().migrationPlan());
        assertEquals(BASE, reopened.atVersion(1));
        var isolated = new JdbcSchemaRegistry(data(url), DatabaseDialect.h2(), "scope-b", CLOCK);
        assertEquals(0, isolated.currentVersion());
        isolated.apply(version(BASE, "2.0.0"), null);
        assertEquals(2, reopened.currentVersion());
        assertEquals(1, isolated.currentVersion());
    }

    @Test
    void singleConnectionAndNonAutocommitDataSourcesDoNotDeadlockOrLoseVersions() {
        var pool = JdbcConnectionPool.create("jdbc:h2:mem:registry_pool;DB_CLOSE_DELAY=-1", "sa", "");
        pool.setMaxConnections(1);
        var wrapped = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            try {
                Object result = method.invoke(pool, args);
                if (result instanceof java.sql.Connection connection) connection.setAutoCommit(false);
                return result;
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        try {
            var registry = new JdbcSchemaRegistry(wrapped, DatabaseDialect.h2());
            registry.apply(BASE, null);
            assertEquals(BASE, registry.current());
            assertEquals(1, registry.applyIfChanged(BASE, null).version());
            assertEquals(1, new JdbcSchemaRegistry(wrapped, DatabaseDialect.h2()).currentVersion());
        } finally { pool.dispose(); }
    }

    @Test
    void failedHeadWriteRollsBackSnapshotAppend() throws Exception {
        var data = data("jdbc:h2:mem:registry_fault;DB_CLOSE_DELAY=-1");
        var registry = new JdbcSchemaRegistry(data, DatabaseDialect.h2());
        registry.apply(BASE, null);
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_head BEFORE UPDATE ON of_schema_heads FOR EACH ROW CALL '" + FailHeadUpdate.class.getName() + "'");
        }
        assertThrows(IllegalStateException.class, () -> registry.apply(version(BASE, "1.1.0"), null));
        assertEquals(1, registry.currentVersion());
        assertEquals(BASE, registry.current());
        try (var connection = data.getConnection(); var statement = connection.createStatement()) { statement.execute("DROP TRIGGER reject_head"); }
        assertEquals(2, registry.apply(version(BASE, "1.1.0"), null).version());
    }

    @Test
    void corruptionCannotBeReadOrSilentlyOverwrittenByApproval() throws Exception {
        for (String mutation : List.of("UPDATE of_schema_versions SET snapshot_json = '{}'", "UPDATE of_schema_versions SET classification = 'BREAKING'",
                "UPDATE of_schema_heads SET current_version = 2", "DELETE FROM of_schema_versions")) {
            var data = data("jdbc:h2:mem:registry_corrupt_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            var registry = new JdbcSchemaRegistry(data, DatabaseDialect.h2());
            registry.apply(BASE, null);
            assertEquals(BASE, registry.current());
            try (var connection = data.getConnection(); var statement = connection.createStatement()) { statement.execute(mutation); }
            assertThrows(IllegalStateException.class, registry::current, mutation);
            assertThrows(IllegalStateException.class, () -> registry.apply(BASE, new MigrationPlan("must not repair corrupt history", true)), mutation);
        }
    }

    @Test
    void exactDecimalDefaultsSurviveRestartWithoutFingerprintDrift() {
        var exact = new java.math.BigDecimal("0.12345678901234567890123456789");
        var objects = BASE.objectTypes().stream().map(type -> {
            if (!type.name().equals("Node")) return type;
            var fields = type.properties().stream().map(field -> {
                if (!field.name().equals("data")) return field;
                return new org.openfoundry.foundation.spi.schema.PropertyDefinition(field.name(), field.type(), field.required(), field.primary(),
                        field.unique(), field.indexed(), field.sensitive(), field.immutable(), field.readOnly(), true, Map.of("fraction", exact), field.constraints());
            }).toList();
            return new org.openfoundry.foundation.spi.schema.ObjectTypeDefinition(type.name(), fields, type.interfaces(), type.constraints(), type.linkFields(), type.computedFields());
        }).toList();
        var schema = new OntologySchema(BASE.namespace(), BASE.version(), objects, BASE.linkTypes(), BASE.actionTypes(), BASE.enums(), BASE.interfaces());
        var data = data("jdbc:h2:mem:exact_registry_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        new JdbcSchemaRegistry(data, DatabaseDialect.h2()).apply(schema, null);
        var restarted = new JdbcSchemaRegistry(data, DatabaseDialect.h2());
        assertEquals(schema, restarted.current());
        assertEquals(SchemaFingerprint.of(schema), SchemaFingerprint.of(restarted.current()));
        assertEquals(1, restarted.applyIfChanged(schema, null).version());
    }

    private static OntologySchema reordered(OntologySchema schema) {
        return new OntologySchema(schema.namespace(), schema.version(), schema.objectTypes().reversed(), schema.linkTypes(), schema.actionTypes(), schema.enums(), schema.interfaces());
    }
    private static OntologySchema version(OntologySchema schema, String version) {
        return new OntologySchema(schema.namespace(), version, schema.objectTypes(), schema.linkTypes(), schema.actionTypes(), schema.enums(), schema.interfaces());
    }
    static JdbcDataSource data(String url) { var data = new JdbcDataSource(); data.setURL(url); data.setUser("sa"); return data; }
    @FunctionalInterface private interface Check { void run(Supplier<SchemaRegistry> factory) throws Exception; }
    public static final class FailHeadUpdate implements org.h2.api.Trigger {
        @Override public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow) throws SQLException { throw new SQLException("injected head write failure"); }
    }
}
