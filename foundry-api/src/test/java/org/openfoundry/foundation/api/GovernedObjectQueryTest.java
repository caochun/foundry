package org.openfoundry.foundation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GovernedObjectQueryTest {
    private static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name: "queries", version: "1.0.0")
            enum State { OPEN CLOSED }
            type Item @objectType {
                key: ID! @primary name: String! amount: Float! state: State!
                note: String secret: String @sensitive active: Boolean
                at: DateTime elapsed: Duration labels: [String!] payload: JSON
            }
            """);
    private static final RequestContext CONTEXT = RequestContext.system("tenant", "reader");
    private static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("reader", "tenant", Set.of());

    @TestFactory
    Stream<DynamicTest> governedQueries() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "authorization precedes paging and total counts", this::pagination),
                test(provider, "typed predicates and nested logic", this::predicates),
                test(provider, "stable multi-field order and null placement", this::sorting),
                test(provider, "hidden fields cannot influence queries", this::fields),
                test(provider, "invalid queries are rejected even on empty data", this::invalid),
                test(provider, "temporal filters use historical values", this::temporal),
                test(provider, "tenant and actor isolation with revocation", this::isolation),
                test(provider, "GraphQL filter connection and old list agree", this::graphql),
                test(provider, "REST HTTP returns governed connections and safe errors", this::http)));
    }

    private DynamicTest test(String provider, String label, Check check) {
        return DynamicTest.dynamicTest(provider + " " + label, () -> {
            var clock = new TestClock();
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider(clock);
            else {
                var data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:queries_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
            }
            storage.applySchema(CONTEXT, SCHEMA);
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.createObject("Item", "a", Map.of("name", "Alpha", "amount", 10, "state", "OPEN", "secret", "alpha-secret",
                        "at", "2026-09-28T00:00:00.900Z", "elapsed", "PT10H", "active", true));
                tx.createObject("Item", "b", Map.of("name", "Beta", "amount", 2, "state", "CLOSED", "secret", "beta-secret",
                        "at", "2026-09-28T00:00:00Z", "elapsed", "PT2H", "note", "present", "active", false));
                var c = new LinkedHashMap<String, Object>(Map.of("name", "Beta", "amount", 2, "state", "OPEN"));
                c.put("note", null);
                tx.createObject("Item", "c", c);
                tx.commit();
            }
            try { check.run(new Fixture(storage, clock)); }
            finally { if (storage instanceof AutoCloseable resource) resource.close(); }
        });
    }

    private void pagination(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            for (int index = 0; index < 125; index++) {
                String id = "0-hidden-" + index;
                tx.createObject("Item", id, Map.of("name", "Hidden", "amount", 0, "state", "OPEN"));
                f.denied.add(id);
            }
            tx.commit();
        }
        var app = f.app(Map.of());
        var options = new QueryOptions(2, 1, null, null, false);
        var page = app.queryObjects(CONTEXT, PRINCIPAL, "Item", ObjectQuery.all(options));
        assertEquals(List.of("b", "c"), ids(page));
        assertEquals(3, page.totalCount());
        assertEquals(List.of("b", "c"), app.listObjects(CONTEXT, PRINCIPAL, "Item", options).stream().map(ObjectRecord::id).toList());
        assertTrue(page.connection().pageInfo().hasPreviousPage());
        assertFalse(page.connection().pageInfo().hasNextPage());
        assertEquals(3, ObjectQueryResult.offsetAfter(page.connection().pageInfo().endCursor()));
        var empty = app.queryObjects(CONTEXT, PRINCIPAL, "Item", ObjectQuery.all(new QueryOptions(1, Integer.MAX_VALUE, null, null, false)));
        assertEquals(3, empty.totalCount());
        assertTrue(empty.items().isEmpty());
        assertNull(empty.connection().pageInfo().startCursor());
    }

    private void predicates(Fixture f) {
        assertEquals(List.of("b", "c"), ids(f.query(Map.of("amount", Map.of("lt", 3)), Map.of())));
        assertEquals(List.of("a", "c"), ids(f.query(Map.of("state", Map.of("in", List.of("OPEN"))), Map.of())));
        assertEquals(List.of("b"), ids(f.query(Map.of("AND", List.of(Map.of("name", Map.of("contains", "et")), Map.of("NOT", Map.of("state", Map.of("eq", "OPEN"))))), Map.of())));
        assertEquals(List.of("a", "b"), ids(f.query(Map.of("OR", List.of(Map.of("key", Map.of("eq", "a")), Map.of("active", Map.of("eq", false)))), Map.of())));
        assertEquals(List.of("a", "c"), ids(f.query(Map.of("note", Map.of("exists", false)), Map.of())));
        assertEquals(List.of("a", "c"), ids(f.query(Map.of("note", Collections.singletonMap("eq", null)), Map.of())));
        assertEquals(List.of("b"), ids(f.query(Map.of("note", Collections.singletonMap("ne", null)), Map.of())));
        assertEquals(List.of("a"), ids(f.query(Map.of("at", Map.of("gt", "2026-09-28T08:00:00+08:00")), Map.of())));
        assertEquals(List.of("a"), ids(f.query(Map.of("elapsed", Map.of("gt", "PT3H")), Map.of())));
        assertEquals(List.of("b", "c"), ids(f.query(Map.of("name", Map.of("startsWith", "Be"), "amount", Map.of("eq", 2.0)), Map.of())));
        assertEquals(0, f.query(Map.of("OR", List.of()), Map.of()).totalCount());
        assertEquals(3, f.query(Map.of("AND", List.of()), Map.of()).totalCount());
        assertEquals(0, f.query(Map.of("key", Map.of("in", List.of())), Map.of()).totalCount());
    }

    private void sorting(Fixture f) {
        assertEquals(List.of("b", "c", "a"), ids(f.query(Map.of(), Map.of("amount", "ASC"))));
        assertEquals(List.of("a", "b", "c"), ids(f.query(Map.of(), Map.of("amount", "DESC"))));
        assertEquals(List.of("b", "a", "c"), ids(f.query(Map.of(), Map.of("note", "ASC"))));
        assertEquals(List.of("b", "a", "c"), ids(f.query(Map.of(), Map.of("note", "DESC"))));
        assertEquals(List.of("b", "a", "c"), ids(f.query(Map.of(), Map.of("at", "ASC"))));
        var order = new LinkedHashMap<String, String>();
        order.put("amount", "ASC");
        order.put("key", "DESC");
        assertEquals(List.of("c", "b", "a"), ids(f.query(Map.of(), order)));
    }

    private void fields(Fixture f) {
        assertThrows(SecurityException.class, () -> f.query(Map.of("OR", List.of(Map.of(), Map.of("secret", Map.of("eq", "alpha-secret")))), Map.of()));
        assertThrows(SecurityException.class, () -> f.query(Map.of(), Map.of("secret", "ASC")));
        assertTrue(f.query(Map.of(), Map.of()).items().stream().noneMatch(row -> row.properties().containsKey("secret")));
        var app = f.app(Map.of("Item", new FieldPolicy(Set.of("name"), Map.of("auditor", Set.of("secret")))));
        assertThrows(SecurityException.class, () -> app.queryObjects(CONTEXT, PRINCIPAL, "Item", query(Map.of("amount", Map.of("eq", 2)), Map.of())));
        var auditor = new SecurityPrincipal("reader", "tenant", Set.of("auditor"));
        assertEquals(List.of("a"), ids(app.queryObjects(CONTEXT, auditor, "Item", query(Map.of("secret", Map.of("eq", "alpha-secret")), Map.of()))));
        assertEquals(List.of("a"), ids(app.queryObjects(CONTEXT, PRINCIPAL, "Item", query(Map.of("key", Map.of("eq", "a")), Map.of()))));
    }

    private void invalid(Fixture f) {
        f.denied.addAll(List.of("a", "b", "c"));
        for (Map<String, Object> filter : List.<Map<String, Object>>of(
                Map.of("unknown", Map.of("eq", 1)), Map.of("amount", Map.of("eq", "two")),
                Map.of("state", Map.of("eq", "MISSING")), Map.of("amount", Map.of("contains", 1)),
                Map.of("name", Map.of("endsWith", "a")), Map.of("payload", Map.of("eq", Map.of())),
                Map.of("labels", Map.of("eq", List.of("a"))), Map.of("OR", Map.of()),
                Map.of("active", Map.of("exists", "yes")), Map.of("amount", Map.of("in", Collections.singletonList(null))))) {
            assertThrows(IllegalArgumentException.class, () -> f.query(filter, Map.of()), filter.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> f.query(Map.of(), Map.of("active", "ASC")));
        assertThrows(IllegalArgumentException.class, () -> f.query(Map.of(), Map.of("name", "ascending")));
        Map<String, Object> deep = Map.of();
        for (int i = 0; i < 34; i++) deep = Map.of("NOT", deep);
        var filter = deep;
        assertThrows(IllegalArgumentException.class, () -> f.query(filter, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> f.app(Map.of()).queryObjects(CONTEXT, PRINCIPAL, "Missing", query(Map.of(), Map.of())));
    }

    private void temporal(Fixture f) {
        Instant old = f.clock.instant();
        f.clock.now = old.plusSeconds(10);
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Item", "a", Map.of("amount", 1), 1);
            tx.deleteObject("Item", "b", 1);
            tx.commit();
        }
        var past = new ObjectQuery(Map.of("amount", Map.of("lt", 3)), Map.of("amount", "ASC"), new QueryOptions(10, 0, old, f.clock.instant(), false));
        assertEquals(List.of("b", "c"), ids(f.app(Map.of()).queryObjects(CONTEXT, PRINCIPAL, "Item", past)));
        assertEquals(List.of("a", "c"), ids(f.query(Map.of("amount", Map.of("lt", 3)), Map.of("amount", "ASC"))));
        var deleted = new ObjectQuery(Map.of(), Map.of(), new QueryOptions(10, 0, null, null, true));
        assertEquals(3, f.app(Map.of()).queryObjects(CONTEXT, PRINCIPAL, "Item", deleted).totalCount());
    }

    private void isolation(Fixture f) {
        var other = RequestContext.system("other", "reader");
        f.storage.applySchema(other, SCHEMA);
        try (var tx = f.storage.beginTransaction(other)) {
            tx.createObject("Item", "a", Map.of("name", "Other", "amount", 5, "state", "OPEN"));
            tx.commit();
        }
        var app = f.app(Map.of());
        assertEquals(3, f.query(Map.of(), Map.of()).totalCount());
        assertEquals(1, app.queryObjects(other, new SecurityPrincipal("reader", "other", Set.of()), "Item", query(Map.of(), Map.of())).totalCount());
        assertThrows(SecurityException.class, () -> app.queryObjects(other, PRINCIPAL, "Item", query(Map.of(), Map.of())));
        assertThrows(SecurityException.class, () -> app.queryObjects(CONTEXT, new SecurityPrincipal("imposter", "tenant", Set.of()), "Item", query(Map.of(), Map.of())));
        f.denied.add("b");
        assertEquals(List.of("a", "c"), ids(f.query(Map.of(), Map.of())));
    }

    private void graphql(Fixture f) {
        f.denied.add("b");
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app(Map.of()), Map.of(), GraphqlApiRuntime.ActionMode.TYPED, GraphqlApiRuntime.QueryMode.LEGACY_LIST);
        var result = graph.execute(ExecutionInput.newExecutionInput("""
                query($filter: ItemFilter!) {
                  items(filter: $filter, orderBy: {amount: ASC}) { key name }
                  itemsConnection(filter: $filter, orderBy: {amount: ASC}, first: 1) {
                    totalCount edges { node { key secret } cursor }
                    pageInfo { hasNextPage hasPreviousPage startCursor endCursor }
                  }
                }
                """).variables(Map.of("filter", Map.of("state", Map.of("eq", "OPEN"))))
                .graphQLContext(Map.of("request", new ApiRequestContext(CONTEXT, PRINCIPAL))).build());
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        Map<String, Object> data = result.getData();
        assertEquals(List.of(Map.of("key", "c", "name", "Beta"), Map.of("key", "a", "name", "Alpha")), data.get("items"));
        var connection = (Map<?, ?>) data.get("itemsConnection");
        assertEquals(2, connection.get("totalCount"));
        var info = (Map<?, ?>) connection.get("pageInfo");
        assertEquals(true, info.get("hasNextPage"));
        String cursor = (String) info.get("endCursor");
        var next = graph.execute(ExecutionInput.newExecutionInput("query($after: String!) { itemsConnection(orderBy: {amount: ASC}, first: 1, after: $after) { edges { node { key } } } }")
                .variables(Map.of("after", cursor)).graphQLContext(Map.of("request", new ApiRequestContext(CONTEXT, PRINCIPAL))).build());
        assertTrue(next.getErrors().isEmpty(), next.getErrors().toString());
        assertTrue(next.getData().toString().contains("key=a"));
        var denied = graph.execute(ExecutionInput.newExecutionInput("{ itemsConnection(filter: { secret: {eq: \"alpha-secret\"} }) { totalCount } }")
                .graphQLContext(Map.of("request", new ApiRequestContext(CONTEXT, PRINCIPAL))).build());
        assertFalse(denied.getErrors().isEmpty());
        assertNull(denied.getData());
        String sdl = new GraphqlContractGenerator().generate(SCHEMA);
        assertTrue(sdl.contains("itemsConnection(filter: ItemFilter"));
        assertTrue(new graphql.schema.idl.SchemaParser().parse(sdl).getType("ItemConnection").isPresent());
    }

    private void http(Fixture f) throws Exception {
        f.denied.add("a");
        try (var server = new JdkRestServer(0, new RestApiRouter(f.app(Map.of())), () -> new ApiRequestContext(CONTEXT, PRINCIPAL));
             var client = HttpClient.newHttpClient()) {
            server.start();
            var response = post(client, server, "{\"filter\":{\"amount\":{\"lt\":3}},\"orderBy\":{\"key\":\"DESC\"},\"first\":1}");
            assertEquals(200, response.statusCode(), response.body());
            var body = new ObjectMapper().readTree(response.body());
            assertEquals(2, body.path("totalCount").asInt());
            assertEquals("c", body.path("edges").get(0).path("node").path("id").asText());
            assertFalse(body.toString().contains("secret"));
            assertEquals(403, post(client, server, "{\"filter\":{\"secret\":{\"exists\":true}}}").statusCode());
            assertEquals(400, post(client, server, "{\"after\":\"bad\"}").statusCode());
            assertEquals(400, post(client, server, "{\"unknown\":true}").statusCode());
            assertEquals(400, post(client, server, "{\"asOfValidTime\":\"2026-09-28T00:00:00Z\"}").statusCode());
        }
    }

    @Test
    void invalidCursorsAndGeneratedNamesFailExplicitly() {
        for (String cursor : List.of("", "invalid", "Y3Vyc29yOi0x", "Y3Vyc29yOjIxNDc0ODM2NDc=", "Y3Vyc29yOjAw")) {
            assertThrows(IllegalArgumentException.class, () -> ObjectQueryResult.offsetAfter(cursor));
        }
        for (String name : List.of("ItemFilter", "ItemConnection", "StringFilter", "PageInfo", "SortDirection", "Items")) {
            var schema = new OdlParser().parse("""
                    extend schema @namespace(name: "collision", version: "1.0.0")
                    type Item @objectType { id: ID! @primary name: String }
                    type %s @objectType { id: ID! @primary }
                    """.formatted(name));
            assertThrows(IllegalArgumentException.class, () -> new GraphqlContractGenerator().generate(schema), name);
        }
    }

    private static HttpResponse<String> post(HttpClient client, JdkRestServer server, String json) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/v1/Item/query"))
                .POST(HttpRequest.BodyPublishers.ofString(json)).header("Content-Type", "application/json").build(), HttpResponse.BodyHandlers.ofString());
    }

    private static ObjectQuery query(Map<String, Object> filter, Map<String, String> order) {
        return new ObjectQuery(filter, order, QueryOptions.defaults());
    }

    private static List<String> ids(ObjectQueryResult result) { return result.items().stream().map(ObjectRecord::id).toList(); }

    private static class Fixture {
        final StorageProvider storage;
        final TestClock clock;
        final Set<String> denied = new HashSet<>();
        Fixture(StorageProvider storage, TestClock clock) { this.storage = storage; this.clock = clock; }
        ApplicationService app(Map<String, FieldPolicy> policies) {
            return new ApplicationService(storage, new AuthorizationService((p, r, key) -> !denied.contains(key.id())), new ActionExecutor(), SCHEMA, Map.of(), policies);
        }
        ObjectQueryResult query(Map<String, Object> filter, Map<String, String> order) {
            return app(Map.of()).queryObjects(CONTEXT, PRINCIPAL, "Item", GovernedObjectQueryTest.query(filter, order));
        }
    }

    @FunctionalInterface private interface Check { void run(Fixture fixture) throws Exception; }
    private static class TestClock extends Clock {
        Instant now = Instant.parse("2026-09-28T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
