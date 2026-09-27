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
import static org.openfoundry.foundation.api.AggregateQuery.Function.*;

class GovernedAggregationTest {
    private static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name: "aggregates", version: "1.0.0")
            enum Category { A B }
            type Item @objectType {
                key: ID! @primary name: String! amount: Float units: Int category: Category note: String
                secret: Float @sensitive secretGroup: String @sensitive tags: [String!] payload: JSON
            }
            """);
    private static final RequestContext CONTEXT = RequestContext.system("tenant", "reader");
    private static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("reader", "tenant", Set.of());

    @TestFactory
    Stream<DynamicTest> aggregationAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "all functions count star versus nullable fields", this::functions),
                test(provider, "null groups composite grouping and canonical JSON", this::groups),
                test(provider, "group sorting pagination and zero limit", this::pagination),
                test(provider, "authorization applies before every measure and group", this::authorization),
                test(provider, "all query fields obey role and sensitive policies", this::fieldAuthorization),
                test(provider, "empty inputs preserve SQL aggregate semantics", this::empty),
                test(provider, "all invalid definitions fail before data evaluation", this::invalid),
                test(provider, "historical values deleted objects and tenant isolation", this::temporalAndIsolation),
                test(provider, "numeric precision overflow and empty numeric values", this::numbers),
                test(provider, "GraphQL preserves the upstream aggregate shape", this::graphql),
                test(provider, "HTTP aggregate authorization validation and temporal options", this::http)));
    }

    private DynamicTest test(String provider, String name, Check check) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> {
            var clock = new TestClock();
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider(clock);
            else {
                var data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:aggregate_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
            }
            storage.applySchema(CONTEXT, SCHEMA);
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.createObject("Item", "a", Map.of("name", "Alpha", "amount", 10, "units", 1, "category", "A", "secret", 100,
                        "secretGroup", "first", "tags", List.of("x"), "payload", Map.of("x", 1, "y", List.of(2))));
                var b = new LinkedHashMap<String, Object>(Map.of("name", "Beta", "amount", 20, "units", 2, "category", "A", "secret", 200,
                        "secretGroup", "second", "tags", List.of("x"), "payload", Map.of("y", List.of(2.0), "x", 1.0)));
                b.put("note", null);
                tx.createObject("Item", "b", b);
                var c = new LinkedHashMap<String, Object>(Map.of("name", "Gamma", "category", "B", "note", "present", "tags", List.of()));
                c.put("amount", null);
                tx.createObject("Item", "c", c);
                tx.createObject("Item", "d", Map.of("name", "Delta"));
                tx.commit();
            }
            try { check.run(new Fixture(storage, clock)); }
            finally { if (storage instanceof AutoCloseable resource) resource.close(); }
        });
    }

    private void functions(Fixture f) {
        var result = f.aggregate(query(List.of(field("*", COUNT), field("amount", COUNT), field("amount", SUM),
                field("amount", AVG), field("amount", MIN), field("amount", MAX), field("key", COUNT),
                field("tags", COUNT), field("note", COUNT), field("units", SUM)), List.of()));
        assertEquals(1, result.totalGroups());
        assertEquals(Map.of(), result.groups().getFirst().keys());
        var values = result.groups().getFirst().values();
        assertEquals(4L, values.get("count_*"));
        assertEquals(2L, values.get("count_amount"));
        assertEquals(30.0, values.get("sum_amount"));
        assertEquals(15.0, values.get("avg_amount"));
        assertEquals(10.0, values.get("min_amount"));
        assertEquals(20.0, values.get("max_amount"));
        assertEquals(4L, values.get("count_key"));
        assertEquals(3L, values.get("count_tags"));
        assertEquals(1L, values.get("count_note"));
        assertEquals(3.0, values.get("sum_units"));
        assertThrows(UnsupportedOperationException.class, () -> values.put("sum_amount", 0));
    }

    private void groups(Fixture f) {
        var result = f.aggregate(query(List.of(field("*", COUNT), field("amount", SUM)), List.of("category")));
        assertEquals(3, result.totalGroups());
        assertEquals(Map.of("category", "A"), result.groups().get(0).keys());
        assertEquals(2L, result.groups().get(0).values().get("count_*"));
        assertEquals(30.0, result.groups().get(0).values().get("sum_amount"));
        assertNull(result.groups().get(1).values().get("sum_amount"));
        assertNull(result.groups().get(2).keys().get("category"));
        assertTrue(result.groups().get(2).keys().containsKey("category"));
        var payloads = f.aggregate(query(List.of(field("*", COUNT)), List.of("payload")));
        assertEquals(2, payloads.totalGroups());
        assertEquals(2L, payloads.groups().get(0).values().get("count_*"));
        assertEquals(2L, payloads.groups().get(1).values().get("count_*"));
        var composite = f.aggregate(query(List.of(field("*", COUNT)), List.of("category", "tags")));
        assertEquals(3, composite.totalGroups());
        assertEquals(List.of("x"), composite.groups().getFirst().keys().get("tags"));
        var keys = f.aggregate(query(List.of(field("*", COUNT)), List.of("key")));
        assertEquals(4, keys.totalGroups());
        assertEquals("a", keys.groups().getFirst().keys().get("key"));
    }

    private void pagination(Fixture f) {
        var fields = List.of(new AggregateQuery.Field("amount", SUM, "total"));
        var order = List.of(new AggregateQuery.Order("total", AggregateQuery.Direction.DESC));
        var page = f.aggregate(new AggregateQuery(fields, List.of("category"), Map.of(), order, 1, 1, null, null, false));
        assertEquals(3, page.totalGroups());
        assertEquals("B", page.groups().getFirst().keys().get("category"));
        assertNull(page.groups().getFirst().values().get("total"));
        assertEquals(3, f.aggregate(new AggregateQuery(fields, List.of("category"), Map.of(), order, 0, 0, null, null, false)).totalGroups());
        assertTrue(f.aggregate(new AggregateQuery(fields, List.of("category"), Map.of(), order, 0, 0, null, null, false)).groups().isEmpty());
        assertTrue(f.aggregate(new AggregateQuery(fields, List.of("category"), Map.of(), order, 1, Integer.MAX_VALUE, null, null, false)).groups().isEmpty());
        var descending = f.aggregate(new AggregateQuery(fields, List.of("category"), Map.of(),
                List.of(new AggregateQuery.Order("category", AggregateQuery.Direction.DESC))));
        assertEquals("B", descending.groups().getFirst().keys().get("category"));
        var filtered = f.aggregate(new AggregateQuery(fields, List.of("category"), Map.of("name", Map.of("startsWith", "A")), List.of()));
        assertEquals(1, filtered.totalGroups());
        assertEquals(10.0, filtered.groups().getFirst().values().get("total"));
    }

    private void authorization(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            for (int index = 0; index < 130; index++) {
                String id = "0-hidden-" + index;
                tx.createObject("Item", id, Map.of("name", "Hidden", "amount", 100000, "category", "B"));
                f.denied.add(id);
            }
            tx.commit();
        }
        var query = query(List.of(field("*", COUNT), field("amount", SUM)), List.of("category"));
        var app = f.app(Map.of());
        var groups = app.aggregateObjects(CONTEXT, PRINCIPAL, "Item", query);
        assertEquals(3, groups.totalGroups());
        assertNull(groups.groups().get(1).values().get("sum_amount"));
        assertEquals(2L, groups.groups().getFirst().values().get("count_*"));
        f.denied.add("a");
        assertEquals(20.0, app.aggregateObjects(CONTEXT, PRINCIPAL, "Item", query).groups().getFirst().values().get("sum_amount"));
        f.denied.addAll(List.of("b", "c", "d"));
        assertEquals(0, app.aggregateObjects(CONTEXT, PRINCIPAL, "Item", query).totalGroups());
    }

    private void fieldAuthorization(Fixture f) {
        assertThrows(SecurityException.class, () -> f.aggregate(query(List.of(field("secret", COUNT)), List.of())));
        assertThrows(SecurityException.class, () -> f.aggregate(query(List.of(field("secret", SUM)), List.of())));
        assertThrows(SecurityException.class, () -> f.aggregate(query(List.of(field("*", COUNT)), List.of("secretGroup"))));
        assertThrows(SecurityException.class, () -> f.aggregate(new AggregateQuery(List.of(field("*", COUNT)), List.of(),
                Map.of("OR", List.of(Map.of(), Map.of("secret", Map.of("exists", true)))), List.of())));
        var app = f.app(Map.of("Item", new FieldPolicy(Set.of("name"), Map.of("auditor", Set.of("secret", "secretGroup")))));
        assertThrows(SecurityException.class, () -> app.aggregateObjects(CONTEXT, PRINCIPAL, "Item", query(List.of(field("amount", SUM)), List.of())));
        var auditor = new SecurityPrincipal("reader", "tenant", Set.of("auditor"));
        assertEquals(300.0, app.aggregateObjects(CONTEXT, auditor, "Item", query(List.of(field("secret", SUM)), List.of())).groups().getFirst().values().get("sum_secret"));
        assertEquals(4L, app.aggregateObjects(CONTEXT, PRINCIPAL, "Item", query(List.of(field("key", COUNT)), List.of())).groups().getFirst().values().get("count_key"));
        // A result alias is a label, not a reference to the same-named hidden property.
        var aliasedCount = f.aggregate(new AggregateQuery(List.of(new AggregateQuery.Field("*", COUNT, "secret")), List.of("category"),
                Map.of(), List.of(new AggregateQuery.Order("secret", AggregateQuery.Direction.DESC))));
        assertEquals(2L, aliasedCount.groups().getFirst().values().get("secret"));
        f.denied.addAll(List.of("a", "b", "c", "d"));
        assertThrows(SecurityException.class, () -> f.aggregate(query(List.of(field("secret", COUNT)), List.of())));
    }

    private void empty(Fixture f) {
        var fields = List.of(field("*", COUNT), field("amount", COUNT), field("amount", SUM), field("amount", AVG), field("amount", MIN), field("amount", MAX));
        var filter = Map.<String, Object>of("name", Map.of("eq", "Nobody"));
        var result = f.aggregate(new AggregateQuery(fields, List.of(), filter, List.of()));
        assertEquals(1, result.totalGroups());
        assertEquals(0L, result.groups().getFirst().values().get("count_*"));
        assertEquals(0L, result.groups().getFirst().values().get("count_amount"));
        for (String alias : List.of("sum_amount", "avg_amount", "min_amount", "max_amount")) {
            assertTrue(result.groups().getFirst().values().containsKey(alias));
            assertNull(result.groups().getFirst().values().get(alias));
        }
        assertEquals(0, f.aggregate(new AggregateQuery(fields, List.of("category"), filter, List.of())).totalGroups());
        f.denied.addAll(List.of("a", "b", "c", "d"));
        assertEquals(result, f.aggregate(new AggregateQuery(fields, List.of(), Map.of(), List.of())));
    }

    private void invalid(Fixture f) {
        f.denied.addAll(List.of("a", "b", "c", "d"));
        for (var bad : List.of(query(List.of(field("missing", COUNT)), List.of()), query(List.of(field("name", SUM)), List.of()),
                query(List.of(field("payload", MIN)), List.of()), query(List.of(field("*", COUNT)), List.of("missing")),
                query(List.of(field("amount", SUM), field("amount", SUM)), List.of()),
                query(List.of(new AggregateQuery.Field("amount", SUM, "category")), List.of("category")),
                new AggregateQuery(List.of(field("*", COUNT)), List.of(), Map.of(), List.of(new AggregateQuery.Order("secret", AggregateQuery.Direction.ASC))),
                new AggregateQuery(List.of(field("*", COUNT)), List.of(), Map.of("amount", Map.of("eq", "bad")), List.of()))) {
            assertThrows(IllegalArgumentException.class, () -> f.aggregate(bad), bad.toString());
        }
        assertThrows(IllegalArgumentException.class, () -> query(List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> query(List.of(field("*", COUNT)), List.of("category", "category")));
        assertThrows(IllegalArgumentException.class, () -> field("*", SUM));
        assertThrows(IllegalArgumentException.class, () -> new AggregateQuery.Field("amount", SUM, "x; DROP TABLE"));
        assertThrows(IllegalArgumentException.class, () -> f.app(Map.of()).aggregateObjects(CONTEXT, PRINCIPAL, "Unknown", query(List.of(field("*", COUNT)), List.of())));
        assertThrows(IllegalArgumentException.class, () -> AggregateQuery.fromJson(Map.of("fields", List.of(Map.of("field", "*", "fn", "evil")))));
    }

    private void temporalAndIsolation(Fixture f) {
        Instant before = f.clock.instant();
        f.clock.now = before.plusSeconds(10);
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Item", "a", Map.of("amount", 100), 1);
            tx.deleteObject("Item", "b", 1);
            tx.commit();
        }
        var fields = List.of(field("*", COUNT), field("amount", SUM));
        assertEquals(100.0, f.aggregate(query(fields, List.of())).groups().getFirst().values().get("sum_amount"));
        var past = new AggregateQuery(fields, List.of(), Map.of(), List.of(), 1, 0, before, f.clock.instant(), false);
        assertEquals(30.0, f.aggregate(past).groups().getFirst().values().get("sum_amount"));
        assertEquals(120.0, f.aggregate(new AggregateQuery(fields, List.of(), Map.of(), List.of(), 1, 0, null, null, true)).groups().getFirst().values().get("sum_amount"));
        var other = RequestContext.system("other", "reader");
        f.storage.applySchema(other, SCHEMA);
        try (var tx = f.storage.beginTransaction(other)) {
            tx.createObject("Item", "a", Map.of("name", "Other", "amount", 999));
            tx.commit();
        }
        var app = f.app(Map.of());
        assertEquals(999.0, app.aggregateObjects(other, new SecurityPrincipal("reader", "other", Set.of()), "Item", query(fields, List.of())).groups().getFirst().values().get("sum_amount"));
        assertThrows(SecurityException.class, () -> app.aggregateObjects(other, PRINCIPAL, "Item", query(fields, List.of())));
        assertThrows(SecurityException.class, () -> app.aggregateObjects(CONTEXT, new SecurityPrincipal("intruder", "tenant", Set.of()), "Item", query(fields, List.of())));
    }

    private void numbers(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Item", "a", Map.of("amount", 0.1), 1);
            tx.updateObject("Item", "b", Map.of("amount", 0.2), 1);
            tx.commit();
        }
        assertEquals(0.3, f.aggregate(query(List.of(field("amount", SUM)), List.of())).groups().getFirst().values().get("sum_amount"));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Item", "a", Map.of("amount", Double.MAX_VALUE), 2);
            tx.updateObject("Item", "b", Map.of("amount", Double.MAX_VALUE), 2);
            tx.commit();
        }
        assertEquals(Double.MAX_VALUE, f.aggregate(query(List.of(field("amount", AVG)), List.of())).groups().getFirst().values().get("avg_amount"));
        assertThrows(IllegalArgumentException.class, () -> f.aggregate(query(List.of(field("amount", SUM)), List.of())));
        f.denied.add("b");
        assertEquals(Double.MAX_VALUE, f.aggregate(query(List.of(field("amount", SUM)), List.of())).groups().getFirst().values().get("sum_amount"));
    }

    private void graphql(Fixture f) {
        f.denied.add("b");
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app(Map.of()));
        var result = graph.execute(ExecutionInput.newExecutionInput("""
                query($filter: ItemFilter!) {
                  itemAggregate(filter: $filter, groupBy: ["category"],
                    fields: [{field: "*", fn: COUNT, alias: "objects"}, {field: "amount", fn: SUM}],
                    orderBy: [{field: "objects", direction: DESC}], limit: 1) {
                      groups { keys values } totalGroups
                  }
                }
                """).variables(Map.of("filter", Map.of("name", Map.of("contains", "a"))))
                .graphQLContext(Map.of("request", new ApiRequestContext(CONTEXT, PRINCIPAL))).build());
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        Map<?, ?> data = result.getData();
        var aggregate = (Map<?, ?>) data.get("itemAggregate");
        assertEquals(3, aggregate.get("totalGroups"));
        var groups = (List<?>) aggregate.get("groups");
        assertEquals(1, groups.size());
        assertEquals(Map.of("category", "A"), ((Map<?, ?>) groups.getFirst()).get("keys"));
        assertEquals(Map.of("objects", 1L, "sum_amount", 10.0), ((Map<?, ?>) groups.getFirst()).get("values"));
        var denied = graph.execute(ExecutionInput.newExecutionInput("{ itemAggregate(fields: [{field: \"secret\", fn: SUM}]) { totalGroups } }")
                .graphQLContext(Map.of("request", new ApiRequestContext(CONTEXT, PRINCIPAL))).build());
        assertFalse(denied.getErrors().isEmpty());
        assertNull(denied.getData());
        String sdl = new GraphqlContractGenerator().generate(SCHEMA);
        var parsed = new graphql.schema.idl.SchemaParser().parse(sdl);
        assertTrue(parsed.getType("AggregateResult").isPresent());
        assertTrue(sdl.contains("itemAggregate(filter: ItemFilter"));
    }

    private void http(Fixture f) throws Exception {
        f.denied.add("b");
        try (var server = new JdkRestServer(0, new RestApiRouter(f.app(Map.of())), () -> new ApiRequestContext(CONTEXT, PRINCIPAL));
             var client = HttpClient.newHttpClient()) {
            server.start();
            var response = post(client, server, "{\"fields\":[{\"field\":\"amount\",\"fn\":\"sUm\"}],\"groupBy\":[\"category\"],\"limit\":1}");
            assertEquals(200, response.statusCode(), response.body());
            var body = new ObjectMapper().readTree(response.body());
            assertEquals(3, body.path("totalGroups").asInt());
            assertEquals(10, body.path("groups").get(0).path("values").path("sum_amount").asDouble());
            assertEquals(403, post(client, server, "{\"fields\":[{\"field\":\"secret\",\"fn\":\"count\"}]}").statusCode());
            for (String json : List.of("{}", "{\"fields\":[]}", "{\"fields\":[{\"field\":\"amount\",\"fn\":\"SUM\",\"ignore\":true}]}",
                    "{\"fields\":[{\"field\":\"*\",\"fn\":\"COUNT\"}],\"limit\":-1}",
                    "{\"fields\":[{\"field\":\"*\",\"fn\":\"COUNT\"}],\"asOfValidTime\":\"2026-09-28T00:00:00Z\"}")) {
                assertEquals(400, post(client, server, json).statusCode(), json);
            }
            var past = post(client, server, "{\"fields\":[{\"field\":\"amount\",\"fn\":\"SUM\"}],\"asOfValidTime\":\"2026-09-28T00:00:00Z\",\"asOfRecordedTime\":\"2026-09-28T00:00:00Z\"}");
            assertEquals(200, past.statusCode());
            assertEquals(10, new ObjectMapper().readTree(past.body()).path("groups").get(0).path("values").path("sum_amount").asDouble());
        }
    }

    @Test
    void aggregateGeneratedNamesCannotOverrideDeclaredTypesOrQueries() {
        for (String name : List.of("AggregateFunction", "AggregateFieldInput", "AggregateOrderInput", "AggregateGroup", "AggregateResult", "ItemAggregate")) {
            var schema = new OdlParser().parse("""
                    extend schema @namespace(name: "names", version: "1.0.0")
                    type Item @objectType { id: ID! @primary }
                    type %s @objectType { id: ID! @primary }
                    """.formatted(name));
            assertThrows(IllegalArgumentException.class, () -> new GraphqlContractGenerator().generate(schema), name);
            var app = new ApplicationService(new InMemoryStorageProvider(), new AuthorizationService((p, r, k) -> true), new ActionExecutor(), schema, Map.of(), Map.of());
            assertThrows(IllegalArgumentException.class, () -> GraphqlApiRuntime.create(schema, app), name);
        }
    }

    private static AggregateQuery.Field field(String name, AggregateQuery.Function function) { return new AggregateQuery.Field(name, function); }
    private static AggregateQuery query(List<AggregateQuery.Field> fields, List<String> groups) { return new AggregateQuery(fields, groups, Map.of(), List.of()); }

    private static HttpResponse<String> post(HttpClient client, JdkRestServer server, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/v1/Item/aggregate"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static class Fixture {
        final StorageProvider storage;
        final TestClock clock;
        final Set<String> denied = new HashSet<>();
        Fixture(StorageProvider storage, TestClock clock) { this.storage = storage; this.clock = clock; }
        ApplicationService app(Map<String, FieldPolicy> policies) {
            return new ApplicationService(storage, new AuthorizationService((p, r, key) -> !denied.contains(key.id())), new ActionExecutor(), SCHEMA, Map.of(), policies);
        }
        AggregateResult aggregate(AggregateQuery query) { return app(Map.of()).aggregateObjects(CONTEXT, PRINCIPAL, "Item", query); }
    }
    @FunctionalInterface private interface Check { void run(Fixture fixture) throws Exception; }
    private static class TestClock extends Clock {
        Instant now = Instant.parse("2026-09-28T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
