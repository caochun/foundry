package org.openfoundry.foundation.api;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.*;

import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ActionNavigationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    static final RequestContext CTX = RequestContext.system("tenant", "operator");
    static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("operator", "tenant", Set.of());
    static final String ODL = """
            extend schema @namespace(name:"action_paths",version:"1")
            type Person @objectType {
                id: ID! @primary name: String!
                unit: Unit @link(type:"Assigned",direction:OUTBOUND)
                secretUnit: Unit @link(type:"Assigned",direction:OUTBOUND) @sensitive
                assignments: [Assigned!] @link(type:"Assigned",direction:OUTBOUND,history:true)
            }
            type Unit @objectType {
                id: ID! @primary name: String! note: String
                region: Region @link(type:"Located",direction:OUTBOUND)
                people: [Person!] @link(type:"Assigned",direction:INBOUND)
            }
            type Ticket @objectType { id: ID! @primary name: String! unit: Unit @link(type:"TicketUnit",direction:OUTBOUND) }
            type TicketUnit @linkType(from:"Ticket",to:"Unit",cardinality:MANY_TO_ONE) { id: ID! @primary }
            type Region @objectType { id: ID! @primary name: String! }
            type Assigned @linkType(from:"Person",to:"Unit",cardinality:MANY_TO_ONE) { id: ID! @primary note: String }
            type Located @linkType(from:"Unit",to:"Region",cardinality:MANY_TO_ONE) { id: ID! @primary }
            type Work @actionType(permission:"can_work") { person: Person! @param unit: Unit! @param }
            type Connect @actionType(permission:"can_connect") { person: Person! @param }
            """;
    static final OntologySchema SCHEMA = new OdlParser().parse(ODL);
    static final Map<String, Object> INPUT = Map.of("person", "p", "unit", "u");
    static final String UPDATE = """
              - type: updateObject
                target: person.unit
                set: {note: person.unit.region.name}
            """;

    @TestFactory
    Stream<DynamicTest> navigationAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "multihop CEL effect target and event values share a snapshot", this::multihop),
                test(provider, "missing single relationships become null", this::missing),
                test(provider, "declared collections and relationship history retain their shape", this::collections),
                test(provider, "navigation denies hidden endpoints and relationships before writing", this::denied),
                test(provider, "replay checks original relationship even after replacement", this::replay),
                test(provider, "continuations retain original values and recheck read access", this::continuation),
                test(provider, "compensation restores navigated targets", this::compensation),
                test(provider, "incomplete navigation evidence rejects replay", this::corrupt),
                test(provider, "related read permission does not grant update authority", this::writePermission),
                test(provider, "sensitive relationship projections require field policy", this::fieldPolicy),
                test(provider, "ontology mode checks endpoint permissions instead of nonexistent link FGA types", this::ontology),
                test(provider, "Consent gates related subjects including replay", this::consent),
                test(provider, "a consent decision does not invalidate its own final read check", this::revokeEffect),
                test(provider, "false Consent conditions do not resolve their subject", this::skippedConsent),
                test(provider, "created aliases resolve links when capturing side effects", this::created),
                test(provider, "link creation filtered deletion and derived IDs use captured paths", this::effects),
                test(provider, "revocation after mutation rolls the entire transaction back", this::midActionRevocation),
                test(provider, "applied conditional consent requires navigation evidence", this::conditionalConsentEvidence),
                test(provider, "creating a link respects the selected endpoint permission mode", this::endpointWrites)));
    }

    private DynamicTest test(String provider, String name, Consumer<Fixture> check) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> check.accept(fixture(provider.equals("jdbc"))));
    }

    private void multihop(Fixture f) {
        var action = action(UPDATE, "person.unit.region.name == 'North'");
        var sent = new ArrayList<SideEffectHandler.Invocation>();
        action = withEvent(action, "LOG_AND_CONTINUE");
        var app = f.app(action, sent::add);
        var result = app.execute(action, CTX, PRINCIPAL, INPUT, "multi");
        assertTrue(result.success());
        assertEquals(List.of(new EntityKey("Unit", "u")), result.affected());
        assertEquals("North", f.storage.getObject(CTX, "Unit", "u").properties().get("note"));
        assertEquals(Map.of("region", "North", "unit", "u"), sent.getFirst().config().get("data"));
        assertEquals(result, app.execute(action, CTX, PRINCIPAL, INPUT, "multi"));
        assertEquals(1, sent.size());
    }

    private void missing(Fixture f) {
        try (var tx = f.storage.beginTransaction(CTX)) { tx.deleteLink("Assigned", "a", 1); tx.commit(); }
        var action = action("", "person.unit == null");
        assertTrue(f.app(action, invocation -> fail()).execute(action, CTX, PRINCIPAL, INPUT, "none").success());
        var invalid = action(UPDATE, null);
        assertThrows(IllegalArgumentException.class, () -> f.app(invalid, invocation -> fail()).execute(invalid, CTX, PRINCIPAL, INPUT, "absent-target"));
        assertEquals(1, f.storage.getObject(CTX, "Unit", "u").version());
    }

    private void collections(Fixture f) {
        var action = action("", "unit.people.size() == 1 && unit.people.exists(p, p.name == 'Person') && person.assignments[0].note == 'original'");
        assertTrue(f.app(action, invocation -> fail()).execute(action, CTX, PRINCIPAL, INPUT, "collections").success());
        try (var tx = f.storage.beginTransaction(CTX)) { tx.deleteLink("Assigned", "a", 1); tx.commit(); }
        var historical = action("", "unit.people.size() == 0 && person.assignments.size() == 1 && person.assignments[0].note == 'original'");
        assertTrue(f.app(historical, invocation -> fail()).execute(historical, CTX, PRINCIPAL, INPUT, "history").success());
    }

    private void denied(Fixture f) {
        var action = action(UPDATE, null);
        for (var key : List.of(new EntityKey("Region", "r"), new EntityKey("Located", "l"), new EntityKey("Assigned", "a"))) {
            f.denied.add(key);
            assertThrows(SecurityException.class, () -> f.app(action, invocation -> fail()).execute(action, CTX, PRINCIPAL, INPUT, "denied"));
            assertEquals(1, f.storage.getObject(CTX, "Unit", "u").version());
            f.denied.clear();
        }
    }

    private void replay(Fixture f) {
        var action = action(UPDATE, null);
        var app = f.app(action, invocation -> fail());
        var result = app.execute(action, CTX, PRINCIPAL, INPUT, "replay");
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.deleteLink("Assigned", "a", 1);
            tx.createObject("Unit", "other", Map.of("name", "Other"));
            tx.createLink("Assigned", "other-a", new EntityKey("Person", "p"), new EntityKey("Unit", "other"), Map.of());
            tx.commit();
        }
        assertEquals(result, app.execute(action, CTX, PRINCIPAL, INPUT, "replay"));
        f.denied.add(new EntityKey("Region", "r"));
        assertThrows(SecurityException.class, () -> app.execute(action, CTX, PRINCIPAL, INPUT, "replay"));
        assertEquals(2, f.storage.getObject(CTX, "Unit", "u").version());
    }

    private void continuation(Fixture f) {
        var action = withEvent(action(UPDATE, null), "RETRY_INDEFINITELY");
        var pending = f.app(action, invocation -> { throw new IllegalStateException("offline"); }).execute(action, CTX, PRINCIPAL, INPUT, "retry");
        assertEquals("PENDING", pending.status());
        try (var tx = f.storage.beginTransaction(CTX)) { tx.updateObject("Region", "r", Map.of("name", "Changed"), 1); tx.commit(); }
        f.clock.now = f.clock.now.plusSeconds(10);
        var sent = new ArrayList<SideEffectHandler.Invocation>();
        var app = f.app(action, sent::add);
        f.denied.add(new EntityKey("Region", "r"));
        assertThrows(SecurityException.class, () -> app.resume(CTX, PRINCIPAL, "Work", pending.actionId()));
        assertTrue(sent.isEmpty());
        f.denied.clear();
        assertTrue(app.resume(CTX, PRINCIPAL, "Work", pending.actionId()).success());
        assertEquals(Map.of("region", "North", "unit", "u"), sent.getFirst().config().get("data"));
        assertEquals(2, f.storage.getObject(CTX, "Unit", "u").version());
    }

    private void compensation(Fixture f) {
        var action = withEvent(action(UPDATE, null), "ROLLBACK_ALL");
        var result = f.app(action, invocation -> { throw new IllegalStateException("rejected"); }).execute(action, CTX, PRINCIPAL, INPUT, "undo");
        assertEquals("ROLLED_BACK", result.status());
        assertFalse(f.storage.getObject(CTX, "Unit", "u").properties().containsKey("note"));
        assertEquals(3, f.storage.getObject(CTX, "Unit", "u").version());
    }

    private void corrupt(Fixture f) {
        var action = withEvent(action(UPDATE, null), "RETRY_INDEFINITELY");
        var app = f.app(action, invocation -> { throw new IllegalStateException("offline"); });
        var pending = app.execute(action, CTX, PRINCIPAL, INPUT, "corrupt");
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.acquireWrite();
            var run = tx.getActionExecution(pending.actionId());
            var state = new LinkedHashMap<>(run.state());
            state.put("navigation", List.of());
            tx.putActionExecution(new ActionExecution(run.id(), run.actorId(), run.action(), run.version() + 1, run.status(), run.availableAt(), state), run.version());
            tx.commit();
        }
        assertThrows(IllegalStateException.class, () -> app.execute(action, CTX, PRINCIPAL, INPUT, "corrupt"));
    }

    private void writePermission(Fixture f) {
        f.deniedGrants.add("can_work/Region/r");
        var readOnly = action("", "person.unit.region.name == 'North'");
        assertTrue(f.app(readOnly, invocation -> fail()).execute(readOnly, CTX, PRINCIPAL, INPUT, "read-only").success());
        var update = action("  - type: updateObject\n    target: person.unit.region\n    set: {name: Changed}\n", null);
        assertThrows(SecurityException.class, () -> f.app(update, invocation -> fail()).execute(update, CTX, PRINCIPAL, INPUT, "write-denied"));
        assertEquals(1, f.storage.getObject(CTX, "Region", "r").version());
    }

    private void fieldPolicy(Fixture f) {
        var action = action(UPDATE.replace("person.unit", "person.secretUnit"), null);
        assertThrows(SecurityException.class, () -> f.app(action, invocation -> fail()).execute(action, CTX, PRINCIPAL, INPUT, "secret"));
        var app = new ApplicationService(f.storage, f.authorization(), new ActionExecutor(), SCHEMA, Map.of("Work", action),
                Map.of("Person", new FieldPolicy(Set.of("id", "name", "secretUnit"), Map.of())));
        assertTrue(app.execute(action, CTX, PRINCIPAL, INPUT, "secret").success());
    }

    private void ontology(Fixture f) {
        f.deniedTypes.add("Assigned");
        f.deniedTypes.add("Located");
        var action = action(UPDATE, null);
        var app = f.app(action, invocation -> fail(), AuthorizationMode.ONTOLOGY_TARGETS, null);
        f.deniedGrants.add("editor/Unit/u");
        assertThrows(SecurityException.class, () -> app.execute(action, CTX, PRINCIPAL, INPUT, "ontology"));
        f.deniedGrants.clear();
        assertTrue(app.execute(action, CTX, PRINCIPAL, INPUT, "ontology").success());
    }

    private void consent(Fixture f) {
        var policy = f.consent();
        var action = action(UPDATE, null);
        var app = f.app(action, invocation -> fail(), AuthorizationMode.STRICT_RESOURCES, policy);
        assertThrows(ConsentDeniedException.class, () -> app.execute(action, CTX, PRINCIPAL, INPUT, "consent"));
        assertEquals(1, f.storage.getObject(CTX, "Unit", "u").version());
        f.store.record(CTX, new EntityKey("Region", "r"), "PURPOSE", ConsentRecord.Decision.GRANT, "approved");
        assertTrue(app.execute(action, CTX, PRINCIPAL, INPUT, "consent").success());
        f.store.record(CTX, new EntityKey("Region", "r"), "PURPOSE", ConsentRecord.Decision.DENY, "withdrawn");
        assertThrows(ConsentDeniedException.class, () -> app.execute(action, CTX, PRINCIPAL, INPUT, "consent"));
    }

    private void revokeEffect(Fixture f) {
        var policy = f.consent();
        f.store.record(CTX, new EntityKey("Region", "r"), "PURPOSE", ConsentRecord.Decision.GRANT, "approved");
        var action = action(UPDATE + "  - type: recordConsent\n    subject: person.unit.region\n    decision: DENY\n", null);
        var app = f.app(action, invocation -> fail(), AuthorizationMode.STRICT_RESOURCES, policy);
        assertTrue(app.execute(action, CTX, PRINCIPAL, INPUT, "revoke").success());
        assertFalse(policy.check(CTX, PRINCIPAL, new EntityKey("Region", "r"), "PURPOSE").allowed());
        assertEquals(2, f.store.snapshot(CTX, new EntityKey("Region", "r")).records().size());
    }

    private void skippedConsent(Fixture f) {
        f.denied.add(new EntityKey("Region", "r"));
        var action = action("  - type: recordConsent\n    subject: person.unit.region\n    condition: 'false'\n", null);
        var app = f.app(action, invocation -> fail(), AuthorizationMode.STRICT_RESOURCES, f.consent());
        var result = app.execute(action, CTX, PRINCIPAL, INPUT, "skip");
        assertTrue(result.success());
        assertEquals(result, app.execute(action, CTX, PRINCIPAL, INPUT, "skip"));
        assertTrue(f.store.snapshot(CTX, new EntityKey("Region", "r")).records().isEmpty());
    }

    private void created(Fixture f) {
        f.deniedTypes.add("Ticket");
        f.deniedTypes.add("TicketUnit");
        var base = action("""
                  - type: createObject
                    objectType: Ticket
                    properties: {name: New}
                  - type: createLink
                    linkType: TicketUnit
                    from: ticket
                    to: unit
                """, null);
        var event = new ActionManifest.SideEffect("notify", "event", Map.of("type", "ticket", "data", Map.of("region", "ticket.unit.region.name")), 1, Duration.ZERO);
        var action = new ActionManifest(base.action(), 1, false, base.preconditions(), base.effects(), ActionManifest.RollbackPolicy.LOG_AND_CONTINUE, List.of(event));
        var sent = new ArrayList<SideEffectHandler.Invocation>();
        // Ontology mode recognizes server-side creation evidence during replay/continuation as well.
        var result = f.app(action, sent::add, AuthorizationMode.ONTOLOGY_TARGETS, null).execute(action, CTX, PRINCIPAL, INPUT, "created");
        assertTrue(result.success());
        assertEquals(Map.of("region", "North"), sent.getFirst().config().get("data"));
    }

    private void effects(Fixture f) {
        var action = withEvent(action("""
                  - type: createObject
                    objectType: Ticket
                    target: params.person.unit.region.id
                    properties: {name: person.unit.region.name}
                  - type: createLink
                    linkType: TicketUnit
                    from: ticket
                    to: person.unit
                  - type: deleteLink
                    linkType: Assigned
                    filter: {from: person, to: person.unit}
                    expect: ONE
                """, null), "LOG_AND_CONTINUE");
        var sent = new ArrayList<SideEffectHandler.Invocation>();
        var result = f.app(action, sent::add).execute(action, CTX, PRINCIPAL, INPUT, "effects");
        assertTrue(result.success());
        assertEquals("North", f.storage.getObject(CTX, "Ticket", "r").properties().get("name"));
        assertTrue(f.storage.getLink(CTX, "Assigned", "a").isDeleted());
        assertEquals(Map.of("region", "North", "unit", "u"), sent.getFirst().config().get("data"));
    }

    private void midActionRevocation(Fixture f) {
        var wrapped = (StorageProvider) java.lang.reflect.Proxy.newProxyInstance(StorageProvider.class.getClassLoader(), new Class<?>[]{StorageProvider.class}, (proxy, method, args) -> {
            try {
                Object result = method.invoke(f.storage, args);
                if (!(result instanceof Transaction transaction)) return result;
                return java.lang.reflect.Proxy.newProxyInstance(Transaction.class.getClassLoader(), new Class<?>[]{Transaction.class}, (ignored, operation, values) -> {
                    try {
                        Object outcome = operation.invoke(transaction, values);
                        if (operation.getName().equals("updateObject")) f.denied.add(new EntityKey("Region", "r"));
                        return outcome;
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
            } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
        var action = action(UPDATE, null);
        var app = new ApplicationService(wrapped, f.authorization(), new ActionExecutor(), SCHEMA, Map.of("Work", action), Map.of());
        assertThrows(SecurityException.class, () -> app.execute(action, CTX, PRINCIPAL, INPUT, "mid-flight"));
        assertEquals(1, f.storage.getObject(CTX, "Unit", "u").version());
        assertFalse(f.storage.getObject(CTX, "Unit", "u").properties().containsKey("note"));
    }

    private void endpointWrites(Fixture f) {
        f.deniedGrants.add("can_connect/Unit/u");
        var action = new ActionManifestParser().parse("""
                action: Connect
                version: 1
                effects:
                  - type: createObject
                    objectType: Ticket
                    properties: {name: New}
                  - type: createLink
                    linkType: TicketUnit
                    from: ticket
                    to: person.unit
                """);
        var strict = new ApplicationService(f.storage, f.authorization(), new ActionExecutor(), SCHEMA, Map.of("Connect", action), Map.of());
        var input = Map.<String, Object>of("person", "p");
        assertThrows(SecurityException.class, () -> strict.execute(action, CTX, PRINCIPAL, input, "connect"));
        assertTrue(f.storage.queryObjects(CTX, "Ticket", QueryOptions.defaults()).isEmpty());
        var ontology = new ApplicationService(f.storage, f.authorization(), new ActionExecutor(), SCHEMA, Map.of("Connect", action), Map.of(), AuthorizationMode.ONTOLOGY_TARGETS);
        assertTrue(ontology.execute(action, CTX, PRINCIPAL, input, "connect").success());
    }

    private void conditionalConsentEvidence(Fixture f) {
        var policy = f.consent();
        f.store.record(CTX, new EntityKey("Region", "r"), "PURPOSE", ConsentRecord.Decision.GRANT, "approved");
        var base = action("  - type: recordConsent\n    subject: person.unit.region\n    condition: 'true'\n", null);
        var action = new ActionManifest(base.action(), 1, false, List.of(), base.effects(), ActionManifest.RollbackPolicy.RETRY_INDEFINITELY,
                List.of(new ActionManifest.SideEffect("notify", "event", Map.of("type", "consent-recorded"), 1, Duration.ofSeconds(10))));
        var app = f.app(action, invocation -> { throw new IllegalStateException("offline"); }, AuthorizationMode.STRICT_RESOURCES, policy);
        var pending = app.execute(action, CTX, PRINCIPAL, INPUT, "conditional");
        assertEquals("PENDING", pending.status());
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.acquireWrite();
            var run = tx.getActionExecution(pending.actionId());
            var state = new LinkedHashMap<>(run.state());
            state.put("navigation", List.of());
            tx.putActionExecution(new ActionExecution(run.id(), run.actorId(), run.action(), run.version() + 1, run.status(), run.availableAt(), state), run.version());
            tx.commit();
        }
        assertThrows(IllegalStateException.class, () -> app.execute(action, CTX, PRINCIPAL, INPUT, "conditional"));
    }

    @Test
    void singleJdbcConnectionSupportsNavigationAndContinuation() {
        var pool = org.h2.jdbcx.JdbcConnectionPool.create("jdbc:h2:mem:paths_pool_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(2);
        try { multihop(fixture(pool)); }
        finally { pool.dispose(); }
    }

    @Test
    void independentJvmCrashRetainsNavigationAndCapturedDeliveryValues() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("navigation") + ";WRITE_DELAY=0";
        var data = new JdbcDataSource();
        data.setURL(url);
        fixture(data);
        var log = directory.resolve("worker.log");
        var process = new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ActionNavigationCrashWorker.class.getName(), url)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(25, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(83, process.exitValue(), java.nio.file.Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        var recovered = emptyFixture(data);
        recovered.clock.now = recovered.clock.now.plusSeconds(30);
        try (var tx = recovered.storage.beginTransaction(CTX)) {
            tx.updateObject("Region", "r", Map.of("name", "Changed after commit"), 1);
            tx.commit();
        }
        var action = withEvent(action(UPDATE, null), "RETRY_INDEFINITELY");
        var sent = new ArrayList<SideEffectHandler.Invocation>();
        var app = recovered.app(action, sent::add);
        var result = app.execute(action, CTX, PRINCIPAL, INPUT, "crash");
        assertTrue(result.success());
        assertEquals(Map.of("region", "North", "unit", "u"), sent.getFirst().config().get("data"));
        assertEquals(2, recovered.storage.getObject(CTX, "Unit", "u").version());
        recovered.denied.add(new EntityKey("Region", "r"));
        assertThrows(SecurityException.class, () -> app.execute(action, CTX, PRINCIPAL, INPUT, "crash"));
    }

    static ActionManifest action(String effects, String condition) {
        return new ActionManifestParser().parse("action: Work\nversion: 1\n" + (condition == null ? "" : "preconditions:\n  - expr: \"" + condition + "\"\n    error: not ready\n")
                + "effects:\n" + effects);
    }
    static ActionManifest withEvent(ActionManifest action, String policy) {
        return new ActionManifest(action.action(), action.version(), false, action.preconditions(), action.effects(), ActionManifest.RollbackPolicy.valueOf(policy),
                List.of(new ActionManifest.SideEffect("notify", "event", Map.of("type", "changed", "data", Map.of("region", "person.unit.region.name", "unit", "person.unit.id")), 1, Duration.ofSeconds(5))));
    }
    static Fixture fixture(boolean jdbc) {
        if (!jdbc) return fixture((javax.sql.DataSource) null);
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:action_paths_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        return fixture(data);
    }

    static Fixture emptyFixture(javax.sql.DataSource data) {
        var clock = new TestClock();
        StorageProvider storage = data == null ? new InMemoryStorageProvider(clock) : new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
        ConsentStore consent = data == null ? new InMemoryConsentStore(clock) : new JdbcConsentStore(data, DatabaseDialect.h2(), clock);
        storage.applySchema(CTX, SCHEMA);
        return new Fixture(storage, clock, consent);
    }

    static Fixture fixture(javax.sql.DataSource data) {
        var fixture = emptyFixture(data);
        var storage = fixture.storage;
        try (var tx = storage.beginTransaction(CTX)) {
            tx.createObject("Person", "p", Map.of("name", "Person"));
            tx.createObject("Unit", "u", Map.of("name", "Unit"));
            tx.createObject("Region", "r", Map.of("name", "North"));
            tx.createLink("Assigned", "a", new EntityKey("Person", "p"), new EntityKey("Unit", "u"), Map.of("note", "original"));
            tx.createLink("Located", "l", new EntityKey("Unit", "u"), new EntityKey("Region", "r"), Map.of());
            tx.commit();
        }
        return fixture;
    }
    static final class Fixture {
        final StorageProvider storage;
        final TestClock clock;
        final ConsentStore store;
        final Set<EntityKey> denied = new HashSet<>();
        final Set<String> deniedTypes = new HashSet<>();
        final Set<String> deniedGrants = new HashSet<>();
        Fixture(StorageProvider storage, TestClock clock, ConsentStore store) {
            this.storage = storage;
            this.clock = clock;
            this.store = store;
        }
        AuthorizationService authorization() {
            return new AuthorizationService((principal, relation, key) -> !denied.contains(key) && !deniedTypes.contains(key.type())
                    && !deniedGrants.contains(relation + "/" + key.type() + "/" + key.id()));
        }
        ConsentService consent() { return new ConsentService(store, authorization(), new ConsentConfiguration(Set.of("Region"), "PURPOSE")); }
        ApplicationService app(ActionManifest action, SideEffectHandler handler) {
            return app(action, handler, AuthorizationMode.STRICT_RESOURCES, null);
        }
        ApplicationService app(ActionManifest action, SideEffectHandler handler, AuthorizationMode mode, ConsentService consent) {
            return new ApplicationService(storage, authorization(),
                    new ActionExecutor().withSideEffects(handler, clock, Duration.ofSeconds(10)), SCHEMA, Map.of("Work", action), Map.of(), mode, consent);
        }
    }
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
