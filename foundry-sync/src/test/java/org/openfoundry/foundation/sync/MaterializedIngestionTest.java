package org.openfoundry.foundation.sync;

import org.h2.jdbcx.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MaterializedIngestionTest {
    @TempDir Path directory;
    static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    static final RequestContext CTX = RequestContext.system("tenant", "worker");
    static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name:"ingestion",version:"1")
            type Person @objectType { id: ID! @primary name: String! status: String note: String born: Date payload: JSON code: String @unique updatedAt: DateTime @readonly }
            type Edit @actionType(permission:"can_edit") { person: Person! @param name: String! @param }
            type ClearNote @actionType(permission:"can_edit") { person: Person! @param }
            """);
    static final MappingConfig MAPPING = new MappingConfig("Person", "id", Map.of("id", "id", "name", "name", "status", "status", "note", "note", "born", "born", "payload", "payload", "code", "code"));
    static final EntityKey PERSON = new EntityKey("Person", "a");

    @TestFactory
    Stream<DynamicTest> ingestionAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "source facts lineage and opaque checkpoints commit together", this::commit),
                test(provider, "default deny runs before connector I/O", this::defaultDeny),
                test(provider, "duplicate event survives another actor and later manual edits", this::replay),
                test(provider, "event identity cannot be reused with different payload", this::changedReplay),
                test(provider, "failed record stops before later checkpoints and closes stream", this::failureStop),
                test(provider, "unrecognized older positions fail without replaying writes", this::ordering),
                test(provider, "per-field source priorities and tied timestamps follow policy", this::priorities),
                test(provider, "Action priority protects null and equal-value corroboration", this::actionPriority),
                test(provider, "no-op observations update provenance without fact versions", this::observations),
                test(provider, "delete restore and absent identity tombstones preserve order", this::lifecycle),
                test(provider, "tenant checkpoints and source event receipts are isolated", this::tenants),
                test(provider, "partition configuration changes require an explicit new pipeline", this::configuration),
                test(provider, "revocation before commit rolls back facts and metadata", this::revocation),
                test(provider, "missing fields do not clear nullable properties", this::missingFields),
                test(provider, "schema activation fences an in-progress mapping", this::schemaChange),
                test(provider, "field updates cannot steal a protected identity assertion", this::identityAuthority),
                test(provider, "restoration preserves uniqueness in the same transaction", this::restoreUniqueness)));
    }

    private DynamicTest test(String provider, String name, Consumer<Fixture> verify) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> verify.accept(fixture(provider.equals("jdbc") ? data("jdbc:h2:mem:ingest_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1") : null)));
    }

    private void commit(Fixture f) {
        var result = f.run("hr", List.of(record("hr", "a", 1, Map.of("name", "From HR"))));
        assertTrue(result.failures().isEmpty(), result.failures().toString());
        assertEquals(1, result.created());
        assertEquals("From HR", f.person("a").properties().get("name"));
        var checkpoint = f.checkpoint("hr");
        assertEquals(1, checkpoint.sequence());
        assertEquals(Map.of("offset", "cursor-1"), checkpoint.token());
        var line = f.storage.getLineage(CTX, PERSON, new LineageQuery("name", 10, null)).getFirst();
        assertEquals(MutationSource.Kind.SYNC, line.source().kind());
        assertEquals("hr", line.source().name());
        assertEquals("a", line.source().details().get("sourcePointer"));
        assertEquals("hr", f.storage.getEntityHistory(CTX, PERSON).getFirst().sourceSystem());
    }

    private void defaultDeny(Fixture f) {
        var called = new AtomicBoolean();
        Connector connector = new Connector() {
            public String name() { return "hr"; }
            public Stream<SourceRecord> read(SourceQuery query) { called.set(true); return Stream.of(record("hr", "a", 1, Map.of("name", "No"))); }
        };
        assertThrows(SecurityException.class, () -> new MaterializedSyncService(f.storage).sync(connector, new SourceQuery("people", Map.of()), MAPPING, CTX));
        assertFalse(called.get());
        assertNull(f.person("a"));
    }

    private void replay(Fixture f) {
        var source = record("hr", "a", 1, Map.of("name", "Initial"));
        assertEquals(1, f.run("hr", List.of(source)).created());
        f.edit("Manual");
        var before = f.storage.getLineage(CTX, PERSON, LineageQuery.defaults());
        var otherActor = RequestContext.system("tenant", "second-worker");
        var replay = f.service.sync(connector("hr", List.of(source)), new SourceQuery("people", Map.of()), MAPPING, otherActor);
        assertEquals(1, replay.replayed());
        assertEquals(0, replay.updated());
        assertEquals("Manual", f.person("a").properties().get("name"));
        assertEquals(before, f.storage.getLineage(CTX, PERSON, LineageQuery.defaults()));
        assertEquals(1, f.checkpoint("hr").version());
    }

    private void changedReplay(Fixture f) {
        f.run("hr", List.of(record("hr", "a", 1, Map.of("name", "Initial"))));
        var changed = f.run("hr", List.of(record("hr", "a", 1, Map.of("name", "Forged"))));
        assertEquals(1, changed.failures().size());
        assertEquals("Initial", f.person("a").properties().get("name"));
        assertEquals(1, f.checkpoint("hr").sequence());
        var original = record("hr", "a", 1, Map.of("name", "Initial"));
        var changedMetadata = new SourceRecord(original.sourceSystem(), original.sourceRecordId(), original.operation(), original.observedAt(), original.data(),
                new Provenance("hr", "a", "different-version", "identity", original.provenance().producedAt(), "source", null), original.position());
        assertEquals(1, f.run("hr", List.of(changedMetadata)).failures().size());
    }

    private void failureStop(Fixture f) {
        var source = List.of(record("hr", "a", 1, Map.of("name", "First")), record("hr", "b", 2, Map.of()), record("hr", "c", 3, Map.of("name", "Third")));
        var visited = new AtomicInteger();
        var closed = new AtomicBoolean();
        Connector connector = new Connector() {
            public String name() { return "hr"; }
            public Stream<SourceRecord> read(SourceQuery query) { return source.stream().peek(row -> visited.incrementAndGet()).onClose(() -> closed.set(true)); }
        };
        var result = f.service.sync(connector, new SourceQuery("people", Map.of()), MAPPING, CTX);
        assertEquals(1, result.created());
        assertEquals(1, result.failures().size());
        assertEquals(2, visited.get());
        assertTrue(closed.get());
        assertNull(f.person("b"));
        assertNull(f.person("c"));
        assertEquals(1, f.checkpoint("hr").sequence());
        var resumed = f.run("hr", List.of(source.getFirst(), record("hr", "b", 2, Map.of("name", "Fixed")), source.getLast()));
        assertTrue(resumed.failures().isEmpty(), resumed.failures().toString());
        assertEquals(1, resumed.replayed());
        assertEquals(2, resumed.created());
        assertEquals(3, f.checkpoint("hr").sequence());
    }

    private void ordering(Fixture f) {
        f.run("hr", List.of(record("hr", "a", 5, Map.of("name", "Latest"))));
        var late = f.run("hr", List.of(record("hr", "a", 4, Map.of("name", "Old"))));
        assertEquals(1, late.failures().size());
        assertEquals("Latest", f.person("a").properties().get("name"));
        assertEquals(5, f.checkpoint("hr").sequence());
    }

    private void priorities(Fixture f) {
        f.service = new MaterializedSyncService(f.storage, new ConflictResolver(ConflictResolver.Strategy.SOURCE_PRIORITY, Map.of(),
                Map.of("hr", 0, "legacy", 1), Map.of("status", Map.of("legacy", 0, "hr", 1))))
                .withAuthorization((ctx, connector, mapping, target, tx) -> true).withClock(f.clock);
        f.run("legacy", List.of(record("legacy", "a", 1, Map.of("name", "Legacy", "status", "REVIEWED"))));
        var merged = f.run("hr", List.of(record("hr", "a", 1, Map.of("name", "HR", "status", "PENDING"))));
        assertTrue(merged.failures().isEmpty(), merged.failures().toString());
        assertEquals("HR", f.person("a").properties().get("name"));
        assertEquals("REVIEWED", f.person("a").properties().get("status"));
        try (var tx = f.storage.beginTransaction(CTX)) {
            assertEquals("hr", tx.latestLineage(PERSON).get("name").source().name());
            assertEquals("legacy", tx.latestLineage(PERSON).get("status").source().name());
        }
        assertEquals(2, merged.conflicts());
        var resolver = new ConflictResolver(ConflictResolver.Strategy.SOURCE_PRIORITY, Map.of(), Map.of());
        var old = resolver.resolve(Map.of("name", new ConflictResolver.IncomingValue("Old", "unknown-a", START, false)),
                Map.of("name", new ConflictResolver.ExistingValue("New", "unknown-b", START.plusSeconds(1), false)));
        assertTrue(old.accepted().isEmpty(), "Same source rank must fall back to event time");
    }

    private void actionPriority(Fixture f) {
        f.service = new MaterializedSyncService(f.storage, new ConflictResolver(ConflictResolver.Strategy.ACTION_PRIORITY, Map.of(), Map.of()))
                .withAuthorization((ctx, connector, mapping, target, tx) -> true).withClock(f.clock);
        f.run("hr", List.of(record("hr", "a", 1, Map.of("name", "Initial", "note", "Imported"))));
        f.edit("Manual");
        var same = f.run("hr", List.of(record("hr", "a", 2, Map.of("name", "Manual"))));
        assertTrue(same.failures().isEmpty(), same.failures().toString());
        try (var tx = f.storage.beginTransaction(CTX)) { assertEquals(MutationSource.Kind.ACTION, tx.latestLineage(PERSON).get("name").source().kind()); }
        f.clearNote();
        var input = new LinkedHashMap<String, Object>();
        input.put("name", "External override");
        input.put("note", "Must not replace explicit null");
        var rejected = f.run("hr", List.of(record("hr", "a", 3, input)));
        assertTrue(rejected.failures().isEmpty(), rejected.failures().toString());
        assertEquals("Manual", f.person("a").properties().get("name"));
        assertTrue(f.person("a").properties().containsKey("note"));
        assertNull(f.person("a").properties().get("note"));
        assertEquals(2, rejected.conflicts());
    }

    private void observations(Fixture f) {
        f.run("hr", List.of(record("hr", "a", 1, Map.of("name", "Same"))));
        var before = f.storage.getLineage(CTX, PERSON, new LineageQuery("name", 10, null)).getFirst();
        var observed = f.run("hr", List.of(record("hr", "a", 2, Map.of("name", "Same"))));
        assertEquals(1, observed.observed());
        assertEquals(1, f.person("a").version());
        var after = f.storage.getLineage(CTX, PERSON, new LineageQuery("name", 10, null)).getFirst();
        assertTrue(after.sequence() > before.sequence());
        assertEquals(before.valueHash(), after.valueHash());
        assertEquals(START.plusSeconds(2), after.source().producedAt());
    }

    private void lifecycle(Fixture f) {
        f.run("hr", List.of(record("hr", "a", 1, Map.of("name", "Initial"))));
        var deleted = change(record("hr", "a", 2, Map.of()), "DELETE");
        assertEquals(1, f.run("hr", List.of(deleted)).deleted());
        assertTrue(f.person("a").isDeleted());
        assertEquals(1, f.run("hr", List.of(record("hr", "a", 3, Map.of("name", "Returned")))).restored());
        assertFalse(f.person("a").isDeleted());
        assertEquals(3, f.person("a").version());
        assertEquals(List.of(EntityOperation.CREATED, EntityOperation.DELETED, EntityOperation.RESTORED), f.storage.getEntityHistory(CTX, PERSON).stream().map(HistorySnapshot::operation).toList());
        var absent = change(record("hr", "missing", 4, Map.of()), "DELETE");
        assertEquals(1, f.run("hr", List.of(absent)).observed());
        assertNull(f.person("missing"));
        try (var tx = f.storage.beginTransaction(CTX)) {
            var tombstone = tx.latestLineage(new EntityKey("Person", "missing")).get("_entity");
            assertFalse(tombstone.valuePresent());
            assertEquals(0, tombstone.entityVersion());
        }
        // Different partition avoids cursor rejection; the absence assertion must itself reject the older value.
        var late = record("hr", "missing", 2, Map.of("name", "Late"));
        late = new SourceRecord(late.sourceSystem(), late.sourceRecordId(), late.operation(), late.observedAt(), late.data(), late.provenance(), new SourcePosition("other", "late", 1, "late-cursor"));
        var ignored = f.run("hr", List.of(late));
        assertTrue(ignored.failures().isEmpty(), ignored.failures().toString());
        assertNull(f.person("missing"));
        assertEquals(1, ignored.ignored());
    }

    private void tenants(Fixture f) {
        var source = record("hr", "a", 1, Map.of("name", "Same ID"));
        f.run("hr", List.of(source));
        var other = RequestContext.system("other-tenant", "worker");
        assertNull(f.service.checkpoint("hr", MAPPING, "p0", other));
        var result = f.service.sync(connector("hr", List.of(source)), new SourceQuery("people", Map.of()), MAPPING, other);
        assertEquals(1, result.created());
        assertEquals(1, f.storage.getObject(other, "Person", "a").version());
        assertEquals(1, f.checkpoint("hr").version());
    }

    private void configuration(Fixture f) {
        f.run("hr", List.of(record("hr", "a", 1, Map.of("name", "Initial"))));
        var changed = f.service.withUnknownOrigins(MaterializedSyncService.UnknownOrigins.TRUST_UPDATED_AT);
        assertThrows(IllegalStateException.class, () -> changed.checkpoint("hr", MAPPING, "p0", CTX));
        var result = changed.sync(connector("hr", List.of(record("hr", "a", 2, Map.of("name", "Changed")))), new SourceQuery("people", Map.of()), MAPPING, CTX);
        assertEquals(1, result.failures().size());
        assertEquals("Initial", f.person("a").properties().get("name"));
        assertEquals(1, f.checkpoint("hr").sequence());
    }

    private void revocation(Fixture f) {
        var checks = new AtomicInteger();
        f.service = f.service.withAuthorization((ctx, connector, mapping, target, tx) -> target == null || checks.incrementAndGet() == 1);
        var result = f.run("hr", List.of(record("hr", "a", 1, Map.of("name", "No commit"))));
        assertEquals(1, result.failures().size());
        assertNull(f.person("a"));
        assertTrue(f.storage.getLineage(CTX, PERSON, LineageQuery.defaults()).isEmpty());
        assertNull(f.checkpoint("hr"));
    }

    private void missingFields(Fixture f) {
        f.run("hr", List.of(record("hr", "a", 1, Map.of("name", "Initial", "note", "Keep"))));
        f.run("hr", List.of(record("hr", "a", 2, Map.of("name", "Next"))));
        assertEquals("Keep", f.person("a").properties().get("note"));
        var cleared = new LinkedHashMap<String, Object>();
        cleared.put("note", null);
        assertTrue(f.run("hr", List.of(record("hr", "a", 3, cleared))).failures().isEmpty());
        assertNull(f.person("a").properties().get("note"));
        assertTrue(f.person("a").properties().containsKey("note"));
    }

    private void restoreUniqueness(Fixture f) {
        f.run("hr", List.of(record("hr", "a", 1, Map.of("name", "Initial", "code", "taken"))));
        f.run("hr", List.of(change(record("hr", "a", 2, Map.of()), "DELETE")));
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.createObject("Person", "b", Map.of("name", "Other", "code", "taken"));
            tx.commit();
        }
        var blocked = f.run("hr", List.of(record("hr", "a", 3, Map.of("name", "Returned"))));
        assertEquals(1, blocked.failures().size());
        assertTrue(f.person("a").isDeleted());
        assertEquals(2, f.checkpoint("hr").sequence());
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.restoreObject("Person", "a", Map.of("code", "free"), 2);
            assertThrows(org.openfoundry.foundation.spi.schema.PropertyValidationException.class,
                    () -> tx.createObject("Person", "c", Map.of("name", "Third", "code", "taken")));
            tx.commit();
        }
        assertFalse(f.person("a").isDeleted());
        assertEquals("free", f.person("a").properties().get("code"));
        assertEquals("taken", f.person("b").properties().get("code"));
        assertNull(f.person("c"));
    }

    private void identityAuthority(Fixture f) {
        f.service = new MaterializedSyncService(f.storage, new ConflictResolver(ConflictResolver.Strategy.ACTION_PRIORITY,
                Map.of("name", ConflictResolver.Strategy.SOURCE_PRIORITY), Map.of("hr", 0)))
                .withAuthorization((ctx, connector, mapping, target, tx) -> true).withClock(f.clock);
        f.run("hr", List.of(record("hr", "a", 1, Map.of("name", "Initial"))));
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.mutationSource(MutationSource.action("HoldIdentity", "hold-a", f.clock.instant(), false));
            tx.recordProvenance(PERSON, 1, Set.of("_entity"));
            tx.commit();
        }
        var updated = f.run("hr", List.of(record("hr", "a", 2, Map.of("name", "Updated"))));
        assertEquals(1, updated.updated());
        assertEquals("Updated", f.person("a").properties().get("name"));
        try (var tx = f.storage.beginTransaction(CTX)) { assertEquals("hold-a", tx.latestLineage(PERSON).get("_entity").source().operationId()); }
        var deleted = f.run("hr", List.of(change(record("hr", "a", 3, Map.of()), "DELETE")));
        assertEquals(1, deleted.ignored());
        assertFalse(f.person("a").isDeleted());
    }

    private void schemaChange(Fixture f) {
        var records = List.of(record("hr", "a", 1, Map.of("name", "First")), record("hr", "b", 2, Map.of("name", "Second")));
        Connector connector = new Connector() {
            public String name() { return "hr"; }
            public Stream<SourceRecord> read(SourceQuery query) {
                return records.stream().peek(record -> {
                    if (record.sourceRecordId().equals("b")) {
                        var next = new OntologySchema(SCHEMA.namespace(), "2", SCHEMA.objectTypes(), SCHEMA.linkTypes(), SCHEMA.actionTypes());
                        if (f.storage instanceof JdbcStorageProvider jdbc) jdbc.activateSchema(CTX, next, null, jdbc.boundSchemaVersion());
                        else f.storage.applySchema(CTX, next);
                    }
                });
            }
        };
        var result = f.service.sync(connector, new SourceQuery("people", Map.of()), MAPPING, CTX);
        assertEquals(1, result.created());
        assertEquals(1, result.failures().size());
        assertNull(f.person("b"));
        assertEquals(1, f.checkpoint("hr").sequence());
    }

    @Test
    void checkpointFailureRollsBackReceiptFactsLineageAndAudit() throws Exception {
        var data = data("jdbc:h2:mem:checkpoint_failure;DB_CLOSE_DELAY=-1");
        var f = fixture(data);
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_checkpoint BEFORE INSERT ON of_ingestion_checkpoints FOR EACH ROW CALL 'org.openfoundry.foundation.sync.MaterializedIngestionTest$RejectCheckpoint'");
        }
        var source = record("hr", "a", 1, Map.of("name", "Atomic"));
        assertEquals(1, f.run("hr", List.of(source)).failures().size());
        assertNull(f.person("a"));
        assertNull(f.checkpoint("hr"));
        assertTrue(f.storage.getLineage(CTX, PERSON, LineageQuery.defaults()).isEmpty());
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            for (String table : List.of("of_ingestion_receipts", "of_audit_records", "of_outbox_events")) {
                try (var row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(row.next()); assertEquals(0, row.getInt(1)); }
            }
            statement.execute("DROP TRIGGER reject_checkpoint");
        }
        assertEquals(1, f.run("hr", List.of(source)).created());
    }

    @Test
    void singleConnectionAndFileRecoveryKeepSourceReceipts() {
        var data = data("jdbc:h2:file:" + directory.resolve("ingestion"));
        var f = fixture(data);
        var source = record("hr", "a", 1, Map.of("name", "Durable"));
        f.run("hr", List.of(source));
        var reopened = fixture(data);
        assertEquals(1, reopened.run("hr", List.of(source)).replayed());
        assertEquals(1, reopened.person("a").version());
        var pool = JdbcConnectionPool.create("jdbc:h2:mem:ingestion_pool;DB_CLOSE_DELAY=-1", "sa", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(2);
        try { commit(fixture(pool)); }
        finally { pool.dispose(); }
    }

    @Test
    void legacyUnknownOriginRequiresExplicitAdoption() throws Exception {
        var data = data("jdbc:h2:mem:unknown_origin;DB_CLOSE_DELAY=-1");
        var f = fixture(data);
        try (var tx = f.storage.beginTransaction(CTX)) { tx.createObject("Person", "a", Map.of("name", "Legacy")); tx.commit(); }
        try (var connection = data.getConnection(); var statement = connection.createStatement()) { statement.executeUpdate("DELETE FROM of_field_lineage"); }
        var source = record("hr", "a", 1, Map.of("name", "Imported"));
        assertEquals(1, f.run("hr", List.of(source)).failures().size());
        assertEquals("Legacy", f.person("a").properties().get("name"));
        assertNull(f.checkpoint("hr"));
        f.service = f.service.withUnknownOrigins(MaterializedSyncService.UnknownOrigins.TRUST_UPDATED_AT);
        assertEquals(1, f.run("hr", List.of(source)).updated());
        assertEquals("Imported", f.person("a").properties().get("name"));
    }

    @Test
    void unknownLegacyDeletionIsNotSilentlyReversed() throws Exception {
        var data = data("jdbc:h2:mem:unknown_deletion;DB_CLOSE_DELAY=-1");
        var f = fixture(data);
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.createObject("Person", "a", Map.of("name", "Archived"));
            tx.deleteObject("Person", "a", 1);
            tx.commit();
        }
        try (var connection = data.getConnection(); var statement = connection.createStatement()) { statement.executeUpdate("DELETE FROM of_field_lineage"); }
        var source = record("hr", "a", 1, Map.of("name", "Return"));
        assertEquals(1, f.run("hr", List.of(source)).failures().size());
        assertTrue(f.person("a").isDeleted());
        assertNull(f.checkpoint("hr"));
        f.service = f.service.withUnknownOrigins(MaterializedSyncService.UnknownOrigins.TRUST_UPDATED_AT);
        assertEquals(1, f.run("hr", List.of(source)).restored());
        assertFalse(f.person("a").isDeleted());
    }

    @Test
    void jdbcAndRestConnectorValuesUseTheSameNormalizedIngestionPath() throws Exception {
        var f = fixture(null);
        var data = data("jdbc:h2:mem:source_values;DB_CLOSE_DELAY=-1");
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE source_people (id INTEGER, name VARCHAR(50), note VARCHAR(50), born DATE)");
            statement.execute("INSERT INTO source_people VALUES (1, 'From JDBC', NULL, DATE '1980-04-01')");
        }
        var mapping = new MappingConfig("Person", "ID", Map.of("ID", "id", "NAME", "name", "NOTE", "note", "BORN", "born"));
        var imported = f.service.sync(new JdbcConnector("jdbc-source", "hr", data), new SourceQuery("SELECT id, name, note, born FROM source_people", Map.of()), mapping, CTX);
        assertTrue(imported.failures().isEmpty(), imported.failures().toString());
        assertEquals("1980-04-01", f.person("1").properties().get("born"));
        assertTrue(f.person("1").properties().containsKey("note"));
        assertNull(f.person("1").properties().get("note"));
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        server.createContext("/data", exchange -> {
            var body = "{\"data\":[{\"id\":\"rest-a\",\"name\":\"REST\",\"payload\":{\"amount\":1.0000000000000000001}}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var result = f.service.sync(new RestConnector("rest-source", "rest"), new SourceQuery("http://localhost:" + server.getAddress().getPort() + "/data", Map.of()), MAPPING, CTX);
            assertTrue(result.failures().isEmpty(), result.failures().toString());
            assertEquals(new java.math.BigDecimal("1.0000000000000000001"), ((Map<?, ?>) f.person("rest-a").properties().get("payload")).get("amount"));
        } finally { server.stop(0); }
    }

    @Test
    void invalidConflictFieldsFailBeforeReadingTheConnector() {
        var f = fixture(null);
        var called = new AtomicBoolean();
        Connector source = new Connector() {
            public String name() { return "hr"; }
            public Stream<SourceRecord> read(SourceQuery query) { called.set(true); return Stream.empty(); }
        };
        for (String field : List.of("missing", "id", "updatedAt")) {
            var service = new MaterializedSyncService(f.storage, new ConflictResolver(ConflictResolver.Strategy.LAST_WRITE_WINS,
                    Map.of(field, ConflictResolver.Strategy.ACTION_PRIORITY), Map.of())).withAuthorization((ctx, connector, mapping, target, tx) -> true);
            assertThrows(IllegalArgumentException.class, () -> service.sync(source, new SourceQuery("people", Map.of()), MAPPING, CTX));
        }
        assertFalse(called.get());
    }

    public static final class RejectCheckpoint implements org.h2.api.Trigger {
        @Override public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow) throws java.sql.SQLException { throw new java.sql.SQLException("Injected checkpoint failure"); }
    }

    static SourceRecord record(String system, String id, long sequence, Map<String, Object> values) {
        var data = new LinkedHashMap<>(values);
        data.put("id", id);
        Instant at = START.plusSeconds(sequence);
        return new SourceRecord(system, id, "UPSERT", at, data, new Provenance(system, id, Long.toString(sequence), "identity", at, "source", null),
                new SourcePosition("p0", "event-" + sequence, sequence, Map.of("offset", "cursor-" + sequence)));
    }
    static SourceRecord change(SourceRecord source, String operation) {
        return new SourceRecord(source.sourceSystem(), source.sourceRecordId(), operation, source.observedAt(), source.data(), source.provenance(), source.position());
    }
    static Connector connector(String name, List<SourceRecord> records) {
        return new Connector() {
            public String name() { return name; }
            public Stream<SourceRecord> read(SourceQuery query) { return records.stream(); }
        };
    }
    static JdbcDataSource data(String url) { var data = new JdbcDataSource(); data.setURL(url); return data; }
    static Fixture fixture(DataSource data) {
        var clock = new TestClock();
        StorageProvider storage = data == null ? new InMemoryStorageProvider(clock) : new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
        storage.applySchema(CTX, SCHEMA);
        return new Fixture(storage, clock);
    }
    static final class Fixture {
        final StorageProvider storage;
        final TestClock clock;
        MaterializedSyncService service;
        Fixture(StorageProvider storage, TestClock clock) {
            this.storage = storage; this.clock = clock;
            service = new MaterializedSyncService(storage).withAuthorization((ctx, connector, mapping, target, tx) -> true).withClock(clock);
        }
        MaterializedSyncService.SyncResult run(String connector, List<SourceRecord> records) {
            clock.now = clock.now.plusSeconds(1);
            return service.sync(connector(connector, records), new SourceQuery("people", Map.of()), MAPPING, CTX);
        }
        IngestionCheckpoint checkpoint(String connector) { return service.checkpoint(connector, MAPPING, "p0", CTX); }
        ObjectRecord person(String id) { return storage.getObject(CTX, "Person", id); }
        void edit(String name) {
            clock.now = clock.now.plusSeconds(1);
            var action = new ActionManifest("Edit", 1, false, List.of(), List.of(new ActionManifest.UpdateObject("person", Map.of("name", "params.name"))));
            execute(action, Map.of("person", person("a"), "name", name));
        }
        void clearNote() {
            clock.now = clock.now.plusSeconds(1);
            var action = new ActionManifest("ClearNote", 1, false, List.of(), List.of(new ActionManifest.UpdateObject("person", Map.of("note", "params.missing"))));
            execute(action, Map.of("person", person("a")));
        }
        void execute(ActionManifest action, Map<String, Object> parameters) {
            var actor = RequestContext.system(CTX.tenantId(), "operator");
            var definition = SCHEMA.actionTypes().stream().filter(type -> type.name().equals(action.action())).findFirst().orElseThrow();
            var executor = new ActionExecutor().withParameterSchema(SCHEMA).withAuthorization((ctx, who, type, values) -> true)
                    .withSideEffects(invocation -> fail(), clock, Duration.ofSeconds(10));
            assertTrue(executor.execute(action, definition, actor, new ActionActor("operator", Set.of()), parameters, UUID.randomUUID().toString(), storage).success());
        }
    }
    static final class TestClock extends Clock {
        Instant now = START;
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
