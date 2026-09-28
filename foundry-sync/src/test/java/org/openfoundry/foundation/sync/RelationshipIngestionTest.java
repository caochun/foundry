package org.openfoundry.foundation.sync;

import org.h2.jdbcx.*;
import org.junit.jupiter.api.*;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RelationshipIngestionTest {
    static final RequestContext CTX = RequestContext.system("tenant", "worker");
    static final EntityKey PERSON = new EntityKey("Person", "p");
    static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name:"relation-sync",version:"1")
            type Person @objectType { id: ID! @primary name: String! }
            type Unit @objectType { id: ID! @primary name: String! }
            type Member @linkType(from:"Person",to:"Unit",cardinality:MANY_TO_ONE) { id: ID! @primary note: String weight: Int code: String @unique @immutable }
            type Solo @linkType(from:"Person",to:"Unit",cardinality:ONE_TO_ONE) { id: ID! @primary note: String }
            type Many @linkType(from:"Person",to:"Unit",cardinality:MANY_TO_MANY) { id: ID! @primary note: String }
            """);
    static final MappingConfig MAPPING = mapping("Member");

    @TestFactory
    Stream<DynamicTest> relationshipsAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "creates stable relationships and independent source evidence", this::create),
                test(provider, "retarget closes old identity and returning restores its history", this::retarget),
                test(provider, "missing foreign key preserves and null explicitly clears", this::clear),
                test(provider, "manual clear blocks a new target under Action priority", this::manualClear),
                test(provider, "manual relationship properties survive updates and parent deletion", this::manualProperties),
                test(provider, "source priority can explicitly supersede an old manual decision", this::supersede),
                test(provider, "many-to-many reconciliation preserves unrelated manual edges", this::many),
                test(provider, "incoming single-target authority is also enforced", this::incoming),
                test(provider, "target cardinality failures roll back object and checkpoint", this::cardinality),
                test(provider, "endpoint revocation prevents both import and old receipt replay", this::authorization),
                test(provider, "permission loss after staged relationship writes rolls everything back", this::lateDenial),
                test(provider, "same event cannot change only its foreign key", this::changedReplay),
                test(provider, "historical receipt reuses old references after retarget", this::replay),
                test(provider, "scope history and metadata obey tenant boundaries", this::tenants),
                test(provider, "missing target rolls back the complete record", this::missingTarget),
                test(provider, "parent deletion and restoration include managed relationships", this::parentLifecycle),
                test(provider, "immutable relationship properties still constrain restoration", this::immutableRestore),
                test(provider, "empty relationship observations preserve provenance parity", this::emptyObservation),
                test(provider, "relationship policies receive complete proposed endpoints", this::policyEndpoints),
                test(provider, "multiple named slots manage only their own many-to-many edges", this::multipleSlots)));
    }

    private DynamicTest test(String provider, String name, Consumer<Fixture> verify) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> verify.accept(fixture(provider.equals("jdbc") ? data("jdbc:h2:mem:relations_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1") : null)));
    }

    private void create(Fixture f) {
        var result = f.run(MAPPING, record(1, "u1", Map.of("note", "Imported", "weight", 3)));
        assertTrue(result.failures().isEmpty(), result.failures().toString());
        assertEquals(Map.of("created", 1), result.relationshipChanges());
        var edge = f.links("Member").getFirst();
        assertEquals(Map.of("note", "Imported", "weight", 3), edge.properties());
        assertEquals("u1", edge.to().id());
        var history = f.storage.getRelationshipAssertions(CTX, scope("Member"), 100, null);
        assertEquals(1, history.size());
        assertEquals(MutationSource.Kind.SYNC, history.getFirst().source().kind());
        assertEquals(edge.id(), history.getFirst().linkId());
        assertEquals(1, f.storage.getRelationshipAssertions(CTX, new RelationshipScope(edge.to(), "Member", StorageProvider.Direction.INBOUND), 100, null).size());
        assertEquals(1, f.run(MAPPING, record(1, "u1", Map.of("note", "Imported", "weight", 3))).replayed());
        assertEquals(1, f.links("Member").size());
        assertEquals(1, f.links("Member").getFirst().version());
    }

    private void retarget(Fixture f) {
        f.run(MAPPING, record(1, "u1", Map.of("note", "First")));
        String original = f.links("Member").getFirst().id();
        var moved = f.run(MAPPING, record(2, "u2", Map.of("note", "Second")));
        assertTrue(moved.failures().isEmpty(), moved.failures().toString());
        assertEquals(Map.of("created", 1, "deleted", 1), moved.relationshipChanges());
        assertTrue(f.storage.getLink(CTX, "Member", original).isDeleted());
        assertNotEquals(original, f.links("Member").getFirst().id());
        var returned = f.run(MAPPING, record(3, "u1", Map.of("note", "Returned")));
        assertTrue(returned.failures().isEmpty(), returned.failures().toString());
        assertEquals(original, f.links("Member").getFirst().id());
        assertEquals("Returned", f.links("Member").getFirst().properties().get("note"));
        assertEquals(List.of(EntityOperation.CREATED, EntityOperation.DELETED, EntityOperation.RESTORED, EntityOperation.UPDATED),
                f.storage.getEntityHistory(CTX, new EntityKey("Member", original)).stream().map(HistorySnapshot::operation).toList());
    }

    private void clear(Fixture f) {
        f.run(MAPPING, record(1, "u1", Map.of()));
        String id = f.links("Member").getFirst().id();
        var missing = new SourceRecord("hr", "row", "UPSERT", START.plusSeconds(2), Map.of("id", "p", "name", "Changed"), null, position(2));
        assertTrue(f.run(MAPPING, missing).failures().isEmpty());
        assertEquals(id, f.links("Member").getFirst().id());
        var result = f.run(MAPPING, record(3, null, Map.of()));
        assertEquals(Map.of("deleted", 1), result.relationshipChanges());
        assertTrue(f.links("Member").isEmpty());
        assertTrue(f.storage.getLink(CTX, "Member", id).isDeleted());
    }

    private void manualClear(Fixture f) {
        f.actionPolicy();
        f.run(MAPPING, record(1, "u1", Map.of()));
        var edge = f.links("Member").getFirst();
        f.manual(tx -> tx.deleteLink("Member", edge.id(), edge.version()));
        var result = f.run(MAPPING, record(2, "u2", Map.of()));
        assertTrue(result.failures().isEmpty(), result.failures().toString());
        assertTrue(result.relationshipChanges().isEmpty());
        assertTrue(result.conflicts() > 0);
        assertTrue(f.links("Member").isEmpty());
        assertEquals(MutationSource.Kind.ACTION, f.storage.getRelationshipAssertions(CTX, scope("Member"), 1, null).getFirst().source().kind());
    }

    private void manualProperties(Fixture f) {
        f.actionPolicy();
        f.run(MAPPING, record(1, "u1", Map.of("note", "Imported")));
        var edge = f.links("Member").getFirst();
        f.manual(tx -> tx.updateLink("Member", edge.id(), Map.of("note", "Manual"), edge.version()));
        var same = f.run(MAPPING, record(2, "u1", Map.of("note", "External")));
        assertTrue(same.failures().isEmpty(), same.failures().toString());
        assertEquals("Manual", f.links("Member").getFirst().properties().get("note"));
        var deleted = record(3, null, Map.of());
        deleted = new SourceRecord(deleted.sourceSystem(), deleted.sourceRecordId(), "DELETE", deleted.observedAt(), deleted.data(), null, deleted.position());
        var result = f.run(MAPPING, deleted);
        assertTrue(result.failures().isEmpty(), result.failures().toString());
        assertEquals(1, result.ignored());
        assertFalse(f.storage.getObject(CTX, "Person", "p").isDeleted());
        assertEquals("Manual", f.links("Member").getFirst().properties().get("note"));
    }

    private void supersede(Fixture f) {
        manualClear(f);
        f.sourcePolicy();
        assertTrue(f.run("override", MAPPING, record(3, "u2", Map.of())).failures().isEmpty());
        assertEquals("u2", f.links("Member").getFirst().to().id());
        f.actionPolicy();
        var resumed = f.run("resumed", MAPPING, record(4, "u1", Map.of()));
        assertTrue(resumed.failures().isEmpty(), resumed.failures().toString());
        assertEquals("u1", f.links("Member").getFirst().to().id());
    }

    private void many(Fixture f) {
        f.sourcePolicy();
        var mapping = mapping("Many");
        f.run(mapping, record(1, "u1", Map.of("note", "Source")));
        f.manual(tx -> tx.createLink("Many", "manual", PERSON, new EntityKey("Unit", "u2"), Map.of("note", "Manual")));
        var result = f.run(mapping, record(2, "u3", Map.of("note", "New")));
        assertTrue(result.failures().isEmpty(), result.failures().toString());
        assertEquals(Set.of("u2", "u3"), f.links("Many").stream().map(link -> link.to().id()).collect(java.util.stream.Collectors.toSet()));
        assertFalse(f.storage.getLink(CTX, "Many", "manual").isDeleted());
        f.run(mapping, record(3, null, Map.of()));
        assertEquals(List.of("manual"), f.links("Many").stream().map(LinkRecord::id).toList());
    }

    private void incoming(Fixture f) {
        f.actionPolicy();
        try (var tx = f.storage.beginTransaction(CTX)) { tx.createObject("Person", "manual-person", Map.of("name", "Other")); tx.commit(); }
        f.manual(tx -> {
            tx.createLink("Solo", "old", new EntityKey("Person", "manual-person"), new EntityKey("Unit", "u2"), Map.of());
            tx.deleteLink("Solo", "old", 1);
        });
        var result = f.run(mapping("Solo"), record(1, "u2", Map.of()));
        assertTrue(result.failures().isEmpty(), result.failures().toString());
        assertTrue(result.relationshipChanges().isEmpty());
        assertTrue(f.links("Solo").isEmpty());
        assertTrue(result.conflicts() > 0);
    }

    private void cardinality(Fixture f) {
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.createObject("Person", "other", Map.of("name", "Other"));
            tx.createLink("Solo", "occupied", new EntityKey("Person", "other"), new EntityKey("Unit", "u1"), Map.of());
            tx.commit();
        }
        var result = f.run(mapping("Solo"), record(1, "u1", Map.of()));
        assertEquals(1, result.failures().size());
        assertNull(f.storage.getObject(CTX, "Person", "p"));
        assertNull(f.service.checkpoint("hr", mapping("Solo"), "p0", CTX));
        assertFalse(f.storage.getLink(CTX, "Solo", "occupied").isDeleted());
    }

    private void authorization(Fixture f) {
        var source = record(1, "u1", Map.of());
        f.run(MAPPING, source);
        f.denied.add(new EntityKey("Unit", "u2"));
        var next = f.run(MAPPING, record(2, "u2", Map.of()));
        assertEquals(1, next.failures().size());
        assertEquals("u1", f.links("Member").getFirst().to().id());
        f.denied.add(new EntityKey("Unit", "u1"));
        assertEquals(1, f.run(MAPPING, source).failures().size());
        assertEquals(1, f.service.checkpoint("hr", MAPPING, "p0", CTX).sequence());
    }

    private void lateDenial(Fixture f) {
        f.service = f.service.withAuthorization((context, connector, mapping, target, tx) -> target == null || !target.type().equals("Member")
                || tx.getLink(target.type(), target.id()) == null);
        var result = f.run(MAPPING, record(1, "u1", Map.of()));
        assertEquals(1, result.failures().size());
        assertNull(f.storage.getObject(CTX, "Person", "p"));
        assertTrue(f.links("Member").isEmpty());
        assertTrue(f.storage.getRelationshipAssertions(CTX, scope("Member"), 100, null).isEmpty());
    }

    private void changedReplay(Fixture f) {
        f.run(MAPPING, record(1, "u1", Map.of()));
        assertEquals(1, f.run(MAPPING, record(1, "u2", Map.of())).failures().size());
        assertEquals("u1", f.links("Member").getFirst().to().id());
    }

    private void replay(Fixture f) {
        var source = record(1, "u1", Map.of());
        f.run(MAPPING, source);
        f.run(MAPPING, record(2, "u2", Map.of()));
        assertEquals(1, f.run(MAPPING, source).replayed());
        assertEquals("u2", f.links("Member").getFirst().to().id());
        f.denied.add(new EntityKey("Unit", "u1"));
        assertEquals(1, f.run(MAPPING, source).failures().size());
        assertEquals("u2", f.links("Member").getFirst().to().id());
    }

    private void tenants(Fixture f) {
        f.run(MAPPING, record(1, "u1", Map.of()));
        assertTrue(f.storage.getRelationshipAssertions(RequestContext.system("other", "worker"), scope("Member"), 100, null).isEmpty());
        try (var tx = f.storage.beginTransaction(RequestContext.system("other", "worker"))) { assertNull(tx.relationshipAssertion(scope("Member"))); }
        var rows = f.storage.getRelationshipAssertions(CTX, scope("Member"), 1, null);
        assertEquals(1, rows.size());
        assertTrue(f.storage.getRelationshipAssertions(CTX, scope("Member"), 100, rows.getFirst().revision()).isEmpty());
    }

    private void missingTarget(Fixture f) {
        assertEquals(1, f.run(MAPPING, record(1, "absent", Map.of())).failures().size());
        assertNull(f.storage.getObject(CTX, "Person", "p"));
        assertNull(f.service.checkpoint("hr", MAPPING, "p0", CTX));
    }

    private void parentLifecycle(Fixture f) {
        f.run(MAPPING, record(1, "u1", Map.of("note", "Owned")));
        String id = f.links("Member").getFirst().id();
        var input = record(2, null, Map.of());
        var deleted = new SourceRecord(input.sourceSystem(), input.sourceRecordId(), "DELETE", input.observedAt(), input.data(), null, input.position());
        var result = f.run(MAPPING, deleted);
        assertEquals(1, result.deleted());
        assertEquals(Map.of("deleted", 1), result.relationshipChanges());
        assertTrue(f.storage.getObject(CTX, "Person", "p").isDeleted());
        assertTrue(f.storage.getLink(CTX, "Member", id).isDeleted());
        var restored = f.run(MAPPING, record(3, "u1", Map.of("note", "Owned")));
        assertTrue(restored.failures().isEmpty(), restored.failures().toString());
        assertEquals(1, restored.restored());
        assertEquals(Map.of("restored", 1), restored.relationshipChanges());
        assertEquals(id, f.links("Member").getFirst().id());
    }

    private void immutableRestore(Fixture f) {
        f.run(MAPPING, record(1, "u1", Map.of("code", "first")));
        String original = f.links("Member").getFirst().id();
        f.run(MAPPING, record(2, "u2", Map.of("code", "second")));
        var rejected = f.run(MAPPING, record(3, "u1", Map.of("code", "changed")));
        assertEquals(1, rejected.failures().size());
        assertEquals("u2", f.links("Member").getFirst().to().id());
        assertTrue(f.storage.getLink(CTX, "Member", original).isDeleted());
        assertEquals(2, f.service.checkpoint("hr", MAPPING, "p0", CTX).sequence());
        assertTrue(f.run(MAPPING, record(3, "u1", Map.of("code", "first"))).failures().isEmpty());
        assertEquals(original, f.links("Member").getFirst().id());
    }

    private void emptyObservation(Fixture f) {
        var first = f.run(MAPPING, record(1, null, Map.of()));
        assertTrue(first.failures().isEmpty(), first.failures().toString());
        assertTrue(f.links("Member").isEmpty());
        var rows = f.storage.getRelationshipAssertions(CTX, scope("Member"), 100, null);
        assertEquals(1, rows.size());
        assertNull(rows.getFirst().operation());
        assertNull(rows.getFirst().linkId());
        assertEquals(MutationSource.Kind.SYNC, rows.getFirst().source().kind());
        assertEquals(1, f.run(MAPPING, record(1, null, Map.of())).replayed());
        assertEquals(1, f.storage.getRelationshipAssertions(CTX, scope("Member"), 100, null).size());
    }

    private void policyEndpoints(Fixture f) {
        var calls = new ArrayList<EntityKey>();
        f.service = f.service.withAuthorization(new SyncAuthorizer() {
            @Override public boolean allowed(RequestContext context, String connector, MappingConfig mapping, EntityKey target, Transaction tx) {
                return target == null || !target.type().equals("Member");
            }
            @Override public boolean allowedRelationship(RequestContext context, String connector, MappingConfig mapping, EntityKey relationship, EntityKey from, EntityKey to, Transaction tx) {
                assertEquals(PERSON, from);
                assertEquals("Member", relationship.type());
                assertEquals("Unit", to.type());
                calls.add(to);
                return to.id().equals("u1");
            }
        });
        assertTrue(f.run(MAPPING, record(1, "u1", Map.of())).failures().isEmpty());
        assertFalse(calls.isEmpty());
        assertEquals(1, f.run(MAPPING, record(2, "u2", Map.of())).failures().size());
        assertEquals(1, f.run(MAPPING, record(1, "u1", Map.of())).replayed());
    }

    private void multipleSlots(Fixture f) {
        var mapping = new MappingConfig("Person", new KeyMapping("id", "id", null), Map.of("name", new PropertyMapping("name")),
                List.of(new LinkMapping("primary", "Many", "Unit", new KeyMapping("first", "id", null), Map.of()),
                        new LinkMapping("secondary", "Many", "Unit", new KeyMapping("second", "id", null), Map.of())));
        var first = new SourceRecord("hr", "row", "UPSERT", START.plusSeconds(1), Map.of("id", "p", "name", "Person", "first", "u1", "second", "u2"), null, position(1));
        assertEquals(Map.of("created", 2), f.run(mapping, first).relationshipChanges());
        var nextValues = new LinkedHashMap<String, Object>(); nextValues.put("id", "p"); nextValues.put("name", "Person"); nextValues.put("first", null);
        var next = new SourceRecord("hr", "row", "UPSERT", START.plusSeconds(2), nextValues, null, position(2));
        assertEquals(Map.of("deleted", 1), f.run(mapping, next).relationshipChanges());
        assertEquals(List.of("u2"), f.links("Many").stream().map(link -> link.to().id()).toList());
    }

    @Test
    void jdbcScopeFailureRollsBackLinkObjectLineageAndCheckpoint() throws Exception {
        var data = data("jdbc:h2:mem:relationship_failure;DB_CLOSE_DELAY=-1");
        var f = fixture(data);
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_scope BEFORE INSERT ON of_relationship_assertions FOR EACH ROW CALL 'org.openfoundry.foundation.sync.RelationshipIngestionTest$RejectScope'");
        }
        assertEquals(1, f.run(MAPPING, record(1, "u1", Map.of())).failures().size());
        assertNull(f.storage.getObject(CTX, "Person", "p"));
        assertTrue(f.links("Member").isEmpty());
        assertTrue(f.storage.getLineage(CTX, PERSON, LineageQuery.defaults()).isEmpty());
        assertNull(f.service.checkpoint("hr", MAPPING, "p0", CTX));
    }

    @Test
    void oneConnectionSupportsRelationshipReconciliationAndReplay() {
        var pool = JdbcConnectionPool.create("jdbc:h2:mem:relationship_pool;DB_CLOSE_DELAY=-1", "sa", "");
        pool.setMaxConnections(1); pool.setLoginTimeout(2);
        try { retarget(fixture(pool)); }
        finally { pool.dispose(); }
    }

    @Test
    void truncatedReceiptAndUnknownLegacyMembershipFailClosed() throws Exception {
        var data = data("jdbc:h2:mem:relationship_receipt;DB_CLOSE_DELAY=-1");
        var f = fixture(data);
        var input = record(1, "u1", Map.of());
        f.run(MAPPING, input);
        try (var connection = data.getConnection(); var query = connection.createStatement(); var rows = query.executeQuery("SELECT receipt_key, result_json FROM of_ingestion_receipts")) {
            assertTrue(rows.next());
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var body = mapper.readValue(rows.getString(2), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            body.put("relationships", List.of());
            try (var update = connection.prepareStatement("UPDATE of_ingestion_receipts SET result_json = ? WHERE receipt_key = ?")) {
                update.setString(1, mapper.writeValueAsString(body)); update.setString(2, rows.getString(1)); update.executeUpdate();
            }
        }
        assertEquals(1, f.run(MAPPING, input).failures().size());
        try (var connection = data.getConnection(); var statement = connection.createStatement()) { statement.executeUpdate("DELETE FROM of_relationship_assertions"); }
        assertEquals(1, f.run(MAPPING, record(2, "u2", Map.of())).failures().size());
        assertEquals("u1", f.links("Member").getFirst().to().id());
        f.service = f.service.withUnknownOrigins(MaterializedSyncService.UnknownOrigins.TRUST_UPDATED_AT);
        assertTrue(f.run("adopt", MAPPING, record(3, "u2", Map.of())).failures().isEmpty());
        assertEquals("u2", f.links("Member").getFirst().to().id());
    }

    @Test
    void malformedScopeEvidenceDoomsAFactTransaction() throws Exception {
        var data = data("jdbc:h2:mem:malformed_scope;DB_CLOSE_DELAY=-1");
        var f = fixture(data);
        f.run(mapping("Many"), record(1, "u1", Map.of()));
        try (var connection = data.getConnection(); var statement = connection.createStatement()) { statement.executeUpdate("UPDATE of_relationship_assertions SET source_json = '{}'"); }
        try (var tx = f.storage.beginTransaction(CTX)) {
            assertThrows(IllegalStateException.class, () -> tx.createLink("Many", "rejected", PERSON, new EntityKey("Unit", "u2"), Map.of()));
            assertThrows(IllegalStateException.class, tx::commit);
        }
        assertNull(f.storage.getLink(CTX, "Many", "rejected"));
    }

    public static final class RejectScope implements org.h2.api.Trigger {
        @Override public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow) throws java.sql.SQLException { throw new java.sql.SQLException("Injected scope failure"); }
    }
    static MappingConfig mapping(String type) {
        var props = new LinkedHashMap<String, PropertyMapping>(); props.put("note", new PropertyMapping("note"));
        if (type.equals("Member")) { props.put("weight", new PropertyMapping("weight")); props.put("code", new PropertyMapping("code")); }
        return new MappingConfig("Person", new KeyMapping("id", "id", null), Map.of("name", new PropertyMapping("name")),
                List.of(new LinkMapping(type, "Unit", new KeyMapping("unit", "id", null), props)));
    }
    static RelationshipScope scope(String type) { return new RelationshipScope(PERSON, type, StorageProvider.Direction.OUTBOUND); }
    static SourcePosition position(long value) { return new SourcePosition("p0", "event-" + value, value, "cursor-" + value); }
    static SourceRecord record(long version, String unit, Map<String, Object> values) {
        var data = new LinkedHashMap<>(values); data.put("id", "p"); data.put("name", "Person"); data.put("unit", unit);
        return new SourceRecord("hr", "row", "UPSERT", START.plusSeconds(version), data, null, position(version));
    }
    static JdbcDataSource data(String url) { var data = new JdbcDataSource(); data.setURL(url); return data; }
    static Fixture open(DataSource data) {
        var clock = new TestClock();
        StorageProvider storage = data == null ? new InMemoryStorageProvider(clock) : new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
        storage.applySchema(CTX, SCHEMA);
        return new Fixture(storage, clock);
    }
    static Fixture fixture(DataSource data) {
        var fixture = open(data);
        var storage = fixture.storage;
        try (var tx = storage.beginTransaction(CTX)) {
            for (String id : List.of("u1", "u2", "u3")) tx.createObject("Unit", id, Map.of("name", id));
            tx.commit();
        }
        return fixture;
    }
    static final class Fixture {
        final StorageProvider storage;
        final TestClock clock;
        final Set<EntityKey> denied = new HashSet<>();
        MaterializedSyncService service;
        Fixture(StorageProvider storage, TestClock clock) { this.storage = storage; this.clock = clock; policy(ConflictResolver.Strategy.LAST_WRITE_WINS, Map.of()); }
        void actionPolicy() { policy(ConflictResolver.Strategy.ACTION_PRIORITY, Map.of()); }
        void sourcePolicy() { policy(ConflictResolver.Strategy.SOURCE_PRIORITY, Map.of("hr", 0)); }
        void policy(ConflictResolver.Strategy strategy, Map<String, Integer> ranks) {
            service = new MaterializedSyncService(storage, new ConflictResolver(strategy, Map.of(), ranks))
                    .withAuthorization((context, connector, mapping, target, tx) -> target == null || !denied.contains(target)).withClock(clock);
        }
        MaterializedSyncService.SyncResult run(MappingConfig mapping, SourceRecord row) { return run("hr", mapping, row); }
        MaterializedSyncService.SyncResult run(String name, MappingConfig mapping, SourceRecord row) {
            clock.now = clock.now.plusSeconds(1);
            Connector connector = new Connector() {
                public String name() { return name; }
                public Stream<SourceRecord> read(SourceQuery query) { return Stream.of(row); }
            };
            return service.sync(connector, new SourceQuery("people", Map.of()), mapping, CTX);
        }
        List<LinkRecord> links(String type) {
            try (var tx = storage.beginTransaction(CTX)) { return tx.findLinks(type, PERSON, null); }
        }
        void manual(Consumer<Transaction> work) {
            clock.now = clock.now.plusSeconds(1);
            try (var tx = storage.beginTransaction(CTX)) {
                tx.mutationSource(MutationSource.action("Manual", "manual-" + UUID.randomUUID(), clock.instant(), false));
                work.accept(tx); tx.commit();
            }
        }
    }
    static final class TestClock extends Clock {
        Instant now = START;
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
