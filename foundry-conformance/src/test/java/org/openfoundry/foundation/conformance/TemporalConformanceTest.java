package org.openfoundry.foundation.conformance;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.*;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TemporalConformanceTest {
    static final RequestContext CONTEXT = RequestContext.system("time-tenant", "tester");
    static final Instant T0 = Instant.parse("2030-01-01T00:00:00Z");
    static final Instant T1 = T0.plusSeconds(10);
    static final Instant T2 = T0.plusSeconds(20);
    static final Instant T3 = T0.plusSeconds(30);
    static final EntityKey PERSON = new EntityKey("Person", "p");
    static final EntityKey ORG = new EntityKey("Organization", "a");
    static final PropertyDefinition ID = new PropertyDefinition("id", "ID", true, true, true, true, false, true);
    static final OntologySchema SCHEMA = new OntologySchema("temporal", "0.1.0",
            List.of(new ObjectTypeDefinition("Person", List.of(ID)), new ObjectTypeDefinition("Organization", List.of(ID))),
            List.of(new LinkTypeDefinition("BelongsTo", "Person", "Organization", Cardinality.MANY_TO_ONE, List.of(ID))), List.of());

    @TestFactory
    Stream<DynamicTest> sameTemporalRulesForBothProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                check(provider, "ordinary updates do not rewrite earlier valid time", this::ordinaryUpdates),
                check(provider, "deletion is a persistent tombstone", this::deletions),
                check(provider, "late facts distinguish valid from recorded time", this::lateFacts),
                check(provider, "past relationship overlaps and invalid endpoints are rejected", this::relationshipIntervals),
                check(provider, "history pagination filters before paging and isolates tenants", this::pagination),
                check(provider, "same-instant transitions use entity version", this::sameInstant),
                check(provider, "rollback leaves no temporal assertions", this::rollback),
                check(provider, "future and arbitrary backdated corrections are rejected", this::rejectUnsupported),
                check(provider, "deleted endpoints disappear from historical traversal", this::deletedEndpoint)));
    }

    private DynamicTest check(String provider, String name, Consumer<Fixture> test) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> {
            var clock = new TestClock();
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider(clock);
            else {
                var data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:temporal_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
            }
            storage.applySchema(CONTEXT, SCHEMA);
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.createObject("Person", "p", Map.of("name", "before"));
                tx.createObject("Organization", "a", Map.of("name", "A"));
                tx.createObject("Organization", "b", Map.of("name", "B"));
                tx.createLink("BelongsTo", "edge", PERSON, ORG, Map.of("role", "before"));
                tx.commit();
            }
            test.accept(new Fixture(storage, clock));
        });
    }

    private void ordinaryUpdates(Fixture f) {
        f.clock.now = T1;
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Person", "p", Map.of("name", "after"), 1);
            tx.updateLink("BelongsTo", "edge", Map.of("role", "after"), 1);
            tx.commit();
        }
        assertEquals("before", object(f, T0, T2).state().get("name"));
        assertEquals("after", object(f, T1, T2).state().get("name"));
        assertEquals("before", object(f, T2, T0).state().get("name"));
        assertEquals("before", link(f, T0, T2).state().get("role"));
        assertEquals("after", link(f, T1, T2).state().get("role"));
        assertEquals(T1, object(f, T1, T2).validFrom());
        assertEquals("before", f.storage.queryObjects(CONTEXT, "Person", at(T0, T2, false)).getFirst().properties().get("name"));
        assertEquals("before", f.storage.getLinks(CONTEXT, PERSON, "BelongsTo", StorageProvider.Direction.OUTBOUND, at(T0, T2, false)).getFirst().properties().get("role"));
        assertEquals("before", traverse(f, T0, T2).edges().getFirst().state().get("role"));
    }

    private void deletions(Fixture f) {
        f.clock.now = T1;
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.deleteLink("BelongsTo", "edge", 1);
            tx.deleteObject("Person", "p", 1);
            tx.commit();
        }
        assertEquals(EntityOperation.CREATED, object(f, T0, T3).operation());
        assertEquals(EntityOperation.CREATED, link(f, T0, T3).operation());
        assertEquals(EntityOperation.DELETED, object(f, T1, T3).operation());
        assertEquals(EntityOperation.DELETED, link(f, T3, T3).operation());
        assertNull(link(f, T3, T3).validTo(), "Tombstones cannot expire and expose the older active version");
        assertTrue(f.storage.queryObjects(CONTEXT, "Person", at(T3, T3, false)).isEmpty());
        assertTrue(f.storage.queryObjects(CONTEXT, "Person", at(T3, T3, true)).getFirst().isDeleted());
        assertTrue(f.storage.getLinks(CONTEXT, PERSON, "BelongsTo", StorageProvider.Direction.OUTBOUND, at(T3, T3, false)).isEmpty());
        var terminated = f.storage.getLinks(CONTEXT, PERSON, "BelongsTo", StorageProvider.Direction.OUTBOUND, at(T3, T3, true)).getFirst();
        assertTrue(terminated.isDeleted());
        assertFalse(terminated.isValidAt(T3));
        var oldMembership = f.storage.getLinks(CONTEXT, PERSON, "BelongsTo", StorageProvider.Direction.OUTBOUND, at(T0, T3, false)).getFirst();
        assertEquals(T0, oldMembership.validFrom());
        assertEquals(T1, oldMembership.validTo());
        assertTrue(oldMembership.isValidAt(T0));
        assertFalse(oldMembership.isValidAt(T1));
        assertEquals(List.of(ORG), traverse(f, T0, T3).nodes());
        assertTrue(traverse(f, T3, T3).nodes().isEmpty());
        assertEquals(EntityOperation.CREATED, link(f, T3, T0).operation());
        assertEquals(EntityOperation.DELETED, f.storage.getLinkAtVersion(CONTEXT, "BelongsTo", "edge", 2).operation());
        assertEquals(2, f.storage.getEntityHistory(CONTEXT, PERSON).size());
    }

    private void lateFacts(Fixture f) {
        f.clock.now = T2;
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Person", "p", Map.of("name", "late"), 1, T1);
            tx.updateLink("BelongsTo", "edge", Map.of("role", "late"), 1, T1);
            tx.commit();
        }
        assertEquals("before", object(f, T1, T1).state().get("name"));
        assertEquals("late", object(f, T1, T2).state().get("name"));
        assertEquals("before", object(f, T0, T2).state().get("name"));
        assertEquals(T1, link(f, T1, T2).validFrom());
        assertEquals(T2, link(f, T1, T2).recordedAt());
        assertEquals("before", traverse(f, T1, T1).edges().getFirst().state().get("role"));
        assertEquals("late", traverse(f, T1, T2).edges().getFirst().state().get("role"));
        f.clock.now = T3;
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.deleteLink("BelongsTo", "edge", 2, T2);
            tx.deleteObject("Person", "p", 2, T2);
            tx.commit();
        }
        assertEquals(EntityOperation.UPDATED, object(f, T2, T2).operation());
        assertEquals(EntityOperation.DELETED, object(f, T2, T3).operation());
        assertEquals(EntityOperation.DELETED, link(f, T2, T3).operation());
        assertEquals(T2, f.storage.getObject(CONTEXT, "Person", "p").deletedAt());
    }

    private void relationshipIntervals(Fixture f) {
        f.clock.now = T1;
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.deleteLink("BelongsTo", "edge", 1);
            tx.commit();
        }
        f.clock.now = T2;
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(IllegalStateException.class, () -> tx.createLink("BelongsTo", "overlap", PERSON,
                    new EntityKey("Organization", "b"), Map.of(), T0.plusSeconds(1)));
        }
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(IllegalArgumentException.class, () -> tx.createLink("BelongsTo", "prebirth", PERSON, ORG, Map.of(), T0.minusSeconds(1)));
        }
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.createLink("BelongsTo", "next", PERSON, new EntityKey("Organization", "b"), Map.of(), T1);
            tx.commit();
        }
        assertEquals(List.of(ORG), traverse(f, T0, T2).nodes());
        assertEquals(List.of(new EntityKey("Organization", "b")), traverse(f, T1, T2).nodes());
    }

    private void pagination(Fixture f) {
        f.clock.now = T1;
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.createObject("Person", "a", Map.of("name", "not-created-yet"));
            tx.createObject("Person", "z", Map.of("name", "late-created"), T0);
            tx.commit();
        }
        var page = f.storage.queryObjects(CONTEXT, "Person", new QueryOptions(1, 0, T0, T1, false));
        assertEquals("p", page.getFirst().id(), "Filter by time before applying offset/limit");
        assertEquals("z", f.storage.queryObjects(CONTEXT, "Person", new QueryOptions(1, 1, T0, T1, false)).getFirst().id());
        assertNull(f.storage.getObjectAtTime(CONTEXT, "Person", "z", T0, T0));
        var otherTenant = RequestContext.system("other", "tester");
        assertTrue(f.storage.queryObjects(otherTenant, "Person", at(T0, T1, false)).isEmpty());
        assertTrue(f.storage.getLinks(otherTenant, PERSON, "BelongsTo", StorageProvider.Direction.OUTBOUND, at(T0, T1, false)).isEmpty());
    }

    private void sameInstant(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Person", "p", Map.of("name", "same-time"), 1);
            tx.deleteLink("BelongsTo", "edge", 1);
            tx.commit();
        }
        assertEquals(2, object(f, T0, T0).version());
        assertEquals(EntityOperation.DELETED, link(f, T0, T0).operation());
        assertTrue(traverse(f, T0, T0).nodes().isEmpty());
    }

    private void rollback(Fixture f) {
        f.clock.now = T1;
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Person", "p", Map.of("name", "rolled-back"), 1);
            tx.deleteLink("BelongsTo", "edge", 1);
        }
        assertEquals(1, object(f, T1, T2).version());
        assertEquals(EntityOperation.CREATED, link(f, T1, T2).operation());
    }

    private void rejectUnsupported(Fixture f) {
        f.clock.now = T2;
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Person", "p", Map.of("name", "second"), 1, T1);
            tx.commit();
        }
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(IllegalArgumentException.class, () -> tx.updateObject("Person", "p", Map.of("name", "future"), 2, T3));
            assertThrows(IllegalArgumentException.class, () -> tx.updateObject("Person", "p", Map.of("name", "correction"), 2, T0));
        }
        assertThrows(IllegalArgumentException.class, () -> new QueryOptions(10, 0, T1, null, false));
        assertThrows(IllegalArgumentException.class, () -> new QueryOptions(10, 0, null, T1, false));
        assertEquals("second", object(f, T2, T3).state().get("name"));
    }

    private void deletedEndpoint(Fixture f) {
        f.clock.now = T1;
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.deleteObject("Organization", "a", 1);
            tx.commit();
        }
        assertEquals(List.of(ORG), traverse(f, T0, T2).nodes());
        assertTrue(traverse(f, T1, T2).nodes().isEmpty());
        assertTrue(f.storage.traverseAsOf(CONTEXT, ORG, List.of(new TraversalStep("BelongsTo", StorageProvider.Direction.INBOUND)), T1, T2, QueryOptions.defaults()).nodes().isEmpty());
    }

    private HistorySnapshot object(Fixture f, Instant valid, Instant recorded) {
        return f.storage.getObjectAtTime(CONTEXT, "Person", "p", valid, recorded);
    }
    private HistorySnapshot link(Fixture f, Instant valid, Instant recorded) {
        return f.storage.getLinkAtTime(CONTEXT, "BelongsTo", "edge", valid, recorded);
    }
    private QueryOptions at(Instant valid, Instant recorded, boolean deleted) {
        return new QueryOptions(100, 0, valid, recorded, deleted);
    }
    private TraversalResult traverse(Fixture f, Instant valid, Instant recorded) {
        return f.storage.traverseAsOf(CONTEXT, PERSON, List.of(new TraversalStep("BelongsTo", StorageProvider.Direction.OUTBOUND)), valid, recorded, QueryOptions.defaults());
    }
    private record Fixture(StorageProvider storage, TestClock clock) {}
    private static final class TestClock extends Clock {
        Instant now = T0;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
