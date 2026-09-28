package org.openfoundry.foundation.sync;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.openfoundry.foundation.sync.JdbcSourceConnectorTest.*;

class DatasourceRunnerTest {
    static final RequestContext CONTEXT = RequestContext.system("tenant", "importer");
    static final SyncAuthorizer ALLOW = (context, connector, mapping, target, tx) -> true;
    static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name:"polling",version:"1")
            type Person @objectType { id: ID! @primary name: String! }
            """);
    static final String YAML = """
            datasource: People
            connector: jdbc
            connection: {url: '${PEOPLE_DB}', table: people}
            mapping:
              objectType: Person
              primaryKey: {source: id, target: id}
              properties:
                name: {source: name}
            sync: {mode: POLLING, interval: PT1M, conflictResolution: ACTION_PRIORITY}
            """;
    @TempDir Path directory;

    @TestFactory
    Stream<DynamicTest> runtimeCases() {
        return Stream.of("memory", "jdbc").flatMap(kind -> Stream.of(
                scenario(kind, "poll/update/resume", this::polls),
                scenario(kind, "failed row and same-timestamp resume", this::resumesAfterFailure),
                scenario(kind, "source/mapping drift", this::rejectsDrift),
                scenario(kind, "pipeline preflight before source I/O", this::preflight),
                scenario(kind, "target denial stops checkpoint", this::deniedTarget),
                scenario(kind, "tenant-scoped source cursors", this::tenants),
                scenario(kind, "full extraction", this::batch),
                scenario(kind, "action priority declared by plan", this::actionPriority)));
    }

    private DynamicTest scenario(String kind, String name, CheckedScenario scenario) {
        return DynamicTest.dynamicTest(kind + " " + name, () -> {
            StorageProvider storage = kind.equals("memory") ? new InMemoryStorageProvider() : new JdbcStorageProvider(database("jdbc:h2:mem:target_" + java.util.UUID.randomUUID()), DatabaseDialect.h2());
            storage.applySchema(CONTEXT, SCHEMA);
            scenario.run(new Fixture(storage, source()));
        });
    }

    private void polls(Fixture fixture) throws Exception {
        var mapping = mapping();
        var first = fixture.runner(ALLOW).runOnce(mapping, CONTEXT, OPTIONS);
        assertTrue(first.failures().isEmpty());
        assertEquals(3, first.created());
        assertEquals(3, fixture.runner(ALLOW).checkpoint(mapping, CONTEXT).sequence());
        long version = fixture.storage.getObject(CONTEXT, "Person", "1").version();
        var empty = fixture.runner(ALLOW).runOnce(mapping, CONTEXT, OPTIONS);
        assertEquals(0, empty.created() + empty.updated() + empty.observed() + empty.replayed());
        execute(fixture.source, "UPDATE people SET name='Changed', updated_at=TIMESTAMP '2026-01-02 00:00:00' WHERE id=1");
        var changed = fixture.runner(ALLOW).runOnce(mapping, CONTEXT, OPTIONS);
        assertTrue(changed.failures().isEmpty(), changed.failures().toString());
        assertEquals(1, changed.updated());
        assertEquals(version + 1, fixture.storage.getObject(CONTEXT, "Person", "1").version());
        assertEquals("Changed", fixture.storage.getObject(CONTEXT, "Person", "1").properties().get("name"));
        assertEquals(4, fixture.runner(ALLOW).checkpoint(mapping, CONTEXT).sequence());
    }

    private void resumesAfterFailure(Fixture fixture) throws Exception {
        execute(fixture.source, "UPDATE people SET name=NULL WHERE id=2");
        var first = fixture.runner(ALLOW).runOnce(mapping(), CONTEXT, OPTIONS);
        assertEquals(1, first.created());
        assertEquals(1, first.failures().size());
        assertEquals(1, fixture.runner(ALLOW).checkpoint(mapping(), CONTEXT).sequence());
        assertNull(fixture.storage.getObject(CONTEXT, "Person", "10"));
        execute(fixture.source, "UPDATE people SET name='Repaired' WHERE id=2");
        var resumed = fixture.runner(ALLOW).runOnce(mapping(), CONTEXT, OPTIONS);
        assertTrue(resumed.failures().isEmpty(), resumed.failures().toString());
        assertEquals(2, resumed.created());
        assertEquals(3, fixture.runner(ALLOW).checkpoint(mapping(), CONTEXT).sequence());
        assertEquals(1, fixture.storage.getObject(CONTEXT, "Person", "1").version());
    }

    private void rejectsDrift(Fixture fixture) {
        fixture.runner(ALLOW).runOnce(mapping(), CONTEXT, OPTIONS);
        int count = fixture.created.get();
        for (String yaml : List.of(YAML.replace("table: people", "table: other"), YAML.replace("${PEOPLE_DB}", "different"),
                YAML.replace("ACTION_PRIORITY", "LAST_WRITE_WINS"), YAML.replace("source: name", "source: other"))) {
            assertThrows(IllegalStateException.class, () -> fixture.runner(ALLOW).runOnce(new MappingConfigParser().parse(yaml), CONTEXT, OPTIONS));
        }
        assertEquals(count, fixture.created.get(), "Configuration drift fails before creating a connector");
    }

    private void preflight(Fixture fixture) {
        assertThrows(SecurityException.class, () -> fixture.runner(SyncAuthorizer.denyAll()).runOnce(mapping(), CONTEXT));
        var invalid = new MappingConfigParser().parse(YAML.replace("name: {source: name}", "unknown: {source: name}"));
        assertThrows(IllegalArgumentException.class, () -> fixture.runner(ALLOW).runOnce(invalid, CONTEXT));
        var custom = new MappingConfigParser().parse(YAML.replace("source: name}", "source: name, transform: \"custom('absent')\"}"));
        assertThrows(IllegalArgumentException.class, () -> fixture.runner(ALLOW).runOnce(custom, CONTEXT));
        for (String mode : List.of("CDC", "OVERLAY")) {
            assertThrows(UnsupportedOperationException.class, () -> fixture.runner(ALLOW).runOnce(new MappingConfigParser().parse(YAML.replace("POLLING", mode)), CONTEXT));
        }
        assertThrows(UnsupportedOperationException.class, () -> fixture.runner(ALLOW).runOnce(new MappingConfigParser().parse(YAML.replace("mode: POLLING", "writeback: true, mode: POLLING")), CONTEXT));
        assertEquals(0, fixture.created.get());
    }

    private void deniedTarget(Fixture fixture) {
        SyncAuthorizer policy = (context, connector, mapping, target, tx) -> target == null || !target.id().equals("2");
        var denied = fixture.runner(policy).runOnce(mapping(), CONTEXT, OPTIONS);
        assertEquals(1, denied.created());
        assertEquals(1, denied.failures().size());
        assertEquals(1, fixture.runner(ALLOW).checkpoint(mapping(), CONTEXT).sequence());
        assertEquals(2, fixture.runner(ALLOW).runOnce(mapping(), CONTEXT, OPTIONS).created());
    }

    private void tenants(Fixture fixture) {
        fixture.runner(ALLOW).runOnce(mapping(), CONTEXT, OPTIONS);
        var other = RequestContext.system("other", "importer");
        assertNull(fixture.runner(ALLOW).checkpoint(mapping(), other));
        assertEquals(3, fixture.runner(ALLOW).runOnce(mapping(), other, OPTIONS).created());
        assertEquals(3, fixture.runner(ALLOW).checkpoint(mapping(), other).sequence());
    }

    private void batch(Fixture fixture) {
        var mapping = new MappingConfigParser().parse(YAML.replace("POLLING", "BATCH"));
        var result = fixture.runner(ALLOW).runOnce(mapping, CONTEXT, OPTIONS);
        assertEquals(3, result.created());
        assertNull(fixture.runner(ALLOW).checkpoint(mapping, CONTEXT));
    }

    private void actionPriority(Fixture fixture) throws Exception {
        fixture.runner(ALLOW).runOnce(mapping(), CONTEXT, OPTIONS);
        try (var tx = fixture.storage.beginTransaction(CONTEXT)) {
            tx.mutationSource(new org.openfoundry.foundation.spi.MutationSource(org.openfoundry.foundation.spi.MutationSource.Kind.ACTION,
                    "manual", "manual-1", java.time.Instant.parse("2026-01-02T00:00:00Z"), Map.of()));
            var person = tx.getObject("Person", "1");
            tx.updateObject("Person", "1", Map.of("name", "Manual"), person.version());
            tx.commit();
        }
        execute(fixture.source, "UPDATE people SET name='Source', updated_at=TIMESTAMP '2026-01-03 00:00:00' WHERE id=1");
        var result = fixture.runner(ALLOW).runOnce(mapping(), CONTEXT, OPTIONS);
        assertTrue(result.failures().isEmpty());
        assertTrue(result.conflicts() > 0);
        assertEquals("Manual", fixture.storage.getObject(CONTEXT, "Person", "1").properties().get("name"));
        assertEquals(4, fixture.runner(ALLOW).checkpoint(mapping(), CONTEXT).sequence());
    }

    @Test
    void durableCheckpointSurvivesProviderReconstruction() throws Exception {
        var target = database("jdbc:h2:file:" + directory.resolve("target") + ";WRITE_DELAY=0");
        var source = source();
        execute(source, "UPDATE people SET name=NULL WHERE id=2");
        try (var storage = new JdbcStorageProvider(target, DatabaseDialect.h2())) {
            storage.applySchema(CONTEXT, SCHEMA);
            assertEquals(1, new Fixture(storage, source).runner(ALLOW).runOnce(mapping(), CONTEXT, OPTIONS).created());
        }
        execute(source, "UPDATE people SET name='Fixed' WHERE id=2");
        try (var recovered = new JdbcStorageProvider(target, DatabaseDialect.h2())) {
            recovered.applySchema(CONTEXT, SCHEMA);
            var runner = new Fixture(recovered, source).runner(ALLOW);
            assertEquals(1, runner.checkpoint(mapping(), CONTEXT).sequence());
            assertEquals(2, runner.runOnce(mapping(), CONTEXT, OPTIONS).created());
            assertEquals(3, runner.checkpoint(mapping(), CONTEXT).sequence());
        }
    }

    @Test
    void resolvedEndpointChangesCannotReuseAnUnchangedPlaceholderDeclaration() throws Exception {
        var storage = new InMemoryStorageProvider();
        storage.applySchema(CONTEXT, SCHEMA);
        var original = source();
        var changed = source();
        var originalRunner = new DatasourceRunner(storage, ConnectorRegistry.jdbc(configuration -> original), ALLOW);
        assertEquals(3, originalRunner.runOnce(mapping(), CONTEXT, OPTIONS).created());
        var changedRunner = new DatasourceRunner(storage, ConnectorRegistry.jdbc(configuration -> changed), ALLOW);
        assertThrows(IllegalArgumentException.class, () -> changedRunner.runOnce(mapping(), CONTEXT, OPTIONS));
        assertEquals(3, originalRunner.checkpoint(mapping(), CONTEXT).sequence());
        assertEquals(1, storage.getObject(CONTEXT, "Person", "1").version());
    }

    @Test
    void sourceSchemaAndPluginVersionChangesRequireExplicitCheckpointMigration() throws Exception {
        var storage = new InMemoryStorageProvider();
        storage.applySchema(CONTEXT, SCHEMA);
        var source = source();
        var registry = ConnectorRegistry.jdbc(configuration -> source);
        var runner = new DatasourceRunner(storage, registry, ALLOW);
        runner.runOnce(mapping(), CONTEXT, OPTIONS);
        execute(source, "ALTER TABLE people ADD detail VARCHAR");
        assertThrows(IllegalArgumentException.class, () -> runner.runOnce(mapping(), CONTEXT, OPTIONS));
        var plugin = registry.get("jdbc");
        registry.unregister("jdbc");
        registry.register(new ConnectorRegistry.Plugin("jdbc", "changed-version", plugin.description(), definition -> {
            fail("Version drift must be detected before creating a source resource");
            return null;
        }));
        assertThrows(IllegalStateException.class, () -> runner.runOnce(mapping(), CONTEXT, OPTIONS));
    }

    @Test
    void oneSourceConnectionCanAlsoServeTargetTransactions() throws Exception {
        var database = source();
        var open = new AtomicInteger();
        var data = tracked(database, open, new AtomicInteger());
        try (var storage = new JdbcStorageProvider(data, DatabaseDialect.h2())) {
            storage.applySchema(CONTEXT, SCHEMA);
            var runner = new DatasourceRunner(storage, ConnectorRegistry.jdbc(configuration -> data), ALLOW);
            var result = runner.runOnce(mapping(), CONTEXT, OPTIONS);
            assertTrue(result.failures().isEmpty(), result.failures().toString());
            assertEquals(3, result.created());
            assertEquals(0, open.get());
        }
    }

    @Test
    void registryRejectsDuplicatesAndCreatesFreshOwnedConnectorInstances() throws Exception {
        var source = source();
        var registry = ConnectorRegistry.jdbc(configuration -> source);
        assertEquals(List.of("jdbc"), registry.list());
        assertThrows(IllegalArgumentException.class, () -> registry.register(registry.get("jdbc")));
        try (var first = registry.create(mapping()); var second = registry.create(mapping())) {
            assertNotSame(first, second);
            assertEquals("People", first.name());
            assertFalse(first.healthCheck().healthy());
        }
        assertTrue(registry.unregister("jdbc"));
        assertFalse(registry.unregister("jdbc"));
        assertThrows(IllegalArgumentException.class, () -> registry.create(mapping()));
    }

    private record Fixture(StorageProvider storage, DataSource source, AtomicInteger created) {
        Fixture(StorageProvider storage, DataSource source) {
            this(storage, source, new AtomicInteger());
        }

        DatasourceRunner runner(SyncAuthorizer authorizer) {
            return new DatasourceRunner(storage, ConnectorRegistry.jdbc(configuration -> {
                created.incrementAndGet();
                return source;
            }), authorizer);
        }
    }

    static DatasourceMapping mapping() {
        return new MappingConfigParser().parse(YAML);
    }

    static JdbcDataSource database(String url) {
        var database = new JdbcDataSource();
        database.setURL(url + ";DB_CLOSE_DELAY=-1");
        return database;
    }

    private interface CheckedScenario {
        void run(Fixture fixture) throws Exception;
    }
}
