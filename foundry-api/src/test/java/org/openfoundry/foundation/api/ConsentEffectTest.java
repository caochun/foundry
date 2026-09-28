package org.openfoundry.foundation.api;

import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.*;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ConsentEffectTest {
    static final RequestContext CONTEXT = RequestContext.system("tenant", "operator");
    static final ActionActor ACTOR = new ActionActor("operator", Set.of("admin"));
    static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("operator", "tenant", Set.of("admin"));
    static final EntityKey SUBJECT = new EntityKey("Person", "new");
    static final String PURPOSE = "GOV_SUPERVISION";
    static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name:"consent_effect",version:"1.0.0")
            type Person @objectType { id: ID! @primary name: String! }
            type Related @linkType(from:"Person",to:"Person",cardinality:MANY_TO_MANY) { id: ID! @primary }
            type Register @actionType(permission:"can_register") {
                id: ID! @param name: String! @param consent: Boolean @param
            }
            """);
    static final String CREATE = """
              - type: createObject
                objectType: Person
                target: params.id
                properties: {name: params.name}
            """;
    static final String GRANT = """
              - type: recordConsent
                subject: person
                evidence: "approved at registration"
                condition: "params.consent != false"
            """;

    @TestFactory
    Stream<DynamicTest> transactionalConsentEffects() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "create and consent commit together and replay does not regrant", this::registration),
                test(provider, "false condition skips resolution and persists skip journal", this::condition),
                test(provider, "CEL can resolve created roots without replacing params", this::createdCondition),
                test(provider, "optional consent requires explicit missing-field semantics", this::missingCondition),
                test(provider, "failure after consent append rolls everything back", this::appendFailure),
                test(provider, "permission loss after consent append rolls back", this::revocation),
                test(provider, "unconfigured effects never silently succeed", this::unconfigured),
                test(provider, "deployment defaults bind the idempotency fingerprint", this::defaults),
                test(provider, "configured types and purposes cannot be bypassed", this::configuration),
                test(provider, "failed delivery compensates creation and consent once", this::compensateCreation),
                test(provider, "previous decision restored and repeated effects undone in reverse", this::compensateDecisions),
                test(provider, "newer revocation blocks compensation", this::newerDecision),
                test(provider, "newer opt-out blocks compensation", this::newerOptOut),
                test(provider, "object undo failure rolls back consent compensation", this::objectConflict),
                test(provider, "corrupted continuation consent journal is rejected", this::corruptJournal),
                test(provider, "transactional consent cannot use another tenant context", this::contextMismatch)));
    }

    private DynamicTest test(String provider, String name, Consumer<Fixture> verify) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> {
            DataSource data = null;
            if (provider.equals("jdbc")) {
                var source = new JdbcDataSource();
                source.setURL("jdbc:h2:mem:effect_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                data = source;
            }
            verify.accept(fixture(data));
        });
    }

    private void registration(Fixture f) {
        var action = action(CREATE + GRANT);
        var app = f.app(action, f.executor());
        var result = app.execute(action, CONTEXT, PRINCIPAL, params(true), "register");
        assertTrue(result.success());
        assertEquals(List.of(SUBJECT), result.affected());
        assertEquals("New", f.storage.getObject(CONTEXT, "Person", "new").properties().get("name"));
        assertEquals(List.of(ConsentRecord.Decision.GRANT), decisions(f));
        assertEquals("approved at registration", f.store.snapshot(CONTEXT, SUBJECT).records().getFirst().evidence());
        assertEquals(1, f.store.auditHistory(CONTEXT, SUBJECT).size());
        assertEquals(result, app.execute(action, CONTEXT, PRINCIPAL, params(true), "register"));
        f.store.record(CONTEXT, SUBJECT, PURPOSE, ConsentRecord.Decision.DENY, "withdrawn later");
        assertEquals(result, app.execute(action, CONTEXT, PRINCIPAL, params(true), "register"));
        assertEquals(List.of(ConsentRecord.Decision.GRANT, ConsentRecord.Decision.DENY), decisions(f));
        assertTrue(app.readObject(CONTEXT, PRINCIPAL, "Person", "new").consentRestricted());
    }

    private void condition(Fixture f) {
        var action = action(CREATE + GRANT.replace("subject: person", "subject: params.missing"));
        var result = f.execute(action, f.executor(), params(false), "skip");
        assertTrue(result.success());
        assertEquals(List.of(), decisions(f));
        assertEquals(result, f.execute(action, f.executor(), params(false), "skip"));
    }

    private void createdCondition(Fixture f) {
        var action = action(CREATE + GRANT.replace("params.consent != false", "!has(params.person) && person.id == params.id && person.name == params.name"));
        assertTrue(f.execute(action, f.executor(), Map.of("id", "new", "name", "New"), "created").success());
        assertEquals(List.of(ConsentRecord.Decision.GRANT), decisions(f));
    }

    private void missingCondition(Fixture f) {
        var parameters = Map.<String, Object>of("id", "new", "name", "New");
        var action = action(CREATE + GRANT);
        assertThrows(RuntimeException.class, () -> f.execute(action, f.executor(), parameters, "missing-field"));
        assertNull(f.storage.getObject(CONTEXT, "Person", "new"));
        assertEquals(List.of(), decisions(f));
        var explicit = action(CREATE + GRANT.replace("params.consent != false", "!has(params.consent) || params.consent != false"));
        assertTrue(f.execute(explicit, f.executor(), parameters, "missing-field").success());
        assertEquals(List.of(ConsentRecord.Decision.GRANT), decisions(f));
    }

    private void appendFailure(Fixture f) {
        ConsentStore wrapped = intercept(f.store, () -> { throw new IllegalStateException("injected after append"); });
        assertThrows(IllegalStateException.class, () -> f.execute(action(CREATE + GRANT), f.executor(wrapped), params(true), "failure"));
        assertNull(f.storage.getObject(CONTEXT, "Person", "new"));
        assertEquals(List.of(), decisions(f));
        assertEquals(List.of(), f.store.auditHistory(CONTEXT, SUBJECT));
        assertTrue(f.execute(action(CREATE + GRANT), f.executor(), params(true), "failure").success());
        assertEquals(1, decisions(f).size());
    }

    private void revocation(Fixture f) {
        var allowed = new AtomicBoolean(true);
        ConsentStore wrapped = intercept(f.store, () -> allowed.set(false));
        var executor = f.executor(wrapped).withAuthorization((ctx, actor, definition, parameters) -> allowed.get());
        assertThrows(SecurityException.class, () -> f.execute(action(CREATE + GRANT), executor, params(true), "revoked"));
        assertNull(f.storage.getObject(CONTEXT, "Person", "new"));
        assertEquals(List.of(), decisions(f));
        assertEquals(List.of(), f.store.auditHistory(CONTEXT, SUBJECT));
    }

    private void unconfigured(Fixture f) {
        var executor = new ActionExecutor().withAuthorization((ctx, actor, definition, parameters) -> true);
        assertThrows(IllegalStateException.class, () -> f.execute(action(CREATE + GRANT), executor, params(true), "missing"));
        assertNull(f.storage.getObject(CONTEXT, "Person", "new"));
    }

    private void defaults(Fixture f) {
        var action = action(CREATE + GRANT);
        f.execute(action, f.executor(), params(true), "defaults");
        var changed = f.executor().withConsentStore(f.store, "RESEARCH", Set.of("Person"), Set.of(PURPOSE, "RESEARCH"));
        assertThrows(IllegalArgumentException.class, () -> f.execute(action, changed, params(true), "defaults"));
        assertEquals(1, decisions(f).size());
    }

    private void configuration(Fixture f) {
        for (String extra : List.of("    purpose: UNKNOWN\n", "    subjectType: Unknown\n")) {
            assertThrows(IllegalArgumentException.class, () -> f.execute(action(CREATE + GRANT + extra), f.executor(), params(true), extra));
            assertNull(f.storage.getObject(CONTEXT, "Person", "new"));
        }
        var ambiguous = f.executor().withConsentStore(f.store, PURPOSE, Set.of("Person", "Unit"), Set.of(PURPOSE));
        assertThrows(IllegalArgumentException.class, () -> f.execute(action(GRANT.replace("subject: person", "subject: params.id")), ambiguous, params(true), "ambiguous"));
        var mismatch = action(CREATE + GRANT + "    subjectType: Unit\n");
        assertThrows(IllegalArgumentException.class, () -> f.execute(mismatch, ambiguous, params(true), "mismatch"));
        assertNull(f.storage.getObject(CONTEXT, "Person", "new"));
    }

    private void compensateCreation(Fixture f) {
        var action = continued(CREATE + GRANT);
        var app = f.app(action, failing(f));
        var result = app.execute(action, CONTEXT, PRINCIPAL, params(true), "undo");
        assertEquals("ROLLED_BACK", result.status());
        assertTrue(f.storage.getObject(CONTEXT, "Person", "new").isDeleted());
        assertEquals(List.of(ConsentRecord.Decision.GRANT, ConsentRecord.Decision.DENY), decisions(f));
        assertTrue(f.store.snapshot(CONTEXT, SUBJECT).records().getLast().evidence().contains("previous explicit decision absent"));
        assertEquals(result, app.execute(action, CONTEXT, PRINCIPAL, params(true), "undo"));
        assertEquals(2, decisions(f).size());
    }

    private void compensateDecisions(Fixture f) {
        f.store.record(CONTEXT, SUBJECT, PURPOSE, ConsentRecord.Decision.GRANT, "existing");
        String deny = GRANT.replace("subject: person", "subject: params.id") + "    decision: DENY\n";
        String grant = GRANT.replace("subject: person", "subject: params.id");
        var result = f.execute(continued(deny + grant), failing(f), params(true), "reverse");
        assertEquals("ROLLED_BACK", result.status());
        assertEquals(List.of(ConsentRecord.Decision.GRANT, ConsentRecord.Decision.DENY, ConsentRecord.Decision.GRANT,
                ConsentRecord.Decision.DENY, ConsentRecord.Decision.GRANT), decisions(f));
    }

    private void newerDecision(Fixture f) {
        var executor = f.executor().withSideEffects(invocation -> {
            f.store.record(CONTEXT, SUBJECT, PURPOSE, ConsentRecord.Decision.DENY, "external withdrawal");
            throw new IllegalStateException("failed delivery");
        });
        var result = f.execute(continued(CREATE + GRANT), executor, params(true), "newer");
        assertEquals("COMPENSATION_FAILED", result.status());
        assertFalse(f.storage.getObject(CONTEXT, "Person", "new").isDeleted());
        assertEquals(List.of(ConsentRecord.Decision.GRANT, ConsentRecord.Decision.DENY), decisions(f));
    }

    private void newerOptOut(Fixture f) {
        var executor = f.executor().withSideEffects(invocation -> {
            f.store.setOptOut(CONTEXT, SUBJECT, true, "external opt-out");
            throw new IllegalStateException("failed delivery");
        });
        assertEquals("COMPENSATION_FAILED", f.execute(continued(CREATE + GRANT), executor, params(true), "optout").status());
        assertTrue(f.store.snapshot(CONTEXT, SUBJECT).optedOut());
        assertEquals(1, decisions(f).size());
    }

    private void objectConflict(Fixture f) {
        var executor = f.executor().withSideEffects(invocation -> {
            try (var tx = f.storage.beginTransaction(CONTEXT)) {
                tx.createObject("Person", "other", Map.of("name", "Other"));
                tx.createLink("Related", "dependency", SUBJECT, new EntityKey("Person", "other"), Map.of());
                tx.commit();
            }
            throw new IllegalStateException("failed delivery");
        });
        assertEquals("COMPENSATION_FAILED", f.execute(continued(CREATE + GRANT), executor, params(true), "dependency").status());
        assertEquals(List.of(ConsentRecord.Decision.GRANT), decisions(f));
        assertEquals(1, f.store.auditHistory(CONTEXT, SUBJECT).size());
        assertFalse(f.storage.getObject(CONTEXT, "Person", "new").isDeleted());
    }

    private void corruptJournal(Fixture f) {
        var action = new ActionManifestParser().parse("action: Register\nversion: 1\neffects:\n" + CREATE + GRANT + """
                sideEffects:
                  - name: notify
                    type: event
                    config: {type: created}
                    retries: 1
                    retryDelay: PT1H
                rollback: {onSideEffectFailure: RETRY_INDEFINITELY}
                """);
        var result = f.execute(action, failing(f), params(true), "corrupt");
        assertEquals("PENDING", result.status());
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.acquireWrite();
            var run = tx.getActionExecution(result.actionId());
            var state = new LinkedHashMap<>(run.state());
            state.remove("consent");
            tx.putActionExecution(new ActionExecution(run.id(), run.actorId(), run.action(), run.version() + 1,
                    run.status(), run.availableAt(), state), run.version());
            tx.commit();
        }
        assertThrows(IllegalStateException.class, () -> f.execute(action, failing(f), params(true), "corrupt"));
        assertEquals(1, decisions(f).size());
    }

    private void contextMismatch(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(SecurityException.class, () -> f.store.record(RequestContext.system("other", "operator"), SUBJECT,
                    PURPOSE, ConsentRecord.Decision.GRANT, "forged context", tx));
            assertThrows(SecurityException.class, () -> f.store.snapshot(RequestContext.system("other", "operator"), SUBJECT, tx));
        }
        assertEquals(List.of(), decisions(f));
    }

    @Test
    void singleConnectionPool() {
        var pool = JdbcConnectionPool.create("jdbc:h2:mem:effect_pool_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(2);
        try { registration(fixture(pool)); }
        finally { pool.dispose(); }
    }

    @Test
    void memoryConflictDoesNotPublishConsentOrLeakLock() {
        var f = fixture(null);
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            f.store.record(CONTEXT, SUBJECT, PURPOSE, ConsentRecord.Decision.GRANT, "staged", tx);
            try (var competing = f.storage.beginTransaction(CONTEXT)) {
                competing.createObject("Person", "other", Map.of("name", "Other"));
                competing.commit();
            }
            assertThrows(TransactionConflictException.class, tx::commit);
        }
        assertEquals(List.of(), decisions(f));
        f.store.record(CONTEXT, SUBJECT, PURPOSE, ConsentRecord.Decision.DENY, "after rollback");
        assertEquals(List.of(ConsentRecord.Decision.DENY), decisions(f));
    }

    @Test
    void legacyEvaluatorCannotSilentlyMergeCreatedObjectsIntoParameters() {
        assertThrows(UnsupportedOperationException.class, () -> ExpressionEvaluator.simple().evaluateBindings(
                "true", Map.of("id", "input"), Map.of("person", SUBJECT), ACTOR, Instant.now()));
    }

    @Test
    void failedParticipantPublicationRestoresConsent() {
        var f = fixture(null);
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.createObject("Person", "new", Map.of("name", "New"));
            f.store.record(CONTEXT, SUBJECT, PURPOSE, ConsentRecord.Decision.GRANT, "staged", tx);
            tx.enlist("fail-after-consent", () -> new TransactionResource() {
                @Override public void prepare() {}
                @Override public void publish() { throw new IllegalStateException("publication failure"); }
                @Override public void rollback() {}
                @Override public void close() {}
            });
            assertThrows(IllegalStateException.class, tx::commit);
        }
        assertNull(f.storage.getObject(CONTEXT, "Person", "new"));
        assertEquals(List.of(), decisions(f));
        assertEquals(List.of(), f.store.auditHistory(CONTEXT, SUBJECT));
        f.store.record(CONTEXT, SUBJECT, PURPOSE, ConsentRecord.Decision.DENY, "after rollback");
        assertEquals(List.of(ConsentRecord.Decision.DENY), decisions(f));
    }

    @Test
    void parserAcceptsUpstreamShapeAndRejectsInvalidDeclarations() {
        var effect = (ActionManifest.RecordConsent) action(GRANT).effects().getFirst();
        assertEquals(ConsentRecord.Decision.GRANT, effect.decision());
        assertNull(effect.purpose());
        var empty = (ActionManifest.RecordConsent) action("  - type: recordConsent\n    subject: params.id\n    evidence: \"\"\n").effects().getFirst();
        assertEquals("", empty.evidence());
        for (String extra : List.of("    decision: UNKNOWN\n", "    unexpected: value\n", "    subject: 123\n")) {
            assertThrows(ActionParseException.class, () -> action(GRANT + extra));
        }
    }

    private static ActionExecutor failing(Fixture f) {
        return f.executor().withSideEffects(invocation -> { throw new IllegalStateException("failed delivery"); });
    }
    private static ActionManifest action(String effects) {
        return new ActionManifestParser().parse("action: Register\nversion: 1\neffects:\n" + effects);
    }
    private static ActionManifest continued(String effects) {
        return new ActionManifestParser().parse("action: Register\nversion: 1\neffects:\n" + effects + """
                sideEffects:
                  - name: notify
                    type: event
                    config: {type: created}
                    retries: 1
                    retryDelay: PT0S
                rollback: {onSideEffectFailure: ROLLBACK_ALL}
                """);
    }
    private static Map<String, Object> params(boolean consent) { return Map.of("id", "new", "name", "New", "consent", consent); }
    private static List<ConsentRecord.Decision> decisions(Fixture f) {
        return f.store.snapshot(CONTEXT, SUBJECT).records().stream().map(ConsentRecord::decision).toList();
    }
    private static ConsentStore intercept(ConsentStore store, Runnable afterAppend) {
        return (ConsentStore) Proxy.newProxyInstance(ConsentStore.class.getClassLoader(), new Class<?>[]{ConsentStore.class}, (proxy, method, args) -> {
            try {
                Object result = method.invoke(store, args);
                if (method.getName().equals("record") && args.length == 6) afterAppend.run();
                return result;
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
    private static Fixture fixture(DataSource data) {
        StorageProvider storage = data == null ? new InMemoryStorageProvider() : new JdbcStorageProvider(data, DatabaseDialect.h2());
        ConsentStore store = data == null ? new InMemoryConsentStore() : new JdbcConsentStore(data, DatabaseDialect.h2());
        storage.applySchema(CONTEXT, SCHEMA);
        store.initialize();
        return new Fixture(storage, store);
    }
    record Fixture(StorageProvider storage, ConsentStore store) {
        ActionExecutor executor() { return executor(store); }
        ActionExecutor executor(ConsentStore configured) {
            return new ActionExecutor().withParameterSchema(SCHEMA)
                    .withAuthorization((ctx, actor, definition, parameters) -> true)
                    .withConsentStore(configured, PURPOSE, Set.of("Person"), Set.of(PURPOSE, "RESEARCH"));
        }
        ActionResult execute(ActionManifest action, ActionExecutor executor, Map<String, Object> params, String key) {
            return executor.execute(action, SCHEMA.actionTypes().getFirst(), CONTEXT, ACTOR, params, key, storage);
        }
        ApplicationService app(ActionManifest action, ActionExecutor executor) {
            var authorization = new AuthorizationService((principal, relation, resource) -> true);
            var consent = new ConsentService(store, authorization, new ConsentConfiguration(Set.of("Person"), PURPOSE));
            return new ApplicationService(storage, authorization, executor, SCHEMA, Map.of("Register", action), Map.of(), AuthorizationMode.STRICT_RESOURCES, consent);
        }
    }
}
