package org.openfoundry.foundation.api;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FilteredLinkActionTest {
    private static final RequestContext CONTEXT = RequestContext.system("tenant", "operator");
    private static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("operator", "tenant", Set.of("admin"));
    private static final EntityKey SOURCE = new EntityKey("Source", "s");
    private static final EntityKey TARGET = new EntityKey("Target", "t");
    private static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name: "filters", version: "1.0.0")
            type Source @objectType { id: ID! @primary state: String! at: DateTime copied: String owner: String }
            type Target @objectType { id: ID! @primary }
            type Related @linkType(from: "Source", to: "Target", cardinality: MANY_TO_MANY) {
              id: ID! @primary at: DateTime
            }
            type Unlink @actionType(permission: "can_unlink") {
              source: Source! @param target: Target! @param
            }
            """);
    private static final String PREFIX = "action: Unlink\nversion: 1\neffects:\n";
    private static final String UPDATE = "  - type: updateObject\n    target: source\n    set: {state: AFTER}\n";

    @TestFactory
    Stream<DynamicTest> filteredDeletionAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "ONE checks cardinality before committing earlier writes", this::one),
                test(provider, "ALL crosses storage page sizes and isolates tenants", this::all),
                test(provider, "both endpoints and inbound filters narrow the selection", this::endpoints),
                test(provider, "same transaction sees created and deleted relationships", this::ownWrites),
                test(provider, "every selected edge and endpoint is authorized", this::authorization),
                test(provider, "replay checks original targets and cannot delete replacement links", this::replay),
                test(provider, "effect expressions read one snapshot and timestamp", this::expressions),
                test(provider, "concurrent selection and same-key replay are atomic", this::concurrency)));
    }

    private DynamicTest test(String provider, String label, Consumer<Fixture> body) {
        return DynamicTest.dynamicTest(provider + " " + label, () -> {
            StorageProvider storage;
            JdbcDataSource data = null;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider();
            else {
                data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:filtered_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2());
            }
            storage.applySchema(CONTEXT, SCHEMA);
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.createObject("Source", "s", Map.of("state", "BEFORE"));
                tx.createObject("Target", "t", Map.of());
                tx.createObject("Target", "other", Map.of());
                tx.commit();
            }
            try {
                body.accept(new Fixture(storage, data));
            } finally {
                if (storage instanceof AutoCloseable resource) resource.close();
            }
        });
    }

    private void one(Fixture f) {
        var manifest = parse(UPDATE + deletion("{from: source, active: true}", "ONE"));
        var empty = assertThrows(LinkResolutionException.class, () -> f.execute(manifest, "one"));
        assertEquals("LINK_NOT_FOUND", empty.code());
        f.edge("e1", TARGET);
        f.edge("e2", TARGET);
        var many = assertThrows(LinkResolutionException.class, () -> f.execute(manifest, "one"));
        assertEquals("LINK_RESOLUTION_AMBIGUOUS", many.code());
        assertEquals("BEFORE", f.storage.getObject(CONTEXT, "Source", "s").properties().get("state"));
        assertEquals(1, f.storage.getEntityHistory(CONTEXT, SOURCE).size());
        assertEquals(0, f.auditCount());
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.deleteLink("Related", "e2", 1);
            tx.commit();
        }
        assertTrue(f.execute(manifest, "one").success());
        assertTrue(f.storage.getLink(CONTEXT, "Related", "e1").isDeleted());
        assertEquals(1, f.auditCount());
    }

    private void all(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            for (int i = 0; i < 105; i++) tx.createLink("Related", "e%03d".formatted(i), SOURCE, TARGET, Map.of());
            tx.commit();
        }
        var other = RequestContext.system("other", "operator");
        try (var tx = f.storage.beginTransaction(other)) {
            tx.createObject("Source", "s", Map.of("state", "BEFORE"));
            tx.createObject("Target", "t", Map.of());
            tx.createLink("Related", "e000", SOURCE, TARGET, Map.of());
            tx.commit();
        }
        var manifest = parse(deletion("{from: source}", "ALL"));
        var result = f.execute(manifest, "all");
        assertEquals(105, result.affected().size());
        assertTrue(f.storage.getLinks(CONTEXT, SOURCE, "Related", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).isEmpty());
        assertFalse(f.storage.getLink(other, "Related", "e000").isDeleted());
        assertEquals(0, f.execute(manifest, "empty").affected().size());
        assertEquals(2, f.storage.getLink(CONTEXT, "Related", "e000").version());
    }

    private void endpoints(Fixture f) {
        f.edge("e1", TARGET);
        f.edge("e2", new EntityKey("Target", "other"));
        assertEquals(List.of(new EntityKey("Related", "e1")), f.execute(parse(deletion("{from: params.source, to: params.target}", "ONE")), "both").affected());
        assertFalse(f.storage.getLink(CONTEXT, "Related", "e2").isDeleted());
        f.edge("e3", TARGET);
        assertEquals(List.of(new EntityKey("Related", "e3")), f.execute(parse(deletion("{to: target, active: false}", "ALL")), "inbound").affected());
        assertThrows(IllegalArgumentException.class, () -> f.execute(parse(deletion("{from: params.missing}", "ALL")), "missing"));
        assertEquals(0, f.execute(parse(deletion("{}", "ALL")), "unbounded").affected().size());
    }

    private void ownWrites(Fixture f) {
        var manifest = parse("""
                  - type: createLink
                    linkType: Related
                    from: source
                    to: target
                    properties: {at: now}
                """ + deletion("{from: source, to: target}", "ONE"));
        var result = f.execute(manifest, "own");
        assertEquals(2, result.affected().size());
        assertEquals(result.affected().getFirst(), result.affected().getLast());
        var link = f.storage.getLink(CONTEXT, "Related", result.affected().getFirst().id());
        assertTrue(link.isDeleted());
        assertEquals(2, f.storage.getEntityHistory(CONTEXT, result.affected().getFirst()).size());
        // Selection excludes an already terminated row on the second effect.
        var repeat = parse(deletion("{from: source}", "ALL") + deletion("{from: source}", "ALL"));
        assertTrue(f.execute(repeat, "none").affected().isEmpty());
    }

    private void authorization(Fixture f) {
        f.edge("allowed", TARGET);
        f.edge("hidden", new EntityKey("Target", "other"));
        var manifest = parse(UPDATE + deletion("{from: source}", "ALL"));
        for (var denied : List.of(new EntityKey("Related", "hidden"), new EntityKey("Target", "other"))) {
            f.denied.add(denied);
            assertThrows(SecurityException.class, () -> f.execute(manifest, "protected"));
            assertThrows(SecurityException.class, () -> f.execute(parse(UPDATE + deletion("{from: source}", "ONE")), "hidden-count"));
            assertFalse(f.storage.getLink(CONTEXT, "Related", "allowed").isDeleted());
            assertEquals("BEFORE", f.storage.getObject(CONTEXT, "Source", "s").properties().get("state"));
            assertEquals(0, f.auditCount());
            f.denied.clear();
        }
        assertTrue(f.execute(manifest, "protected").success());
    }

    private void replay(Fixture f) {
        var historicalTarget = new EntityKey("Target", "other");
        f.edge("original", historicalTarget);
        var manifest = parse(deletion("{from: source}", "ALL"));
        var first = f.execute(manifest, "receipt");
        f.edge("replacement", TARGET);
        assertEquals(first, f.execute(manifest, "receipt"));
        assertFalse(f.storage.getLink(CONTEXT, "Related", "replacement").isDeleted());
        assertEquals(1, f.auditCount());
        for (var denied : List.of(historicalTarget, new EntityKey("Related", "original"))) {
            f.denied.add(denied);
            assertThrows(SecurityException.class, () -> f.execute(manifest, "receipt"));
            f.denied.clear();
        }
    }

    private void concurrency(Fixture f) {
        var manifest = parse(deletion("{from: source}", "ONE"));
        f.edge("first", TARGET);
        var competing = concurrently(() -> f.execute(manifest, "a"), () -> f.execute(manifest, "b"));
        assertEquals(1, competing.stream().filter(ActionResult.class::isInstance).count());
        assertEquals(1, competing.stream().filter(value -> value instanceof LinkResolutionException error
                && error.code().equals("LINK_NOT_FOUND")).count());
        assertEquals(1, f.auditCount());
        f.edge("second", TARGET);
        var replayed = concurrently(() -> f.execute(manifest, "same"), () -> f.execute(manifest, "same"));
        assertInstanceOf(ActionResult.class, replayed.getFirst());
        assertEquals(replayed.getFirst(), replayed.getLast());
        assertEquals(2, f.auditCount());
    }

    private static List<Object> concurrently(java.util.concurrent.Callable<ActionResult> first,
                                             java.util.concurrent.Callable<ActionResult> second) {
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = Stream.of(first, second).map(operation -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Start timeout");
                try {
                    return (Object) operation.call();
                } catch (LinkResolutionException expected) {
                    return expected;
                }
            })).toList();
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            return List.of(futures.getFirst().get(20, java.util.concurrent.TimeUnit.SECONDS),
                    futures.getLast().get(20, java.util.concurrent.TimeUnit.SECONDS));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private void expressions(Fixture f) {
        var manifest = parse("""
                  - type: updateObject
                    target: params.source
                    set: {state: AFTER, at: now, owner: actor.id}
                  - type: updateObject
                    target: source
                    set: {copied: source.state}
                  - type: createLink
                    linkType: Related
                    from: params.source
                    to: target
                    properties: {at: now}
                """);
        var result = f.execute(manifest, "expressions");
        var source = f.storage.getObject(CONTEXT, "Source", "s");
        var link = f.storage.getLink(CONTEXT, "Related", result.affected().getLast().id());
        assertEquals("AFTER", source.properties().get("state"));
        assertEquals("BEFORE", source.properties().get("copied"));
        assertEquals("operator", source.properties().get("owner"));
        assertEquals(source.properties().get("at"), link.properties().get("at"));
        assertDoesNotThrow(() -> Instant.parse((String) source.properties().get("at")));
    }

    private static ActionManifest parse(String effects) {
        return new ActionManifestParser().parse(PREFIX + effects);
    }

    private static String deletion(String filter, String expect) {
        return "  - type: deleteLink\n    linkType: Related\n    filter: " + filter + "\n    expect: " + expect + "\n";
    }

    private static final class Fixture {
        final StorageProvider storage;
        final JdbcDataSource data;
        final Set<EntityKey> denied = new HashSet<>();

        Fixture(StorageProvider storage, JdbcDataSource data) {
            this.storage = storage;
            this.data = data;
        }

        void edge(String id, EntityKey to) {
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.createLink("Related", id, SOURCE, to, Map.of());
                tx.commit();
            }
        }

        ActionResult execute(ActionManifest manifest, String key) {
            var app = new ApplicationService(storage, new AuthorizationService((principal, relation, entity) -> !denied.contains(entity)),
                    new ActionExecutor(), SCHEMA, Map.of("Unlink", manifest), Map.of());
            return app.execute(manifest, CONTEXT, PRINCIPAL, Map.of("source", "s", "target", "t"), key);
        }

        int auditCount() {
            if (storage instanceof InMemoryStorageProvider memory) return memory.auditEntries(CONTEXT).size();
            try (var connection = data.getConnection(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT COUNT(*) FROM of_audit_records WHERE tenant_id = 'tenant'")) {
                rows.next();
                return rows.getInt(1);
            } catch (java.sql.SQLException failure) {
                throw new AssertionError(failure);
            }
        }
    }
}
