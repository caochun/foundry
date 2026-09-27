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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SideEffectWorkflowTest {
    static final RequestContext CONTEXT = RequestContext.system("tenant", "operator");
    static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("operator", "tenant", Set.of("admin"));
    static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name: "sideeffects", version: "1.0.0")
            type Item @objectType {
              id: ID! @primary name: String! note: String fixed: String @immutable
              createdAt: DateTime! @readonly updatedAt: DateTime! @readonly
            }
            type Related @linkType(from: "Item", to: "Item", cardinality: MANY_TO_ONE) { id: ID! @primary }
            type Notice @objectType { id: ID! @primary name: String! }
            type NoticeFor @linkType(from: "Notice", to: "Item", cardinality: MANY_TO_ONE) { id: ID! @primary }
            type Work @actionType(permission: "can_work") { item: Item! @param target: Item! @param }
            """);
    static final EntityKey ITEM = new EntityKey("Item", "a");
    static final EntityKey TARGET = new EntityKey("Item", "b");
    static final String UPDATE = "  - type: updateObject\n    target: item\n    set: {name: AFTER, note: added}\n";

    @TestFactory
    Stream<DynamicTest> sideEffectsAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "success is post-commit and replay is not another call", this::success),
                test(provider, "retry waits durably and does not repeat business effects", this::retry),
                test(provider, "log and continue records warnings and runs later tasks", this::continueAfterFailure),
                test(provider, "rollback restores missing attributes as new facts", this::restoreObject),
                test(provider, "rollback restores terminated link identity and temporal lifecycle", this::restoreLink),
                test(provider, "rollback removes created objects and relationships", this::undoCreations),
                test(provider, "concurrent business changes prevent destructive compensation", this::conflict),
                test(provider, "missing handler rejects before any business write", this::noHandler),
                test(provider, "continuations retain actor tenant and current authorization boundaries", this::continuationSecurity),
                test(provider, "malformed continuation cannot replay business or external effects", this::malformed),
                test(provider, "repeated updates compensate in reverse order", this::repeatedUpdates),
                test(provider, "late failure from an expired worker cannot compensate a completed retry", this::expiredWorker),
                test(provider, "created object references work in subsequent effects and payloads", this::createdReferences),
                test(provider, "new external relationships prevent created-object compensation", this::newDependency)));
    }

    private DynamicTest test(String provider, String label, Consumer<Fixture> verify) {
        return DynamicTest.dynamicTest(provider + " " + label, () -> {
            var clock = new TestClock();
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider(clock);
            else {
                var data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:sideeffects_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
            }
            storage.applySchema(CONTEXT, SCHEMA);
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.createObject("Item", "a", Map.of("name", "BEFORE", "fixed", "frozen"));
                tx.createObject("Item", "b", Map.of("name", "Target"));
                tx.commit();
            }
            clock.advance(10);
            try { verify.accept(new Fixture(storage, clock)); }
            finally { if (storage instanceof AutoCloseable resource) resource.close(); }
        });
    }

    private void success(Fixture f) {
        var calls = new ArrayList<SideEffectHandler.Invocation>();
        var action = action(UPDATE, "ROLLBACK_ALL", "PT0S", 1);
        var app = f.app(action, invocation -> {
            assertEquals("AFTER", f.storage.getObject(CONTEXT, "Item", "a").properties().get("name"));
            assertEquals("BEFORE", ((Map<?, ?>) invocation.config().get("data")).get("name"));
            calls.add(invocation);
        });
        var result = execute(app, action, "success");
        assertTrue(result.success());
        assertEquals("COMPLETED", result.status());
        assertEquals(result, execute(app, action, "success"));
        assertEquals(1, calls.size());
        assertEquals(result.actionId() + "/event", calls.getFirst().idempotencyKey());
        assertTrue(f.storage.pendingActions(CONTEXT, f.clock.instant(), 10).isEmpty());
    }

    private void retry(Fixture f) {
        var calls = new ArrayList<SideEffectHandler.Invocation>();
        var action = action(UPDATE, "RETRY_INDEFINITELY", "PT5S", 1);
        var failing = f.app(action, invocation -> { calls.add(invocation); throw new IllegalStateException("offline"); });
        var pending = execute(failing, action, "retry");
        assertFalse(pending.success());
        assertEquals("PENDING", pending.status());
        assertEquals(2, f.storage.getObject(CONTEXT, "Item", "a").version());
        assertEquals(pending, execute(failing, action, "retry"));
        assertEquals(1, calls.size());
        f.clock.advance(5);
        assertEquals(1, f.storage.pendingActions(CONTEXT, f.clock.instant(), 10).size());
        var resumed = execute(f.app(action, calls::add), action, "retry");
        assertTrue(resumed.success());
        assertEquals(2, calls.size());
        assertEquals(calls.getFirst().config(), calls.getLast().config());
        assertEquals(calls.getFirst().idempotencyKey(), calls.getLast().idempotencyKey());
        assertEquals(2, calls.getLast().attempt());
        assertEquals(2, f.storage.getObject(CONTEXT, "Item", "a").version());
        var delayed = action(UPDATE, "RETRY_INDEFINITELY", "PT10M", 1);
        execute(f.app(delayed, invocation -> { throw new IllegalStateException("offline"); }), delayed, "long-delay");
        assertTrue(f.storage.pendingActions(CONTEXT, f.clock.instant().plusSeconds(599), 10).isEmpty());
        assertEquals(1, f.storage.pendingActions(CONTEXT, f.clock.instant().plusSeconds(600), 10).size());
    }

    private void continueAfterFailure(Fixture f) {
        var first = action(UPDATE, "LOG_AND_CONTINUE", "PT0S", 1);
        var next = new ActionManifest.SideEffect("next", "event", Map.of("type", "done"), 1, Duration.ZERO);
        var action = new ActionManifest(first.action(), first.version(), false, first.preconditions(), first.effects(),
                first.onSideEffectFailure(), List.of(first.sideEffects().getFirst(), next));
        var calls = new ArrayList<String>();
        var result = execute(f.app(action, invocation -> {
            calls.add(invocation.name());
            if (invocation.name().equals("event")) throw new IllegalStateException("failed");
        }), action, "continue");
        assertTrue(result.success());
        assertEquals("COMPLETED_WITH_WARNINGS", result.status());
        assertEquals(List.of("event", "next"), calls);
        assertEquals(List.of(new ActionResult.Failure("SIDE_EFFECT_FAILURE", "event")), result.errors());
        assertEquals("AFTER", f.storage.getObject(CONTEXT, "Item", "a").properties().get("name"));
    }

    private void restoreObject(Fixture f) {
        var before = f.storage.getObject(CONTEXT, "Item", "a");
        var action = action(UPDATE, "ROLLBACK_ALL", "PT0S", 1);
        var result = execute(f.app(action, invocation -> {
            f.clock.advance(1);
            throw new IllegalStateException("failed");
        }), action, "rollback-object");
        assertFalse(result.success());
        assertEquals("ROLLED_BACK", result.status());
        var restored = f.storage.getObject(CONTEXT, "Item", "a");
        assertEquals("BEFORE", restored.properties().get("name"));
        assertFalse(restored.properties().containsKey("note"));
        assertEquals(before.properties().get("createdAt"), restored.properties().get("createdAt"));
        assertEquals("frozen", restored.properties().get("fixed"));
        assertEquals(f.clock.instant().toString(), restored.properties().get("updatedAt"));
        assertEquals(3, restored.version());
        assertEquals("AFTER", f.storage.getObjectAtTime(CONTEXT, "Item", "a", TestClock.START.plusSeconds(10), f.clock.instant()).state().get("name"));
        assertEquals("BEFORE", f.storage.getObjectAtTime(CONTEXT, "Item", "a", f.clock.instant(), f.clock.instant()).state().get("name"));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            var patch = new java.util.HashMap<String, Object>();
            patch.put("note", null);
            tx.updateObject("Item", "a", patch, restored.version());
            tx.commit();
        }
        var again = execute(f.app(action, invocation -> { throw new IllegalStateException("failed"); }), action, "rollback-null");
        assertEquals("ROLLED_BACK", again.status());
        var nullState = f.storage.getObject(CONTEXT, "Item", "a").properties();
        assertTrue(nullState.containsKey("note"));
        assertNull(nullState.get("note"));
    }

    private void restoreLink(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.createLink("Related", "original", ITEM, TARGET, Map.of());
            tx.commit();
        }
        f.clock.advance(1);
        Instant deletedAt = f.clock.instant();
        var action = action("  - type: deleteLink\n    linkType: Related\n    filter: {from: item}\n    expect: ONE\n", "ROLLBACK_ALL", "PT0S", 1);
        var result = execute(f.app(action, invocation -> { f.clock.advance(1); throw new IllegalStateException("failed"); }), action, "rollback-link");
        assertEquals("ROLLED_BACK", result.status());
        var restored = f.storage.getLink(CONTEXT, "Related", "original");
        assertFalse(restored.isDeleted());
        assertEquals(3, restored.version());
        assertEquals(f.clock.instant(), restored.validFrom());
        assertEquals(EntityOperation.RESTORED, f.storage.getEntityHistory(CONTEXT, new EntityKey("Related", "original")).getLast().operation());
        assertTrue(f.storage.getLinks(CONTEXT, ITEM, "Related", StorageProvider.Direction.OUTBOUND,
                new QueryOptions(10, 0, deletedAt, f.clock.instant(), false)).isEmpty());
        var active = f.storage.getLinks(CONTEXT, ITEM, "Related", StorageProvider.Direction.OUTBOUND,
                new QueryOptions(10, 0, f.clock.instant(), f.clock.instant(), false)).getFirst();
        assertEquals(f.clock.instant(), active.validFrom());
        assertNull(active.validTo());
        assertTrue(active.isValidAt(f.clock.instant()));
    }

    private void undoCreations(Fixture f) {
        var action = action("""
                  - type: createObject
                    objectType: Item
                    target: generated
                    properties: {name: created}
                  - type: createLink
                    linkType: Related
                    from: item
                    to: target
                    properties: {}
                """, "ROLLBACK_ALL", "PT0S", 1);
        var result = execute(f.app(action, invocation -> { throw new IllegalStateException("failed"); }), action, "create-rollback");
        assertEquals("ROLLED_BACK", result.status());
        assertTrue(f.storage.getObject(CONTEXT, "Item", result.affected().getFirst().id()).isDeleted());
        assertTrue(f.storage.getLink(CONTEXT, "Related", result.affected().getLast().id()).isDeleted());
    }

    private void conflict(Fixture f) {
        var action = action(UPDATE + "  - type: createLink\n    linkType: Related\n    from: item\n    to: target\n", "ROLLBACK_ALL", "PT0S", 1);
        var app = f.app(action, invocation -> {
            try (var tx = f.storage.beginTransaction(CONTEXT)) {
                tx.updateObject("Item", "a", Map.of("name", "CONCURRENT"), 2);
                tx.commit();
            }
            throw new IllegalStateException("failed");
        });
        var result = execute(app, action, "conflict");
        assertEquals("COMPENSATION_FAILED", result.status());
        assertEquals("CONCURRENT", f.storage.getObject(CONTEXT, "Item", "a").properties().get("name"));
        assertFalse(f.storage.getLink(CONTEXT, "Related", result.affected().getLast().id()).isDeleted());
        assertEquals(result, execute(app, action, "conflict"));
    }

    private void continuationSecurity(Fixture f) {
        var action = action(UPDATE, "RETRY_INDEFINITELY", "PT5S", 1);
        var pending = execute(f.app(action, invocation -> { throw new IllegalStateException("offline"); }), action, "protected");
        f.clock.advance(5);
        var otherActor = RequestContext.system("tenant", "other");
        assertTrue(f.storage.pendingActions(otherActor, f.clock.instant(), 10).isEmpty());
        assertTrue(f.storage.pendingActions(RequestContext.system("other", "operator"), f.clock.instant(), 10).isEmpty());
        try (var tx = f.storage.beginTransaction(otherActor)) {
            assertThrows(SecurityException.class, () -> tx.getActionExecution(pending.actionId()));
        }
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var denied = new ActionExecutor().withSideEffects(invocation -> calls.incrementAndGet(), f.clock, Duration.ofSeconds(30));
        var definition = SCHEMA.actionTypes().getFirst();
        var actor = new ActionActor("operator", Set.of("admin"));
        assertThrows(SecurityException.class, () -> denied.resume(action, definition, pending.actionId(), CONTEXT, actor, f.storage));
        assertEquals(0, calls.get());
        var authorized = denied.withAuthorization((context, who, type, values) -> true);
        var resumed = authorized.resumePending(Map.of("Work", action), Map.of("Work", definition), CONTEXT, actor, 10, f.storage);
        assertEquals(1, resumed.size());
        assertTrue(resumed.getFirst().success());
        assertEquals(1, calls.get());
        assertEquals(2, f.storage.getObject(CONTEXT, "Item", "a").version());
    }

    private void malformed(Fixture f) {
        var action = action(UPDATE, "RETRY_INDEFINITELY", "PT5S", 1);
        var pending = execute(f.app(action, invocation -> { throw new IllegalStateException("offline"); }), action, "malformed");
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.acquireWrite();
            var run = tx.getActionExecution(pending.actionId());
            var state = new java.util.LinkedHashMap<>(run.state());
            state.put("format", 1.5);
            tx.putActionExecution(new ActionExecution(run.id(), run.actorId(), run.action(), run.version() + 1,
                    run.status(), run.availableAt(), state), run.version());
            tx.commit();
        }
        f.clock.advance(5);
        var app = f.app(action, invocation -> fail("Malformed state must never call the destination"));
        assertThrows(IllegalStateException.class, () -> execute(app, action, "malformed"));
        assertEquals(2, f.storage.getObject(CONTEXT, "Item", "a").version());
    }

    private void repeatedUpdates(Fixture f) {
        var action = action(UPDATE + "  - type: updateObject\n    target: item\n    set: {name: SECOND}\n", "ROLLBACK_ALL", "PT0S", 1);
        var result = execute(f.app(action, invocation -> { throw new IllegalStateException("offline"); }), action, "repeated");
        assertEquals("ROLLED_BACK", result.status());
        var restored = f.storage.getObject(CONTEXT, "Item", "a");
        assertEquals("BEFORE", restored.properties().get("name"));
        assertFalse(restored.properties().containsKey("note"));
        assertEquals(5, restored.version());
    }

    private void expiredWorker(Fixture f) {
        var action = action(UPDATE, "ROLLBACK_ALL", "PT0S", 1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var app = f.app(action, invocation -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try { assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                throw new IllegalStateException("Late failed response");
            }
        });
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var old = executor.submit(() -> execute(app, action, "expired"));
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("RUNNING", execute(app, action, "expired").status());
            assertEquals(1, calls.get());
            f.clock.advance(30);
            var recovered = execute(app, action, "expired");
            assertEquals("COMPLETED", recovered.status());
            release.countDown();
            assertEquals(recovered, old.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(2, calls.get());
            assertEquals("AFTER", f.storage.getObject(CONTEXT, "Item", "a").properties().get("name"));
            assertEquals(2, f.storage.getObject(CONTEXT, "Item", "a").version());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            release.countDown();
        }
    }

    private void createdReferences(Fixture f) {
        var base = action("""
                  - type: createObject
                    objectType: Notice
                    properties: {name: New}
                  - type: createLink
                    linkType: NoticeFor
                    from: notice
                    to: item
                  - type: updateObject
                    target: notice
                    set: {name: AFTER}
                """, "ROLLBACK_ALL", "PT0S", 1);
        var event = new ActionManifest.SideEffect("event", "event", Map.of("type", "created", "data", Map.of("id", "notice.id", "name", "notice.name")), 1, Duration.ZERO);
        var action = new ActionManifest(base.action(), base.version(), false, base.preconditions(), base.effects(), base.onSideEffectFailure(), List.of(event));
        var data = new ArrayList<Map<String, Object>>();
        var result = execute(f.app(action, invocation -> data.add(invocation.config())), action, "created-reference");
        assertTrue(result.success());
        var created = result.affected().getFirst();
        assertEquals(Map.of("id", created.id(), "name", "New"), data.getFirst().get("data"));
        assertEquals("AFTER", f.storage.getObject(CONTEXT, "Notice", created.id()).properties().get("name"));
        var failed = execute(f.app(action, invocation -> { throw new IllegalStateException("failed"); }), action, "created-compensated");
        assertEquals("ROLLED_BACK", failed.status());
        assertTrue(f.storage.getObject(CONTEXT, "Notice", failed.affected().getFirst().id()).isDeleted());
        assertTrue(f.storage.getLink(CONTEXT, "NoticeFor", failed.affected().get(1).id()).isDeleted());
        assertFalse(f.storage.getObject(CONTEXT, "Notice", created.id()).isDeleted());
    }

    private void newDependency(Fixture f) {
        var action = action("  - type: createObject\n    objectType: Notice\n    properties: {name: New}\n", "ROLLBACK_ALL", "PT0S", 1);
        var result = execute(f.app(action, invocation -> {
            try (var tx = f.storage.beginTransaction(CONTEXT)) {
                var run = tx.getActionExecution(invocation.actionId());
                var key = (Map<?, ?>) ((List<?>) run.state().get("affected")).getFirst();
                tx.createLink("NoticeFor", "external-dependency", new EntityKey("Notice", (String) key.get("id")), ITEM, Map.of());
                tx.commit();
            }
            throw new IllegalStateException("failed");
        }), action, "dependent-object");
        assertEquals("COMPENSATION_FAILED", result.status());
        assertFalse(f.storage.getObject(CONTEXT, "Notice", result.affected().getFirst().id()).isDeleted());
        assertFalse(f.storage.getLink(CONTEXT, "NoticeFor", "external-dependency").isDeleted());
    }

    private void noHandler(Fixture f) {
        var action = action(UPDATE, "ROLLBACK_ALL", "PT0S", 1);
        var app = new ApplicationService(f.storage, new AuthorizationService((principal, relation, key) -> true),
                new ActionExecutor(), SCHEMA, Map.of("Work", action), Map.of());
        assertThrows(IllegalStateException.class, () -> execute(app, action, "unsupported"));
        assertEquals(1, f.storage.getObject(CONTEXT, "Item", "a").version());
        assertTrue(f.storage.pendingActions(CONTEXT, f.clock.instant(), 10).isEmpty());
    }

    static ActionManifest action(String effects, String policy, String delay, int attempts) {
        return new ActionManifestParser().parse("action: Work\nversion: 1\neffects:\n" + effects + """
                sideEffects:
                  - name: event
                    type: event
                    config:
                      type: example.changed
                      data: {id: item.id, name: item.name}
                    retries: %d
                    retryDelay: %s
                rollback: {onSideEffectFailure: %s}
                """.formatted(attempts, delay, policy));
    }

    private static ActionResult execute(ApplicationService app, ActionManifest action, String key) {
        return app.execute(action, CONTEXT, PRINCIPAL, Map.of("item", "a", "target", "b"), key);
    }

    record Fixture(StorageProvider storage, TestClock clock) {
        ApplicationService app(ActionManifest action, SideEffectHandler handler) {
            return new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> true),
                    new ActionExecutor().withSideEffects(handler, clock, Duration.ofSeconds(30)), SCHEMA, Map.of("Work", action), Map.of());
        }
    }

    static final class TestClock extends Clock {
        static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
        volatile Instant now = START;
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
