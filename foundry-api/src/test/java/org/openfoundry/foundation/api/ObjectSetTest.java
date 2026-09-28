package org.openfoundry.foundation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.schema.*;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ObjectSetTest {
    @TempDir Path directory;
    static final RequestContext OWNER = RequestContext.system("tenant", "alice");
    static final RequestContext OTHER = RequestContext.system("tenant", "bob");
    static final RequestContext FOREIGN = RequestContext.system("another", "alice");
    static final SecurityPrincipal ALICE = new SecurityPrincipal("alice", "tenant", Set.of("privileged"));
    static final SecurityPrincipal BOB = new SecurityPrincipal("bob", "tenant", Set.of());
    static final String ODL = """
            extend schema @namespace(name:"sets", version:"1.0.0")
            enum Category { A B }
            type Item @objectType { id: ID! @primary name: String! amount: Int! category: Category! secret: String @sensitive }
            """;
    static final OntologySchema SCHEMA = new OdlParser().parse(ODL);

    @TestFactory
    Stream<DynamicTest> setsAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "store enforces ownership tenant scope and immutable snapshots", this::ownership),
                test(provider, "partial edits clears protected fields and revision checks", this::updates),
                test(provider, "names may repeat but lookup never selects hidden definitions", this::names),
                test(provider, "live execution uses caller permissions and saved sorting", this::execution),
                test(provider, "aggregation intersects saved and aggregate filters", this::aggregation),
                test(provider, "shared criteria cannot disclose protected fields", this::criteria),
                test(provider, "definition revocation or edit during execution discards results", this::changedDuringExecution),
                test(provider, "schema changes revalidate execution while owners can repair metadata", this::schemaChanges),
                test(provider, "concurrent store edits merge or conflict atomically", this::concurrency),
                test(provider, "GraphQL saved definitions and execution", this::graphql),
                test(provider, "REST metadata and execution routes", this::http)));
    }

    private DynamicTest test(String provider, String name, Check check) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> {
            var clock = new MutableClock();
            StorageProvider storage;
            ObjectSetStore store;
            Supplier<ObjectSetStore> reopen;
            if (provider.equals("memory")) {
                storage = new InMemoryStorageProvider(clock);
                store = new InMemoryObjectSetStore(clock);
                reopen = () -> store;
            } else {
                var data = data("jdbc:h2:mem:sets_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
                store = new JdbcObjectSetStore(data, DatabaseDialect.h2(), clock);
                reopen = () -> new JdbcObjectSetStore(data, DatabaseDialect.h2(), clock);
            }
            storage.applySchema(OWNER, SCHEMA);
            try (var tx = storage.beginTransaction(OWNER)) {
                tx.createObject("Item", "a", Map.of("name", "One", "amount", 1, "category", "A", "secret", "alpha"));
                tx.createObject("Item", "b", Map.of("name", "Two", "amount", 2, "category", "A", "secret", "beta"));
                tx.createObject("Item", "c", Map.of("name", "Three", "amount", 3, "category", "B", "secret", "alpha"));
                tx.commit();
            }
            check.run(new Fixture(storage, store, reopen, clock));
        });
    }

    private void ownership(Fixture f) {
        var input = new LinkedHashMap<String, Object>(definition(false));
        var filter = new LinkedHashMap<String, Object>(Map.of("field", "amount", "operator", "gte", "value", 2));
        input.put("filter", filter);
        var saved = f.store.create(OWNER, ObjectSetSpec.fromMap(input));
        filter.put("value", 100);
        assertEquals("alice", saved.createdBy());
        assertEquals("tenant", saved.tenantId());
        assertEquals(2, saved.spec().filter().get("value"));
        assertNull(f.store.get(OTHER, saved.id()));
        assertNull(f.store.get(FOREIGN, saved.id()));
        assertTrue(f.store.list(OTHER, null).isEmpty());
        assertNull(f.store.getByName(OTHER, saved.spec().name()));
        var anonymous = new RequestContext("tenant", null, null);
        assertNull(f.store.get(anonymous, saved.id()));
        assertThrows(SecurityException.class, () -> f.store.create(anonymous, saved.spec()));
        assertThrows(SecurityException.class, () -> f.store.update(OTHER, saved.id(), Map.of("name", "Hijacked")));
        assertThrows(SecurityException.class, () -> f.store.delete(anonymous, saved.id()));
        assertThrows(ObjectSetNotFoundException.class, () -> f.store.delete(FOREIGN, saved.id()));
        assertThrows(UnsupportedOperationException.class, () -> saved.spec().filter().put("value", 3));
        var shared = f.store.update(OWNER, saved.id(), Map.of("isPublic", true));
        assertEquals(shared, f.store.get(OTHER, saved.id()));
        assertEquals(shared, f.store.get(anonymous, saved.id()));
        assertNull(f.store.get(FOREIGN, saved.id()));
        var forged = new LinkedHashMap<>(definition(true));
        forged.put("createdBy", "bob");
        assertThrows(IllegalArgumentException.class, () -> f.service.create(OWNER, ALICE, forged));
        assertThrows(SecurityException.class, () -> f.service.create(OTHER, ALICE, definition(true)));
    }

    private void updates(Fixture f) {
        var created = f.service.create(OWNER, ALICE, definition(true));
        f.clock.advance();
        var patched = f.service.update(OWNER, ALICE, created.id(), Map.of("name", "Edited", "description", "Description"), 1L);
        assertEquals(2, patched.version());
        assertEquals(created.createdAt(), patched.createdAt());
        assertTrue(patched.updatedAt().isAfter(created.createdAt()));
        assertEquals(created.spec().filter(), patched.spec().filter());
        assertThrows(ObjectSetConflictException.class, () -> f.store.update(OWNER, created.id(), Map.of("name", "Stale"), 1L));
        assertThrows(SecurityException.class, () -> f.service.update(OTHER, BOB, created.id(), Map.of("name", "Forbidden"), 2L));
        for (String field : List.of("id", "tenantId", "createdBy", "createdAt", "updatedAt", "objectType", "version")) {
            assertThrows(IllegalArgumentException.class, () -> f.store.update(OWNER, created.id(), Map.of(field, "forged")), field);
        }
        var clears = new LinkedHashMap<String, Object>();
        for (String field : List.of("description", "filter", "orderBy", "limit", "aggregation")) clears.put(field, null);
        var cleared = f.service.update(OWNER, ALICE, created.id(), clears, 2L);
        assertNull(cleared.spec().description());
        assertNull(cleared.spec().aggregation());
        assertNull(cleared.spec().limit());
        assertTrue(cleared.spec().filter().isEmpty());
        assertTrue(cleared.spec().orderBy().isEmpty());
        f.store.delete(OWNER, created.id(), 3L);
        assertNull(f.store.get(OWNER, created.id()));
    }

    private void names(Fixture f) {
        var privateSet = f.store.create(OWNER, ObjectSetSpec.fromMap(definition(false)));
        f.clock.advance();
        var publicSet = f.store.create(OWNER, ObjectSetSpec.fromMap(definition(true)));
        assertEquals(privateSet.id(), f.store.getByName(OWNER, "Current cohort").id());
        assertEquals(publicSet.id(), f.store.getByName(OTHER, "Current cohort").id());
        var foreign = f.store.create(FOREIGN, ObjectSetSpec.fromMap(definition(true)));
        assertEquals(foreign.id(), f.store.getByName(FOREIGN, "Current cohort").id());
        assertEquals(2, f.store.list(OWNER, "Item").size());
        assertTrue(f.store.list(OWNER, "Missing").isEmpty());
        assertEquals(publicSet.id(), f.service.getByName(OTHER, BOB, "Current cohort").id());
    }

    private void execution(Fixture f) {
        var byId = new LinkedHashMap<>(definition(false));
        byId.put("filter", Map.of("field", "_id", "operator", "eq", "value", "a"));
        var selected = f.service.create(OWNER, ALICE, byId);
        assertEquals(List.of("a"), ids(f.service.execute(OWNER, ALICE, selected.id(), 10, 0)));
        var saved = f.service.create(OWNER, ALICE, definition(true));
        assertEquals(List.of("c"), ids(f.service.execute(OWNER, ALICE, saved.id(), null, 0)));
        var other = f.service.execute(OTHER, BOB, saved.id(), 20, 0);
        assertEquals(List.of("b"), ids(other));
        assertEquals(1, other.totalCount());
        assertFalse(other.edges().getFirst().node().properties().containsKey("secret"));
        var zero = f.service.execute(OWNER, ALICE, saved.id(), 0, 0);
        assertTrue(zero.edges().isEmpty());
        assertEquals(2, zero.totalCount());
        try (var tx = f.storage.beginTransaction(OWNER)) {
            for (int index = 0; index < 130; index++) {
                String id = "hidden-" + index;
                tx.createObject("Item", id, Map.of("name", "Hidden", "amount", 50, "category", "A"));
                f.denied.add(id);
            }
            tx.createObject("Item", "d", Map.of("name", "New", "amount", 4, "category", "A"));
            tx.commit();
        }
        var changed = f.service.execute(OWNER, ALICE, saved.id(), null, 0);
        assertEquals(3, changed.totalCount());
        assertEquals(List.of("d"), ids(changed));
        f.denied.add("d");
        assertEquals(List.of("c"), ids(f.service.execute(OWNER, ALICE, saved.id(), null, 0)));
        f.service.update(OWNER, ALICE, saved.id(), Map.of("isPublic", false), 1L);
        assertThrows(ObjectSetNotFoundException.class, () -> f.service.execute(OTHER, BOB, saved.id(), 10, 0));
    }

    private void aggregation(Fixture f) {
        var input = new LinkedHashMap<>(definition(true));
        input.put("aggregation", Map.of("fields", List.of(Map.of("field", "amount", "fn", "sum", "alias", "total")),
                "filter", Map.of("field", "amount", "operator", "lte", "value", 2)));
        var saved = f.service.create(OWNER, ALICE, input);
        assertEquals(2.0, f.service.aggregate(OWNER, ALICE, saved.id()).groups().getFirst().values().get("total"));
        assertEquals(2.0, f.service.aggregate(OTHER, BOB, saved.id()).groups().getFirst().values().get("total"));
        f.denied.add("b");
        assertNull(f.service.aggregate(OTHER, BOB, saved.id()).groups().getFirst().values().get("total"));
        f.service.update(OWNER, ALICE, saved.id(), Collections.singletonMap("aggregation", null), 1L);
        assertThrows(IllegalArgumentException.class, () -> f.service.aggregate(OWNER, ALICE, saved.id()));
        var logical = new LinkedHashMap<>(definition(false));
        logical.put("filter", Map.of("and", List.of(Map.of("field", "amount", "operator", "gte", "value", 1),
                Map.of("not", Map.of("field", "category", "operator", "eq", "value", "B")))));
        var normalized = f.service.create(OWNER, ALICE, logical);
        assertEquals(List.of("a"), ids(f.service.execute(OWNER, ALICE, normalized.id(), 10, 0)));
    }

    private void criteria(Fixture f) {
        var input = new LinkedHashMap<>(definition(true));
        input.put("filter", Map.of("field", "secret", "operator", "eq", "value", "alpha"));
        var saved = f.service.create(OWNER, ALICE, input);
        assertNotNull(f.store.get(OTHER, saved.id()), "The metadata store implements tenant/public visibility; the service applies field policy");
        assertThrows(SecurityException.class, () -> f.service.get(OTHER, BOB, saved.id()));
        assertTrue(f.service.list(OTHER, BOB, null).isEmpty());
        assertThrows(SecurityException.class, () -> f.service.execute(OTHER, BOB, saved.id(), 10, 0));
        assertThrows(SecurityException.class, () -> f.service.create(OTHER, BOB, input));
        var noPrivilege = new SecurityPrincipal("alice", "tenant", Set.of());
        assertNotNull(f.service.get(OWNER, noPrivilege, saved.id()), "Owners can inspect their authored query to repair it");
        assertThrows(SecurityException.class, () -> f.service.execute(OWNER, noPrivilege, saved.id(), 10, 0));
        var unshared = f.service.update(OWNER, noPrivilege, saved.id(), Map.of("isPublic", false, "name", "Retired"), 1L);
        assertFalse(unshared.spec().isPublic());
        assertNull(f.service.get(OTHER, BOB, saved.id()));
        f.service.delete(OWNER, noPrivilege, saved.id(), 2L);
    }

    private void changedDuringExecution(Fixture f) {
        var saved = f.service.create(OWNER, ALICE, definition(true));
        var changed = new AtomicBoolean();
        var app = new ApplicationService(f.storage, new AuthorizationService((principal, relation, key) -> {
            if (changed.compareAndSet(false, true)) f.store.update(OWNER, saved.id(), Map.of("isPublic", false));
            return true;
        }), new ActionExecutor(), SCHEMA, Map.of(), policies());
        var executing = new ObjectSetService(app, f.store);
        assertThrows(ObjectSetNotFoundException.class, () -> executing.execute(OTHER, BOB, saved.id(), 10, 0));
        f.store.update(OWNER, saved.id(), Map.of("isPublic", true));
        var edited = new AtomicBoolean();
        var changing = new ApplicationService(f.storage, new AuthorizationService((principal, relation, key) -> {
            if (edited.compareAndSet(false, true)) f.store.update(OWNER, saved.id(), Map.of("name", "Renamed"));
            return true;
        }), new ActionExecutor(), SCHEMA, Map.of(), policies());
        assertThrows(ObjectSetConflictException.class, () -> new ObjectSetService(changing, f.store).execute(OTHER, BOB, saved.id(), 10, 0));
    }

    private void schemaChanges(Fixture f) {
        var saved = f.service.create(OWNER, ALICE, definition(false));
        var next = new OdlParser().parse(ODL.replace("amount: Int!", "amount: Int! optional: String"));
        if (f.storage instanceof JdbcStorageProvider jdbc) jdbc.activateSchema(OWNER, next, null, jdbc.boundSchemaVersion());
        else f.storage.applySchema(OWNER, next);
        assertThrows(SchemaVersionMismatchException.class, () -> f.service.execute(OWNER, ALICE, saved.id(), 10, 0));
        var current = new ObjectSetService(new ApplicationService(f.storage, f.authorization(), new ActionExecutor(), next, Map.of(), policies()), f.store);
        assertEquals(2, current.execute(OWNER, ALICE, saved.id(), 10, 0).totalCount());
        var invalid = new LinkedHashMap<>(definition(false));
        invalid.put("filter", Map.of("field", "removed", "operator", "eq", "value", "old"));
        var obsolete = f.store.create(OWNER, ObjectSetSpec.fromMap(invalid));
        assertNotNull(current.get(OWNER, ALICE, obsolete.id()));
        assertThrows(IllegalArgumentException.class, () -> current.execute(OWNER, ALICE, obsolete.id(), 10, 0));
        current.update(OWNER, ALICE, obsolete.id(), Map.of("filter", Map.of()), 1L);
        assertEquals(3, current.execute(OWNER, ALICE, obsolete.id(), 10, 0).totalCount());
    }

    private void concurrency(Fixture f) throws Exception {
        var saved = f.store.create(OWNER, ObjectSetSpec.fromMap(definition(false)));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); return f.reopen.get().update(OWNER, saved.id(), Map.of("name", "Renamed")); });
            var b = pool.submit(() -> { start.await(); return f.reopen.get().update(OWNER, saved.id(), Map.of("description", "Annotated")); });
            start.countDown();
            a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
            var merged = f.store.get(OWNER, saved.id());
            assertEquals(3, merged.version());
            assertEquals("Renamed", merged.spec().name());
            assertEquals("Annotated", merged.spec().description());
            var release = new CountDownLatch(1);
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) results.add(pool.submit(() -> {
                release.await();
                try { f.reopen.get().update(OWNER, saved.id(), Map.of("name", "Winner"), 3L); return true; }
                catch (ObjectSetConflictException conflict) { return false; }
            }));
            release.countDown();
            int success = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) success++;
            assertEquals(1, success);
            assertEquals(4, f.store.get(OWNER, saved.id()).version());
        }
    }

    private void graphql(Fixture f) {
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app, Map.of(), GraphqlApiRuntime.ActionMode.TYPED, GraphqlApiRuntime.QueryMode.CONNECTION, f.store);
        var created = graph.execute(input("""
                mutation { createObjectSet(input:{name:"Graph set",objectType:"Item",isPublic:true,
                    filter:{field:"amount",operator:"gte",value:2},orderBy:[{field:"amount",direction:"desc"}],limit:1}) { id name version createdBy } }
                """, OWNER, ALICE));
        assertTrue(created.getErrors().isEmpty(), created.getErrors().toString());
        Map<?, ?> result = created.getData();
        var set = (Map<?, ?>) result.get("createObjectSet");
        String id = (String) set.get("id");
        assertEquals("1", set.get("version"));
        assertEquals("alice", set.get("createdBy"));
        var executed = graph.execute(input("{executeObjectSet(id:\"" + id + "\"){totalCount edges{node}}}", OTHER, BOB));
        assertTrue(executed.getErrors().isEmpty(), executed.getErrors().toString());
        var page = (Map<?, ?>) ((Map<?, ?>) executed.getData()).get("executeObjectSet");
        assertEquals(1, page.get("totalCount"));
        assertFalse(page.toString().contains("secret"));
        var updated = graph.execute(input("mutation{updateObjectSet(id:\"" + id + "\",expectedVersion:\"1\",input:{description:\"Edited\"}){version description}}", OWNER, ALICE));
        assertTrue(updated.getErrors().isEmpty(), updated.getErrors().toString());
        var stale = graph.execute(input("mutation{deleteObjectSet(id:\"" + id + "\",expectedVersion:\"1\")}", OWNER, ALICE));
        assertEquals("OBJECT_SET_CONFLICT", stale.getErrors().getFirst().getExtensions().get("code"));
        var deleted = graph.execute(input("mutation{deleteObjectSet(id:\"" + id + "\",expectedVersion:\"2\")}", OWNER, ALICE));
        assertTrue(deleted.getErrors().isEmpty(), deleted.getErrors().toString());
        assertEquals(Map.of("deleteObjectSet", true), deleted.getData());
        var sdl = new GraphqlContractGenerator().generate(SCHEMA);
        assertTrue(new graphql.schema.idl.SchemaParser().parse(sdl).getType("ObjectSet").isPresent());
        assertTrue(sdl.contains("createObjectSet(input: CreateObjectSetInput!): ObjectSet!"));
    }

    private void http(Fixture f) throws Exception {
        var identity = new java.util.concurrent.atomic.AtomicReference<>(new ApiRequestContext(OWNER, ALICE));
        try (var server = new JdkRestServer(0, new RestApiRouter(f.app, f.store), identity::get); var client = HttpClient.newHttpClient()) {
            server.start();
            var created = send(client, server, "POST", "", new ObjectMapper().writeValueAsString(definition(true)));
            assertEquals(201, created.statusCode(), created.body());
            String id = new ObjectMapper().readTree(created.body()).path("id").asText();
            identity.set(new ApiRequestContext(OTHER, BOB));
            var executed = send(client, server, "GET", "/" + id + "/execute?limit=20", null);
            assertEquals(200, executed.statusCode(), executed.body());
            assertEquals(1, new ObjectMapper().readTree(executed.body()).path("totalCount").asInt());
            assertFalse(executed.body().contains("secret"));
            assertEquals(403, send(client, server, "DELETE", "/" + id, null).statusCode());
            identity.set(new ApiRequestContext(OWNER, ALICE));
            assertEquals(400, send(client, server, "PUT", "/" + id, "{\"objectType\":\"Other\"}").statusCode());
            assertEquals(200, send(client, server, "PUT", "/" + id, "{\"name\":\"HTTP edited\",\"expectedVersion\":1}").statusCode());
            assertEquals(409, send(client, server, "DELETE", "/" + id + "?expectedVersion=1", null).statusCode());
            var removed = send(client, server, "DELETE", "/" + id + "?expectedVersion=2", null);
            assertEquals(204, removed.statusCode());
            assertTrue(removed.body().isEmpty());
            assertEquals(404, send(client, server, "GET", "/" + id, null).statusCode());
        }
    }

    @Test
    void durableDefinitionsSurviveReconstructionAndAOneConnectionPool() {
        String url = "jdbc:h2:file:" + directory.resolve("sets");
        var store = new JdbcObjectSetStore(data(url), DatabaseDialect.h2());
        var saved = store.create(OWNER, ObjectSetSpec.fromMap(definition(true)));
        var restarted = new JdbcObjectSetStore(data(url), DatabaseDialect.h2());
        assertEquals(saved, restarted.get(OWNER, saved.id()));
        var pool = org.h2.jdbcx.JdbcConnectionPool.create(url, "sa", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(2);
        try {
            var single = new JdbcObjectSetStore(pool, DatabaseDialect.h2());
            assertEquals(2, single.update(OWNER, saved.id(), Map.of("name", "Persisted"), 1L).version());
            assertEquals("Persisted", restarted.get(OWNER, saved.id()).spec().name());
            single.delete(OWNER, saved.id(), 2L);
            assertNull(restarted.get(OWNER, saved.id()));
        } finally { pool.dispose(); }
    }

    @Test
    void coreNamesAndMissingConfigurationFailExplicitly() {
        for (String name : List.of("ObjectSet", "CreateObjectSetInput", "ObjectSetPage", "ObjectSets")) {
            var schema = new OdlParser().parse(ODL + "\ntype " + name + " @objectType { id: ID! @primary }");
            assertThrows(IllegalArgumentException.class, () -> new GraphqlContractGenerator().generate(schema));
        }
        var action = new OdlParser().parse(ODL + "\ntype CreateObjectSet @actionType { name: String @param }");
        assertThrows(IllegalArgumentException.class, () -> new GraphqlContractGenerator().generate(action, GraphqlApiRuntime.ActionMode.LEGACY_JSON));
        var storage = new InMemoryStorageProvider();
        storage.applySchema(OWNER, SCHEMA);
        var app = new ApplicationService(storage, new AuthorizationService((p, r, k) -> true), new ActionExecutor(), SCHEMA, Map.of(), Map.of());
        var result = GraphqlApiRuntime.create(SCHEMA, app).execute(input("{objectSets{id}}", OWNER, ALICE));
        assertEquals("OBJECT_SETS_NOT_CONFIGURED", result.getErrors().getFirst().getExtensions().get("code"));
    }

    private static Map<String, Object> definition(boolean shared) {
        return Map.of("name", "Current cohort", "objectType", "Item", "isPublic", shared, "limit", 1,
                "filter", Map.of("field", "amount", "operator", "gte", "value", 2), "orderBy", List.of(Map.of("field", "amount", "direction", "desc")));
    }
    private static Map<String, FieldPolicy> policies() {
        return Map.of("Item", new FieldPolicy(Set.of("id", "name", "amount", "category"), Map.of("privileged", Set.of("secret"))));
    }
    private static ExecutionInput input(String query, RequestContext context, SecurityPrincipal principal) {
        return ExecutionInput.newExecutionInput(query).graphQLContext(Map.of("request", new ApiRequestContext(context, principal))).build();
    }
    private static List<String> ids(ObjectQueryResult.Connection result) { return result.edges().stream().map(edge -> edge.node().id()).toList(); }
    private static JdbcDataSource data(String url) { var data = new JdbcDataSource(); data.setURL(url); data.setUser("sa"); return data; }
    private static HttpResponse<String> send(HttpClient client, JdkRestServer server, String method, String suffix, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/v1/object-sets" + suffix))
                .header("Content-Type", "application/json").method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static final class Fixture {
        final StorageProvider storage;
        final ObjectSetStore store;
        final Supplier<ObjectSetStore> reopen;
        final MutableClock clock;
        final Set<String> denied = new HashSet<>();
        final ApplicationService app;
        final ObjectSetService service;
        Fixture(StorageProvider storage, ObjectSetStore store, Supplier<ObjectSetStore> reopen, MutableClock clock) {
            this.storage = storage; this.store = store; this.reopen = reopen; this.clock = clock;
            app = new ApplicationService(storage, authorization(), new ActionExecutor(), SCHEMA, Map.of(), policies());
            service = new ObjectSetService(app, store);
        }
        AuthorizationService authorization() { return new AuthorizationService((principal, relation, key) -> !denied.contains(key.id()) && (principal.id().equals("alice") || key.id().equals("b"))); }
    }
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-28T00:00:00Z");
        void advance() { now = now.plusSeconds(1); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    @FunctionalInterface private interface Check { void run(Fixture fixture) throws Exception; }
}
