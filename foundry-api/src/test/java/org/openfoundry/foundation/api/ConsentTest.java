package org.openfoundry.foundation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import org.h2.jdbcx.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.schema.*;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.*;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ConsentTest {
    @TempDir Path directory;
    static final RequestContext READER_CTX = new RequestContext("tenant", "reader", "read-trace");
    static final RequestContext ADMIN_CTX = new RequestContext("tenant", "admin", "admin-trace");
    static final SecurityPrincipal READER = new SecurityPrincipal("reader", "tenant", Set.of());
    static final SecurityPrincipal ADMIN = new SecurityPrincipal("admin", "tenant", Set.of("admin"));
    static final EntityKey A = new EntityKey("Person", "a");
    static final EntityKey B = new EntityKey("Person", "b");
    static final EntityKey C = new EntityKey("Person", "c");
    static final EntityKey UNIT = new EntityKey("Unit", "u");
    static final String PURPOSE = "GOV_SUPERVISION";
    static final String ODL = """
            extend schema @namespace(name:"consent",version:"1.0.0")
            interface Identifiable { id: ID! @primary }
            type Person implements Identifiable @objectType { name: String! amount: Int! secret: String @sensitive }
            type Unit @objectType {
                id: ID! @primary name: String!
                people: [Person!] @link(type:"WorksIn",direction:INBOUND)
                size: Int @computed(fn:"countLinks",args:{type:"WorksIn",direction:INBOUND})
            }
            type WorksIn @linkType(from:"Person",to:"Unit",cardinality:MANY_TO_MANY) { id: ID! @primary note: String }
            type Rename @actionType(permission:"can_rename") { person: Person! @param name: String! @param }
            """;
    static final OntologySchema SCHEMA = new OdlParser().parse(ODL);
    static final ActionManifest RENAME = new ActionManifest("Rename", 1, false, List.of(),
            List.of(new ActionManifest.UpdateObject("person", Map.of("name", "params.name"))));

    @TestFactory
    Stream<DynamicTest> consentAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "latest insertion and open purpose vocabulary", this::policy),
                test(provider, "configured relationship exemption and opt-out precedence", this::exemption),
                test(provider, "denied singles contain identity only", this::single),
                test(provider, "consent exclusion precedes all counts sorting and pagination", this::queries),
                test(provider, "history relationship navigation and computed counts obey consent", this::relationships),
                test(provider, "action execution replay and denial audit", this::actions),
                test(provider, "consent is rechecked inside the object transaction", this::transactionCheck),
                test(provider, "collections and indirect relationship subjects cannot bypass consent", this::otherActionTargets),
                test(provider, "recorder roles tenant and subject identity are enforced", this::identity),
                test(provider, "REST and GraphQL record revoke and opt-out", this::apis),
                test(provider, "concurrent writes preserve ordered evidence", this::concurrency)));
    }

    private DynamicTest test(String provider, String name, Check check) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> check.run(fixture(provider.equals("jdbc") ? data("jdbc:h2:mem:consent_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1") : null)));
    }

    private void policy(Fixture f) {
        assertFalse(f.service.check(READER_CTX, READER, A, PURPOSE).allowed());
        f.grant(A);
        assertTrue(f.service.check(READER_CTX, READER, A, PURPOSE).allowed());
        assertEquals(ConsentDecision.Basis.EXPLICIT_CONSENT, f.service.check(READER_CTX, READER, A, PURPOSE).basis());
        f.clock.now = f.clock.now.minusSeconds(60);
        f.service.revoke(ADMIN_CTX, ADMIN, A, PURPOSE, "withdrawn");
        var records = f.store.snapshot(READER_CTX, A).records();
        assertEquals(List.of(1L, 2L), records.stream().map(ConsentRecord::sequence).toList());
        assertTrue(records.getLast().recordedAt().isBefore(records.getFirst().recordedAt()));
        assertFalse(f.service.check(READER_CTX, READER, A, PURPOSE).allowed());
        f.service.record(ADMIN_CTX, ADMIN, A, "RESEARCH", ConsentRecord.Decision.GRANT, "different purpose");
        assertTrue(f.service.check(READER_CTX, READER, A, "RESEARCH").allowed());
        assertFalse(f.service.check(READER_CTX, READER, A, PURPOSE).allowed());
        assertEquals(2, f.service.checkBatch(READER_CTX, READER, List.of(A, B, A), PURPOSE).size());
        assertThrows(UnsupportedOperationException.class, () -> f.store.snapshot(READER_CTX, A).records().clear());
    }

    private void exemption(Fixture f) {
        var exempt = new ConsentService(f.store, f.authorization, new ConsentConfiguration(Set.of("Person"), PURPOSE, Set.of(PURPOSE, "RESEARCH"), Set.of("admin"),
                new ConsentConfiguration.Exemption(PURPOSE, "caregiver")));
        assertFalse(exempt.check(READER_CTX, READER, A, PURPOSE).allowed());
        f.relationship.set(true);
        assertEquals(ConsentDecision.Basis.LEGITIMATE_INTEREST, exempt.check(READER_CTX, READER, A, PURPOSE).basis());
        exempt.record(ADMIN_CTX, ADMIN, A, PURPOSE, ConsentRecord.Decision.DENY, "explicit deny");
        assertTrue(exempt.check(READER_CTX, READER, A, PURPOSE).allowed(), "Configured exemption precedes explicit consent per upstream");
        exempt.setOptOut(ADMIN_CTX, ADMIN, A, true, "disable exemption");
        assertFalse(exempt.check(READER_CTX, READER, A, PURPOSE).allowed());
        exempt.record(ADMIN_CTX, ADMIN, A, PURPOSE, ConsentRecord.Decision.GRANT, "explicit grant despite exemption opt-out");
        var explicit = exempt.check(READER_CTX, READER, A, PURPOSE);
        assertTrue(explicit.allowed());
        assertEquals(ConsentDecision.Basis.EXPLICIT_CONSENT, explicit.basis());
        assertFalse(exempt.check(READER_CTX, READER, A, "RESEARCH").allowed());
        f.relationship.set(false);
        assertTrue(exempt.check(READER_CTX, READER, A, PURPOSE).allowed());
    }

    private void single(Fixture f) {
        var restricted = f.app.readObject(READER_CTX, READER, "Person", "a");
        assertTrue(restricted.consentRestricted());
        assertNull(restricted.object());
        assertEquals(A, restricted.key());
        assertNull(f.app.getObject(READER_CTX, READER, "Person", "a"));
        var rest = new RestApiRouter(f.app).get(READER_CTX, READER, "/api/v1/Person/a", QueryOptions.defaults());
        assertEquals(Map.of("id", "a", "_consentRestricted", true), rest.body());
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app, Map.of("Rename", RENAME));
        var result = graph.execute(input("{person(id:\"a\"){... on Identifiable{id _consentRestricted} name amount secret}}", READER_CTX, READER));
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        var person = (Map<?, ?>) ((Map<?, ?>) result.getData()).get("person");
        assertEquals(true, person.get("_consentRestricted"));
        assertEquals("a", person.get("id"));
        assertNull(person.get("name")); assertNull(person.get("amount")); assertNull(person.get("secret"));
        f.grant(A);
        assertEquals("A", f.app.getObject(READER_CTX, READER, "Person", "a").properties().get("name"));
        assertFalse(f.app.getObject(READER_CTX, READER, "Person", "a").properties().containsKey("secret"));
        f.denied.add(A);
        assertNull(f.app.readObject(READER_CTX, READER, "Person", "a"), "Consent cannot bypass ordinary object permission");
    }

    private void queries(Fixture f) {
        try (var tx = f.storage.beginTransaction(READER_CTX)) {
            for (int i = 0; i < 130; i++) tx.createObject("Person", "0-denied-" + i, Map.of("name", "Denied", "amount", 1000));
            tx.commit();
        }
        f.grant(B);
        var query = new ObjectConnectionQuery(Map.of("amount", Map.of("gte", 0)), Map.of("amount", "DESC"), new ConnectionPage(1, null, null, null, 0));
        var page = f.app.queryConnection(READER_CTX, READER, "Person", query);
        assertEquals(1, page.totalCount());
        assertEquals("b", page.edges().getFirst().node().id());
        assertFalse(page.pageInfo().hasNextPage());
        assertEquals(1, f.app.listObjects(READER_CTX, READER, "Person", QueryOptions.defaults()).size());
        var aggregate = new AggregateQuery(List.of(new AggregateQuery.Field("amount", AggregateQuery.Function.SUM)), List.of(), Map.of(), List.of());
        assertEquals(2.0, f.app.aggregateObjects(READER_CTX, READER, "Person", aggregate).groups().getFirst().values().get("sum_amount"));
        assertEquals(0, f.app.searchObjects(READER_CTX, READER, "Person", new SearchQuery("Denied", null, Map.of())).totalCount());
        assertEquals(1, f.app.searchObjects(READER_CTX, READER, "Person", new SearchQuery("B", null, Map.of())).totalCount());
        var sets = new ObjectSetService(f.app, new InMemoryObjectSetStore());
        var saved = sets.create(READER_CTX, READER, Map.of("name", "All subjects", "objectType", "Person", "isPublic", true));
        assertEquals(1, sets.execute(READER_CTX, READER, saved.id(), 20, 0).totalCount());
        f.service.revoke(ADMIN_CTX, ADMIN, B, PURPOSE, "revoked");
        assertEquals(0, sets.execute(READER_CTX, READER, saved.id(), 20, 0).totalCount());
        assertNull(f.app.aggregateObjects(READER_CTX, READER, "Person", aggregate).groups().getFirst().values().get("sum_amount"));
    }

    private void relationships(Fixture f) {
        assertEquals(0, f.app.readComputedField(READER_CTX, READER, UNIT, "size"));
        assertEquals(List.of(), f.app.readLinkField(READER_CTX, READER, UNIT, "people", QueryOptions.defaults()));
        assertTrue(f.app.history(READER_CTX, READER, A).isEmpty());
        assertTrue(f.app.history(READER_CTX, READER, new EntityKey("WorksIn", "edge-a")).isEmpty());
        f.grant(B);
        assertEquals(1, f.app.readComputedField(READER_CTX, READER, UNIT, "size"));
        var people = (List<?>) f.app.readLinkField(READER_CTX, READER, UNIT, "people", QueryOptions.defaults());
        assertEquals("b", ((ObjectRecord) people.getFirst()).id());
        assertEquals(1, f.app.history(READER_CTX, READER, B).size());
        assertEquals(1, f.app.history(READER_CTX, READER, new EntityKey("WorksIn", "edge-b")).size());
        var past = new QueryOptions(100, 0, f.clock.instant(), f.clock.instant(), false);
        assertEquals(1, f.app.listObjects(READER_CTX, READER, "Person", past).size());
        f.service.revoke(ADMIN_CTX, ADMIN, B, PURPOSE, "also restrict past data");
        assertTrue(f.app.listObjects(READER_CTX, READER, "Person", past).isEmpty());
    }

    private void actions(Fixture f) {
        var input = Map.<String, Object>of("person", "b", "name", "Changed");
        assertThrows(ConsentDeniedException.class, () -> f.app.execute(RENAME, READER_CTX, READER, input, "same"));
        assertEquals(1, f.storage.getObject(READER_CTX, "Person", "b").version());
        assertEquals(1, f.store.auditHistory(ADMIN_CTX, B).stream().filter(audit -> audit.operation().equals("ACTION") && audit.outcome().equals("DENIED")).count());
        f.grant(B);
        var accepted = f.app.execute(RENAME, READER_CTX, READER, input, "same");
        assertTrue(accepted.success());
        assertEquals(accepted, f.app.execute(RENAME, READER_CTX, READER, input, "same"));
        f.service.revoke(ADMIN_CTX, ADMIN, B, PURPOSE, "revoke replay access");
        assertThrows(ConsentDeniedException.class, () -> f.app.execute(RENAME, READER_CTX, READER, input, "same"));
        assertEquals(2, f.storage.getObject(READER_CTX, "Person", "b").version());
    }

    private void transactionCheck(Fixture f) {
        f.grant(B);
        var changed = new AtomicBoolean();
        ConsentStore wrapped = (ConsentStore) Proxy.newProxyInstance(ConsentStore.class.getClassLoader(), new Class<?>[]{ConsentStore.class}, (proxy, method, args) -> {
            try {
                Object result = method.invoke(f.store, args);
                if (method.getName().equals("snapshot") && args.length == 2 && changed.compareAndSet(false, true)) {
                    f.store.record(ADMIN_CTX, B, PURPOSE, ConsentRecord.Decision.DENY, "between preparation and transaction");
                }
                return result;
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        var policy = new ConsentService(wrapped, f.authorization, config());
        var app = app(f.storage, f.authorization, policy);
        assertThrows(ConsentDeniedException.class, () -> app.execute(RENAME, READER_CTX, READER, Map.of("person", "b", "name", "No commit"), "race"));
        assertEquals("B", f.storage.getObject(READER_CTX, "Person", "b").properties().get("name"));
        assertEquals(1, f.store.auditHistory(ADMIN_CTX, B).stream().filter(audit -> audit.operation().equals("ACTION")).count());
    }

    private void otherActionTargets(Fixture f) {
        var schema = new OdlParser().parse(ODL + """
                type Batch @actionType(permission:"can_batch") { people: [Person!]! @param }
                type Drop @actionType(permission:"can_remove") { edge: ID! @param }
                type Register @actionType(permission:"can_create") { name: String! @param amount: Int! @param }
                """);
        if (f.storage instanceof JdbcStorageProvider jdbc) jdbc.activateSchema(READER_CTX, schema, null, jdbc.boundSchemaVersion());
        else f.storage.applySchema(READER_CTX, schema);
        var batch = new ActionManifest("Batch", 1, false, List.of(), List.of());
        var drop = new ActionManifest("Drop", 1, false, List.of(), List.of(new ActionManifest.DeleteLink("WorksIn", "params.edge")));
        var register = new ActionManifest("Register", 1, false, List.of(), List.of(new ActionManifest.CreateObject("Person", "created", Map.of("name", "params.name", "amount", "params.amount"))));
        var app = new ApplicationService(f.storage, f.authorization, new ActionExecutor(), schema, Map.of("Batch", batch, "Drop", drop, "Register", register), Map.of(), AuthorizationMode.STRICT_RESOURCES, f.service);
        f.grant(A);
        assertThrows(ConsentDeniedException.class, () -> app.execute(batch, READER_CTX, READER, Map.of("people", List.of("a", "b")), "batch"));
        assertThrows(ConsentDeniedException.class, () -> app.execute(drop, READER_CTX, READER, Map.of("edge", "edge-b"), "drop"));
        assertFalse(f.storage.getLink(READER_CTX, "WorksIn", "edge-b").isDeleted());
        f.grant(B);
        assertTrue(app.execute(batch, READER_CTX, READER, Map.of("people", List.of("a", "b")), "batch").success());
        assertTrue(app.execute(drop, READER_CTX, READER, Map.of("edge", "edge-b"), "drop").success());
        f.service.revoke(ADMIN_CTX, ADMIN, B, PURPOSE, "revoke indirect subject");
        assertThrows(ConsentDeniedException.class, () -> app.execute(drop, READER_CTX, READER, Map.of("edge", "edge-b"), "drop"));
        var created = app.execute(register, READER_CTX, READER, Map.of("name", "New subject", "amount", 1), "register");
        assertTrue(created.success());
        assertTrue(app.readObject(READER_CTX, READER, "Person", created.affected().getFirst().id()).consentRestricted());
        assertEquals(created.actionId(), app.execute(register, READER_CTX, READER, Map.of("name", "New subject", "amount", 1), "register").actionId());
    }

    private void identity(Fixture f) {
        assertThrows(SecurityException.class, () -> f.service.record(READER_CTX, READER, A, PURPOSE, ConsentRecord.Decision.GRANT, "forged recorder"));
        assertTrue(f.store.snapshot(READER_CTX, A).records().isEmpty());
        assertEquals("DENIED", f.store.auditHistory(ADMIN_CTX, A).getFirst().outcome());
        assertThrows(SecurityException.class, () -> f.service.record(READER_CTX, ADMIN, A, PURPOSE, ConsentRecord.Decision.GRANT, null));
        f.grant(A);
        var other = RequestContext.system("other", "reader");
        assertFalse(f.service.check(other, new SecurityPrincipal("reader", "other", Set.of()), A, PURPOSE).allowed());
        var multi = new ConsentService(f.store, f.authorization, new ConsentConfiguration(Set.of("Person", "Unit"), PURPOSE));
        assertTrue(multi.check(READER_CTX, READER, A, PURPOSE).allowed());
        assertFalse(multi.check(READER_CTX, READER, new EntityKey("Unit", "a"), PURPOSE).allowed());
        assertThrows(IllegalArgumentException.class, () -> f.service.record(ADMIN_CTX, ADMIN, A, "UNCONFIGURED", ConsentRecord.Decision.GRANT, null));
        assertThrows(SecurityException.class, () -> f.service.records(READER_CTX, READER, A));
    }

    private void apis(Fixture f) throws Exception {
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app, Map.of("Rename", RENAME));
        var denied = graph.execute(input("mutation{recordConsent(input:{subject:\"a\"}){recorded}}", READER_CTX, READER));
        assertEquals("FORBIDDEN", denied.getErrors().getFirst().getExtensions().get("code"));
        var granted = graph.execute(input("mutation{recordConsent(input:{subject:\"a\",evidence:\"approved\"}){subject purpose decision recorded}}", ADMIN_CTX, ADMIN));
        assertTrue(granted.getErrors().isEmpty(), granted.getErrors().toString());
        assertTrue(f.service.check(READER_CTX, READER, A, PURPOSE).allowed());
        var revoked = graph.execute(input("mutation{revokeConsent(input:{subject:\"a\",reason:\"withdrawn\"}){decision liveInvalidationSupported}}", ADMIN_CTX, ADMIN));
        assertTrue(revoked.getErrors().isEmpty(), revoked.getErrors().toString());
        assertEquals(Map.of("revokeConsent", Map.of("decision", "DENY", "liveInvalidationSupported", false)), revoked.getData());
        var action = graph.execute(input("mutation{rename(input:{person:\"a\",name:\"Denied\"}){success}}", READER_CTX, READER));
        assertEquals("CONSENT_DENIED", action.getErrors().getFirst().getExtensions().get("code"));
        var identity = new java.util.concurrent.atomic.AtomicReference<>(new ApiRequestContext(ADMIN_CTX, ADMIN));
        try (var server = new JdkRestServer(0, new RestApiRouter(f.app), identity::get, Map.of("Rename", RENAME)); var client = HttpClient.newHttpClient()) {
            server.start();
            assertEquals(200, send(client, server, "POST", "/api/v1/consent", "{\"subject\":\"b\",\"decision\":\"GRANT\"}").statusCode());
            assertEquals(200, send(client, server, "POST", "/api/v1/consent/opt-out", "{\"subject\":\"b\",\"optedOut\":true,\"reason\":\"stop exemption\"}").statusCode());
            assertEquals(200, send(client, server, "GET", "/api/v1/consent?subject=b", null).statusCode());
            assertEquals(400, send(client, server, "POST", "/api/v1/consent", "{\"subject\":\"b\",\"tenantId\":\"other\"}").statusCode());
            identity.set(new ApiRequestContext(READER_CTX, READER));
            assertEquals(403, send(client, server, "GET", "/api/v1/consent/audit?subject=b", null).statusCode());
            var restricted = send(client, server, "GET", "/api/v1/Person/a", null);
            assertEquals(200, restricted.statusCode());
            assertEquals(Set.of("id", "_consentRestricted"), new ObjectMapper().readValue(restricted.body(), Map.class).keySet());
            var rejected = send(client, server, "POST", "/api/v1/actions/Rename", "{\"person\":\"a\",\"name\":\"Denied\"}");
            assertEquals(403, rejected.statusCode());
            assertTrue(rejected.body().contains("CONSENT_DENIED"));
        }
    }

    private void concurrency(Fixture f) throws Exception {
        try (var pool = Executors.newFixedThreadPool(8)) {
            var barrier = new CountDownLatch(1);
            var writes = new ArrayList<Future<ConsentRecord>>();
            for (int index = 0; index < 8; index++) {
                var decision = index % 2 == 0 ? ConsentRecord.Decision.GRANT : ConsentRecord.Decision.DENY;
                writes.add(pool.submit(() -> { barrier.await(); return f.reopen.get().record(ADMIN_CTX, A, PURPOSE, decision, "parallel"); }));
            }
            barrier.countDown();
            for (var write : writes) write.get(15, TimeUnit.SECONDS);
        }
        var snapshot = f.store.snapshot(READER_CTX, A);
        assertEquals(8, snapshot.revision());
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), snapshot.records().stream().map(ConsentRecord::sequence).toList());
        assertEquals(snapshot.records().getLast().decision() == ConsentRecord.Decision.GRANT, f.service.check(READER_CTX, READER, A, PURPOSE).allowed());
        assertEquals(8, f.store.auditHistory(ADMIN_CTX, A).size());
    }

    @Test
    void singleConnectionPoolChecksConsentInsideActionsWithoutBorrowingAnotherConnection() {
        var pool = JdbcConnectionPool.create("jdbc:h2:mem:consent_one_connection;DB_CLOSE_DELAY=-1", "sa", "");
        pool.setMaxConnections(1); pool.setLoginTimeout(2);
        try {
            var f = fixture(pool);
            f.grant(B);
            assertTrue(f.app.execute(RENAME, READER_CTX, READER, Map.of("person", "b", "name", "Updated"), "once").success());
            assertEquals("Updated", f.app.getObject(READER_CTX, READER, "Person", "b").properties().get("name"));
        } finally { pool.dispose(); }
    }

    @Test
    void fileRecoveryPreservesConsentOptOutAndAuditAndAuditFailureRollsBackTheDecision() throws Exception {
        var data = data("jdbc:h2:file:" + directory.resolve("consent"));
        var store = new JdbcConsentStore(data, DatabaseDialect.h2());
        store.record(ADMIN_CTX, A, PURPOSE, ConsentRecord.Decision.GRANT, "evidence");
        store.setOptOut(ADMIN_CTX, A, true, "recorded override");
        var reopened = new JdbcConsentStore(data, DatabaseDialect.h2());
        assertTrue(reopened.snapshot(READER_CTX, A).optedOut());
        assertEquals("evidence", reopened.snapshot(READER_CTX, A).records().getFirst().evidence());
        assertEquals(2, reopened.auditHistory(ADMIN_CTX, A).size());
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER fail_consent_audit BEFORE INSERT ON of_consent_audit FOR EACH ROW CALL '" + RejectAudit.class.getName() + "'");
        }
        assertThrows(IllegalStateException.class, () -> reopened.record(ADMIN_CTX, A, PURPOSE, ConsentRecord.Decision.DENY, "must roll back"));
        assertEquals(2, reopened.snapshot(READER_CTX, A).revision());
        assertEquals(1, reopened.snapshot(READER_CTX, A).records().size());
        assertEquals(2, reopened.auditHistory(ADMIN_CTX, A).size());
    }

    @Test
    void invalidConfigurationIsRejectedAndNoConsentRemainsTheDefault() {
        assertThrows(IllegalArgumentException.class, () -> new ConsentConfiguration(Set.of("Person"), PURPOSE, Set.of("RESEARCH"), Set.of("admin"), null));
        var storage = new InMemoryStorageProvider(); storage.applySchema(READER_CTX, SCHEMA);
        var auth = new AuthorizationService((p, r, k) -> true);
        var unknown = new ConsentService(new InMemoryConsentStore(), auth, new ConsentConfiguration(Set.of("Unknown"), PURPOSE));
        assertThrows(IllegalArgumentException.class, () -> app(storage, auth, unknown));
        var disabled = new ApplicationService(storage, auth, new ActionExecutor(), SCHEMA, Map.of(), Map.of());
        var missing = GraphqlApiRuntime.create(SCHEMA, disabled).execute(input("mutation{recordConsent(input:{subject:\"a\"}){recorded}}", ADMIN_CTX, ADMIN));
        assertEquals("CONSENT_NOT_CONFIGURED", missing.getErrors().getFirst().getExtensions().get("code"));
        assertTrue(DataPurposes.STANDARD.contains(DataPurposes.DIRECT_CARE));
    }

    private static Fixture fixture(DataSource data) {
        var clock = new MutableClock();
        StorageProvider storage = data == null ? new InMemoryStorageProvider(clock) : new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
        ConsentStore store = data == null ? new InMemoryConsentStore(clock) : new JdbcConsentStore(data, DatabaseDialect.h2(), clock);
        storage.applySchema(READER_CTX, SCHEMA);
        try (var tx = storage.beginTransaction(READER_CTX)) {
            tx.createObject("Unit", "u", Map.of("name", "Unit"));
            for (int index = 0; index < 3; index++) {
                String id = List.of("a", "b", "c").get(index);
                tx.createObject("Person", id, Map.of("name", id.toUpperCase(Locale.ROOT), "amount", index + 1, "secret", "private"));
                tx.createLink("WorksIn", "edge-" + id, new EntityKey("Person", id), UNIT, Map.of("note", "relationship"));
            }
            tx.commit();
        }
        return new Fixture(storage, store, data == null ? () -> store : () -> new JdbcConsentStore(data, DatabaseDialect.h2(), clock), clock);
    }
    private static ConsentConfiguration config() { return new ConsentConfiguration(Set.of("Person"), PURPOSE, Set.of(PURPOSE, "RESEARCH"), Set.of("admin"), null); }
    private static ApplicationService app(StorageProvider storage, AuthorizationService auth, ConsentService consent) {
        return new ApplicationService(storage, auth, new ActionExecutor(), SCHEMA, Map.of("Rename", RENAME), Map.of(), AuthorizationMode.STRICT_RESOURCES, consent);
    }
    private static JdbcDataSource data(String url) { var data = new JdbcDataSource(); data.setURL(url); data.setUser("sa"); return data; }
    private static ExecutionInput input(String query, RequestContext context, SecurityPrincipal principal) {
        return ExecutionInput.newExecutionInput(query).graphQLContext(Map.of("request", new ApiRequestContext(context, principal))).build();
    }
    private static HttpResponse<String> send(HttpClient client, JdkRestServer server, String method, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + path)).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static final class Fixture {
        final StorageProvider storage;
        final ConsentStore store;
        final Supplier<ConsentStore> reopen;
        final MutableClock clock;
        final AtomicBoolean relationship = new AtomicBoolean();
        final Set<EntityKey> denied = new HashSet<>();
        final AuthorizationService authorization;
        final ConsentService service;
        final ApplicationService app;
        Fixture(StorageProvider storage, ConsentStore store, Supplier<ConsentStore> reopen, MutableClock clock) {
            this.storage = storage; this.store = store; this.reopen = reopen; this.clock = clock;
            authorization = new AuthorizationService((principal, relation, key) -> !denied.contains(key) && (!relation.equals("caregiver") || relationship.get()));
            service = new ConsentService(store, authorization, config());
            app = app(storage, authorization, service);
        }
        void grant(EntityKey subject) { service.record(ADMIN_CTX, ADMIN, subject, PURPOSE, ConsentRecord.Decision.GRANT, "approved"); }
    }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-28T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    @FunctionalInterface private interface Check { void run(Fixture fixture) throws Exception; }
    public static final class RejectAudit implements org.h2.api.Trigger {
        @Override public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow) throws SQLException { throw new SQLException("injected consent audit failure"); }
    }
}
