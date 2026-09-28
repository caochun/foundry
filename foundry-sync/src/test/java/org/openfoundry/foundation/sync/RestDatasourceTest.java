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

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.openfoundry.foundation.sync.RestSourceConnectorTest.*;

class RestDatasourceTest {
    static final RequestContext CTX = RequestContext.system("tenant", "importer");
    static final SyncAuthorizer ALLOW = (context, connector, mapping, target, tx) -> true;
    static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name:"rest",version:"1")
            type Person @objectType { id: ID! @primary name: String! }
            """);
    @TempDir Path directory;

    @TestFactory
    Stream<DynamicTest> providers() {
        return Stream.of("memory", "jdbc").flatMap(kind -> Stream.of(
                test(kind, "resume in a partially committed page", this::resume),
                test(kind, "target authorization stops before later cursor", this::denied),
                test(kind, "snapshot plus overlay", this::snapshot),
                test(kind, "explicit source delete and restore", this::lifecycle),
                test(kind, "mapping and source drift before I/O", this::drift)));
    }
    private DynamicTest test(String kind, String label, Scenario scenario) {
        return DynamicTest.dynamicTest(kind + " " + label, () -> {
            StorageProvider storage = kind.equals("memory") ? new InMemoryStorageProvider()
                    : new JdbcStorageProvider(data("jdbc:h2:mem:rest_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"), DatabaseDialect.h2());
            storage.applySchema(CTX, SCHEMA);
            scenario.run(storage);
        });
    }

    private void resume(StorageProvider storage) throws Exception {
        var repaired = new AtomicBoolean();
        try (var server = new Server(exchange -> {
            String after = query(exchange.getRequestURI().getRawQuery()).get("after");
            if ("r2".equals(after)) return "[]";
            String second = event(2, "p2", "INSERT");
            if (!repaired.get()) second = second.replace("\"name\":\"Person\"", "\"name\":null");
            return "r1".equals(after) ? "[" + second + "]" : "[" + event(1, "p1", "INSERT") + "," + second + "]";
        })) {
            var mapping = mapping(server, DatasourceMapping.Mode.POLLING, INCREMENTAL);
            var runner = new DatasourceRunner(storage, ConnectorRegistry.rest(), ALLOW);
            var first = runner.runOnce(mapping, CTX, OPTIONS);
            assertEquals(1, first.created());
            assertEquals(1, first.failures().size());
            assertEquals(1, runner.checkpoint(mapping, CTX).sequence());
            assertNull(storage.getObject(CTX, "Person", "p2"));
            repaired.set(true);
            var resumed = runner.runOnce(mapping, CTX, OPTIONS);
            assertTrue(resumed.failures().isEmpty(), resumed.failures().toString());
            assertEquals(1, resumed.created());
            assertEquals(2, runner.checkpoint(mapping, CTX).sequence());
            assertEquals(1, storage.getObject(CTX, "Person", "p1").version());
            assertEquals(0, runner.runOnce(mapping, CTX, OPTIONS).created());
        }
    }

    private void denied(StorageProvider storage) throws Exception {
        try (var server = new Server(exchange -> "[" + event(1, "p1", "INSERT") + "," + event(2, "p2", "INSERT") + "]")) {
            var mapping = mapping(server, DatasourceMapping.Mode.POLLING, INCREMENTAL);
            var runner = new DatasourceRunner(storage, ConnectorRegistry.rest(), (ctx, connector, definition, key, tx) -> key == null || !key.id().equals("p2"));
            assertEquals(1, runner.runOnce(mapping, CTX, OPTIONS).failures().size());
            assertEquals(1, runner.checkpoint(mapping, CTX).sequence());
            assertNull(storage.getObject(CTX, "Person", "p2"));
            int calls = server.calls.get();
            var denied = new DatasourceRunner(storage, ConnectorRegistry.rest(), SyncAuthorizer.denyAll());
            assertThrows(SecurityException.class, () -> denied.runOnce(mapping, CTX, OPTIONS));
            assertEquals(calls, server.calls.get());
        }
    }

    private void snapshot(StorageProvider storage) throws Exception {
        try (var server = new Server(exchange -> "{\"data\":[{\"id\":\"p1\",\"name\":\"Person\"}]}")) {
            var mapping = mapping(server, DatasourceMapping.Mode.BATCH, Map.of());
            var runner = new DatasourceRunner(storage, ConnectorRegistry.rest(), ALLOW);
            assertEquals(1, runner.runOnce(mapping, CTX, OPTIONS).created());
            assertNull(runner.checkpoint(mapping, CTX));
            var overlay = mapping(server, DatasourceMapping.Mode.OVERLAY, Map.of());
            try (var managed = new ManagedOverlay(overlay, ConnectorRegistry.rest())) {
                int calls = server.calls.get();
                var first = managed.get(Map.of("id", "p1"));
                assertEquals("Person", first.properties().get("name"));
                assertSame(first, managed.get(Map.of("id", "p1")));
                assertEquals(calls + 1, server.calls.get());
                assertEquals(1, storage.getObject(CTX, "Person", "p1").version());
            }
        }
    }

    private void lifecycle(StorageProvider storage) throws Exception {
        var phase = new java.util.concurrent.atomic.AtomicInteger(1);
        try (var server = new Server(exchange -> "[" + event(phase.get(), "p1", phase.get() == 2 ? "DELETE" : "INSERT") + "]")) {
            var mapping = mapping(server, DatasourceMapping.Mode.POLLING, INCREMENTAL);
            var runner = new DatasourceRunner(storage, ConnectorRegistry.rest(), ALLOW);
            assertEquals(1, runner.runOnce(mapping, CTX, OPTIONS).created());
            phase.set(2);
            assertEquals(1, runner.runOnce(mapping, CTX, OPTIONS).deleted());
            assertTrue(storage.getObject(CTX, "Person", "p1").isDeleted());
            phase.set(3);
            assertEquals(1, runner.runOnce(mapping, CTX, OPTIONS).restored());
            assertFalse(storage.getObject(CTX, "Person", "p1").isDeleted());
            assertEquals(3, runner.checkpoint(mapping, CTX).sequence());
        }
    }

    private void drift(StorageProvider storage) throws Exception {
        try (var server = new Server(exchange -> "[" + event(1, "p1", "INSERT") + "]")) {
            var definition = mapping(server, DatasourceMapping.Mode.POLLING, INCREMENTAL);
            var runner = new DatasourceRunner(storage, ConnectorRegistry.rest(), ALLOW);
            runner.runOnce(definition, CTX, OPTIONS);
            var changed = new DatasourceMapping(definition.datasource(), definition.connector(),
                    new DatasourceMapping.Connection(definition.connection().url() + "?source=changed", "people", INCREMENTAL), definition.mapping(), definition.sync());
            int calls = server.calls.get();
            assertThrows(IllegalStateException.class, () -> runner.runOnce(changed, CTX, OPTIONS));
            assertEquals(calls, server.calls.get());
        }
    }

    @Test
    void jdbcReconstructionResumesFromTheDurablePerRecordCursor() throws Exception {
        var target = data("jdbc:h2:file:" + directory.resolve("target") + ";WRITE_DELAY=0");
        var repaired = new AtomicBoolean();
        try (var server = new Server(exchange -> {
            String first = event(1, "p1", "INSERT");
            String second = event(2, "p2", "INSERT");
            if (!repaired.get()) second = second.replace("\"name\":\"Person\"", "\"name\":null");
            return query(exchange.getRequestURI().getRawQuery()).containsKey("after") ? "[" + second + "]" : "[" + first + "," + second + "]";
        })) {
            var definition = mapping(server, DatasourceMapping.Mode.POLLING, INCREMENTAL);
            try (var storage = new JdbcStorageProvider(target, DatabaseDialect.h2())) {
                storage.applySchema(CTX, SCHEMA);
                assertEquals(1, new DatasourceRunner(storage, ConnectorRegistry.rest(), ALLOW).runOnce(definition, CTX, OPTIONS).failures().size());
            }
            repaired.set(true);
            try (var recovered = new JdbcStorageProvider(target, DatabaseDialect.h2())) {
                recovered.applySchema(CTX, SCHEMA);
                var runner = new DatasourceRunner(recovered, ConnectorRegistry.rest(), ALLOW);
                assertEquals(1, runner.runOnce(definition, CTX, OPTIONS).created());
                assertEquals(2, runner.checkpoint(definition, CTX).sequence());
                assertEquals(1, recovered.getObject(CTX, "Person", "p1").version());
            }
        }
    }

    static DatasourceMapping mapping(Server server, DatasourceMapping.Mode mode, Map<String, Object> options) {
        return new DatasourceMapping("People", "rest", server.config(options),
                new MappingConfig("Person", new KeyMapping("id", "id", null), Map.of("name", new PropertyMapping("name")), List.of()),
                new DatasourceMapping.Sync(mode, null, ConflictResolver.Strategy.SOURCE_PRIORITY, null, null, null, false));
    }
    static JdbcDataSource data(String url) { var data = new JdbcDataSource(); data.setURL(url); return data; }
    interface Scenario { void run(StorageProvider storage) throws Exception; }
}
