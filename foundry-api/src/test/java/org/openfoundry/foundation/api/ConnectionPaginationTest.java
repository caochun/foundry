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

class ConnectionPaginationTest {
    private static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name: "pages", version: "1.0.0")
            type Item @objectType { key: ID! @primary rank: Int! secret: String @sensitive }
            """);
    private static final RequestContext CONTEXT = RequestContext.system("tenant", "reader");
    private static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("reader", "tenant", Set.of());

    @TestFactory
    Stream<DynamicTest> connectionsAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "forward pages and full visible count", this::forward),
                test(provider, "standalone last and strict before bounds", this::backward),
                test(provider, "both cursor bounds and zero-sized windows", this::rangesAndZero),
                test(provider, "default and maximum page sizes", this::limits),
                test(provider, "forward and backward walks cover each visible object once", this::walk),
                test(provider, "filter sorting permissions and tenant identity precede slicing", this::governance),
                test(provider, "backward historical queries preserve the selected state", this::temporal),
                test(provider, "GraphQL defaults to upstream connections and explicit legacy lists", this::graphql),
                test(provider, "REST supports reverse and zero-sized queries", this::http)));
    }

    private DynamicTest test(String provider, String label, Check check) {
        return DynamicTest.dynamicTest(provider + " " + label, () -> {
            var clock = new TestClock();
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider(clock);
            else {
                var data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:connections_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
            }
            storage.applySchema(CONTEXT, SCHEMA);
            try (var tx = storage.beginTransaction(CONTEXT)) {
                for (int index = 0; index < 7; index++) tx.createObject("Item", "r" + index, Map.of("rank", index, "secret", "hidden"));
                tx.commit();
            }
            try { check.run(new Fixture(storage, clock)); }
            finally { if (storage instanceof AutoCloseable resource) resource.close(); }
        });
    }

    private void forward(Fixture f) {
        var first = f.page(new ConnectionPage(3, null, null, null, 0));
        assertEquals(List.of("r0", "r1", "r2"), ids(first));
        assertEquals(7, first.totalCount());
        assertFalse(first.pageInfo().hasPreviousPage());
        assertTrue(first.pageInfo().hasNextPage());
        var next = f.page(new ConnectionPage(3, first.pageInfo().endCursor(), null, null, 0));
        assertEquals(List.of("r3", "r4", "r5"), ids(next));
        assertTrue(next.pageInfo().hasPreviousPage());
        assertTrue(next.pageInfo().hasNextPage());
        var last = f.page(new ConnectionPage(3, next.pageInfo().endCursor(), null, null, 0));
        assertEquals(List.of("r6"), ids(last));
        assertFalse(last.pageInfo().hasNextPage());
        var beyond = f.page(new ConnectionPage(3, cursor(1000), null, null, 0));
        assertTrue(beyond.edges().isEmpty());
        assertEquals(7, beyond.totalCount());
        assertNull(beyond.pageInfo().endCursor());
        assertFalse(beyond.pageInfo().hasNextPage());
        assertEquals(List.of("r3", "r4"), ids(f.page(new ConnectionPage(2, null, null, null, 3))));
    }

    private void backward(Fixture f) {
        var tail = f.page(new ConnectionPage(null, null, 3, null, 0));
        assertEquals(List.of("r4", "r5", "r6"), ids(tail));
        assertTrue(tail.pageInfo().hasPreviousPage());
        assertFalse(tail.pageInfo().hasNextPage());
        var previous = f.page(new ConnectionPage(null, null, 3, tail.pageInfo().startCursor(), 0));
        assertEquals(List.of("r1", "r2", "r3"), ids(previous));
        assertTrue(previous.pageInfo().hasPreviousPage());
        assertTrue(previous.pageInfo().hasNextPage());
        var first = f.page(new ConnectionPage(null, null, 3, previous.pageInfo().startCursor(), 0));
        assertEquals(List.of("r0"), ids(first));
        assertFalse(first.pageInfo().hasPreviousPage());
        assertTrue(first.pageInfo().hasNextPage());
        assertTrue(f.page(new ConnectionPage(null, null, 3, first.pageInfo().startCursor(), 0)).edges().isEmpty());
        // A before-only request defaults backwards and never includes or passes the cursor row.
        assertEquals(List.of("r0", "r1", "r2", "r3"), ids(f.page(new ConnectionPage(null, null, null, cursor(4), 0))));
        assertEquals(List.of("r5", "r6"), ids(f.page(new ConnectionPage(null, null, 2, cursor(1000), 0))));
    }

    private void rangesAndZero(Fixture f) {
        assertEquals(List.of("r2", "r3"), ids(f.page(new ConnectionPage(2, cursor(1), null, cursor(6), 0))));
        assertEquals(List.of("r4", "r5"), ids(f.page(new ConnectionPage(null, cursor(1), 2, cursor(6), 0))));
        assertEquals(List.of("r2", "r3", "r4", "r5"), ids(f.page(new ConnectionPage(null, cursor(1), null, cursor(6), 0))));
        assertTrue(f.page(new ConnectionPage(2, cursor(5), null, cursor(1), 0)).edges().isEmpty());
        assertTrue(f.page(new ConnectionPage(null, cursor(2), 2, cursor(2), 0)).edges().isEmpty());
        var zeroFirst = f.page(new ConnectionPage(0, null, null, null, 0));
        assertEquals(7, zeroFirst.totalCount());
        assertTrue(zeroFirst.edges().isEmpty());
        assertTrue(zeroFirst.pageInfo().hasNextPage());
        assertFalse(zeroFirst.pageInfo().hasPreviousPage());
        assertNull(zeroFirst.pageInfo().startCursor());
        var zeroLast = f.page(new ConnectionPage(null, null, 0, null, 0));
        assertEquals(7, zeroLast.totalCount());
        assertTrue(zeroLast.edges().isEmpty());
        assertFalse(zeroLast.pageInfo().hasNextPage());
        assertTrue(zeroLast.pageInfo().hasPreviousPage());
        var zeroBefore = f.page(new ConnectionPage(null, null, 0, cursor(3), 0));
        assertTrue(zeroBefore.pageInfo().hasNextPage());
        assertTrue(zeroBefore.pageInfo().hasPreviousPage());
        f.denied.addAll(List.of("r0", "r1", "r2", "r3", "r4", "r5", "r6"));
        var empty = f.page(new ConnectionPage(null, null, 10, null, 0));
        assertEquals(0, empty.totalCount());
        assertFalse(empty.pageInfo().hasPreviousPage());
        assertFalse(empty.pageInfo().hasNextPage());
    }

    private void limits(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            for (int index = 0; index < 120; index++) tx.createObject("Item", "z%03d".formatted(index), Map.of("rank", index));
            tx.commit();
        }
        assertEquals(20, f.page(ConnectionPage.defaults()).edges().size());
        var first = f.page(new ConnectionPage(Integer.MAX_VALUE, null, null, null, 0));
        assertEquals(100, first.edges().size());
        assertEquals(127, first.totalCount());
        var last = f.page(new ConnectionPage(null, null, Integer.MAX_VALUE, null, 0));
        assertEquals(100, last.edges().size());
        assertFalse(last.pageInfo().hasNextPage());
        assertTrue(last.pageInfo().hasPreviousPage());
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app());
        var query = graph.execute(ExecutionInput.newExecutionInput("{items{edges{cursor} totalCount}}")
                .graphQLContext(Map.of("request", new ApiRequestContext(CONTEXT, PRINCIPAL))).build());
        assertTrue(query.getErrors().isEmpty(), query.getErrors().toString());
        Map<?, ?> data = query.getData();
        assertEquals(20, ((List<?>) ((Map<?, ?>) data.get("items")).get("edges")).size());
    }

    private void walk(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            for (int index = 0; index < 130; index++) {
                String id = "hidden-%03d".formatted(index);
                tx.createObject("Item", id, Map.of("rank", index));
                f.denied.add(id);
            }
            for (int index = 0; index < 44; index++) tx.createObject("Item", "z%03d".formatted(index), Map.of("rank", index));
            tx.commit();
        }
        var expected = new ArrayList<String>();
        for (int index = 0; index < 7; index++) expected.add("r" + index);
        for (int index = 0; index < 44; index++) expected.add("z%03d".formatted(index));
        var forward = new ArrayList<String>();
        String after = null;
        for (int page = 0; page < 100; page++) {
            var result = f.page(new ConnectionPage(6, after, null, null, 0));
            assertEquals(51, result.totalCount());
            forward.addAll(ids(result));
            if (!result.pageInfo().hasNextPage()) break;
            after = result.pageInfo().endCursor();
            assertNotNull(after);
        }
        var reverse = new ArrayList<String>();
        String before = null;
        for (int page = 0; page < 100; page++) {
            var result = f.page(new ConnectionPage(null, null, 6, before, 0));
            assertEquals(51, result.totalCount());
            reverse.addAll(0, ids(result));
            if (!result.pageInfo().hasPreviousPage()) break;
            before = result.pageInfo().startCursor();
            assertNotNull(before);
        }
        assertEquals(expected, forward);
        assertEquals(expected, reverse);
    }

    private void governance(Fixture f) {
        f.denied.add("r5");
        var page = new ConnectionPage(null, null, 2, null, 0);
        var query = new ObjectConnectionQuery(Map.of("rank", Map.of("gte", 2)), Map.of("rank", "DESC"), page);
        var result = f.app().queryConnection(CONTEXT, PRINCIPAL, "Item", query);
        assertEquals(List.of("r3", "r2"), ids(result));
        assertEquals(4, result.totalCount());
        assertTrue(result.edges().stream().noneMatch(edge -> edge.node().properties().containsKey("secret")));
        assertThrows(SecurityException.class, () -> f.app().queryConnection(CONTEXT, PRINCIPAL, "Item",
                new ObjectConnectionQuery(Map.of("secret", Map.of("eq", "hidden")), Map.of(), new ConnectionPage(0, null, null, null, 0))));
        assertThrows(SecurityException.class, () -> f.app().queryConnection(CONTEXT, PRINCIPAL, "Item",
                new ObjectConnectionQuery(Map.of(), Map.of("secret", "ASC"), page)));
        var other = RequestContext.system("other", "reader");
        f.storage.applySchema(other, SCHEMA);
        try (var tx = f.storage.beginTransaction(other)) { tx.createObject("Item", "r0", Map.of("rank", 99)); tx.commit(); }
        assertThrows(SecurityException.class, () -> f.app().queryConnection(other, PRINCIPAL, "Item", query));
        assertThrows(SecurityException.class, () -> f.app().queryConnection(CONTEXT, new SecurityPrincipal("imposter", "tenant", Set.of()), "Item", query));
        var otherPage = f.app().queryConnection(other, new SecurityPrincipal("reader", "other", Set.of()), "Item", query);
        assertEquals(List.of("r0"), ids(otherPage));
        assertEquals(99, otherPage.edges().getFirst().node().properties().get("rank"));
        var saved = f.page(new ConnectionPage(null, null, 3, null, 0)).pageInfo().startCursor();
        f.denied.add("r6");
        assertFalse(ids(f.page(new ConnectionPage(10, saved, null, null, 0))).contains("r6"));
    }

    private void temporal(Fixture f) {
        Instant old = f.clock.instant();
        f.clock.now = old.plusSeconds(10);
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Item", "r6", Map.of("rank", -1), 1);
            tx.deleteObject("Item", "r5", 1);
            tx.commit();
        }
        var page = new ConnectionPage(null, null, 2, null, 0);
        var past = new ObjectConnectionQuery(Map.of(), Map.of("rank", "ASC"), page, old, f.clock.instant(), false);
        assertEquals(List.of("r5", "r6"), ids(f.app().queryConnection(CONTEXT, PRINCIPAL, "Item", past)));
        var current = new ObjectConnectionQuery(Map.of(), Map.of("rank", "ASC"), page);
        assertEquals(List.of("r3", "r4"), ids(f.app().queryConnection(CONTEXT, PRINCIPAL, "Item", current)));
        var deleted = new ObjectConnectionQuery(Map.of(), Map.of("rank", "ASC"), page, null, null, true);
        assertEquals(List.of("r4", "r5"), ids(f.app().queryConnection(CONTEXT, PRINCIPAL, "Item", deleted)));
    }

    private void graphql(Fixture f) {
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app());
        var context = Map.<String, Object>of("request", new ApiRequestContext(CONTEXT, PRINCIPAL));
        var result = graph.execute(ExecutionInput.newExecutionInput("""
                { items(last: 2) { edges { node { key } cursor } totalCount pageInfo { hasPreviousPage hasNextPage } }
                  alias: itemsConnection(last: 2) { edges { node { key } cursor } totalCount pageInfo { hasPreviousPage hasNextPage } } }
                """).graphQLContext(context).build());
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        Map<?, ?> data = result.getData();
        assertEquals(data.get("items"), data.get("alias"));
        var connection = (Map<?, ?>) data.get("items");
        assertEquals(7, connection.get("totalCount"));
        assertTrue(connection.toString().contains("key=r5"));
        assertTrue(connection.toString().contains("key=r6"));
        var previous = graph.execute(ExecutionInput.newExecutionInput("query($before:String!){items(last:3,before:$before){edges{node{key}}}}")
                .variables(Map.of("before", cursor(1))).graphQLContext(context).build());
        assertTrue(previous.getErrors().isEmpty(), previous.getErrors().toString());
        assertEquals(Map.of("items", Map.of("edges", List.of(Map.of("node", Map.of("key", "r0"))))), previous.getData());
        var zero = graph.execute(ExecutionInput.newExecutionInput("{items(first:0){edges{cursor} totalCount}}")
                .graphQLContext(context).build());
        assertTrue(zero.getErrors().isEmpty(), zero.getErrors().toString());
        assertEquals(Map.of("items", Map.of("edges", List.of(), "totalCount", 7)), zero.getData());
        for (String invalid : List.of("{items(first:1,last:1){totalCount}}", "{items(before:\"invalid\"){totalCount}}", "{items(first:-1){totalCount}}")) {
            assertFalse(graph.execute(ExecutionInput.newExecutionInput(invalid).graphQLContext(context).build()).getErrors().isEmpty());
        }
        assertFalse(graph.execute(ExecutionInput.newExecutionInput("{items{key}}").graphQLContext(context).build()).getErrors().isEmpty());
        for (var actionMode : GraphqlApiRuntime.ActionMode.values()) {
            var legacy = GraphqlApiRuntime.create(SCHEMA, f.app(), Map.of(), actionMode, GraphqlApiRuntime.QueryMode.LEGACY_LIST);
            var old = legacy.execute(ExecutionInput.newExecutionInput("{items(first:2){key}} ").graphQLContext(context).build());
            assertTrue(old.getErrors().isEmpty(), old.getErrors().toString());
            assertEquals(Map.of("items", List.of(Map.of("key", "r0"), Map.of("key", "r1"))), old.getData());
        }
        var fullLegacy = GraphqlApiRuntime.createLegacy(SCHEMA, f.app(), Map.of());
        assertTrue(fullLegacy.execute(ExecutionInput.newExecutionInput("{items{key}}").graphQLContext(context).build()).getErrors().isEmpty());
        assertEquals("ItemConnection!", graphql.schema.GraphQLTypeUtil.simplePrint(graph.getGraphQLSchema().getQueryType().getFieldDefinition("items").getType()));
        var generated = new GraphqlContractGenerator().generate(SCHEMA);
        var parsed = new graphql.schema.idl.SchemaParser().parse(generated);
        var queryType = (graphql.language.ObjectTypeDefinition) parsed.getType("Query").orElseThrow();
        var items = queryType.getFieldDefinitions().stream().filter(field -> field.getName().equals("items")).findFirst().orElseThrow();
        assertEquals("ItemConnection!", graphql.language.AstPrinter.printAstCompact(items.getType()));
        assertTrue(items.getInputValueDefinitions().stream().anyMatch(arg -> arg.getName().equals("before")));
        assertTrue(new GraphqlContractGenerator().generate(SCHEMA, GraphqlApiRuntime.ActionMode.TYPED, GraphqlApiRuntime.QueryMode.LEGACY_LIST)
                .contains("first: Int = 100, offset: Int = 0): [Item!]!"));
    }

    private void http(Fixture f) throws Exception {
        try (var server = new JdkRestServer(0, new RestApiRouter(f.app()), () -> new ApiRequestContext(CONTEXT, PRINCIPAL));
             var client = HttpClient.newHttpClient()) {
            server.start();
            var result = post(client, server, "{\"last\":3,\"before\":\"" + cursor(1) + "\"}");
            assertEquals(200, result.statusCode(), result.body());
            var data = new ObjectMapper().readTree(result.body());
            assertEquals(7, data.path("totalCount").asInt());
            assertEquals(1, data.path("edges").size());
            assertEquals("r0", data.path("edges").get(0).path("node").path("id").asText());
            assertEquals(0, new ObjectMapper().readTree(post(client, server, "{\"first\":0}").body()).path("edges").size());
            assertEquals(2, new ObjectMapper().readTree(post(client, server, "{\"first\":null,\"last\":2}").body()).path("edges").size());
            for (String input : List.of("{\"first\":1,\"last\":1}", "{\"last\":2,\"offset\":1}", "{\"before\":\"bad\"}", "{\"last\":-1}", "{\"last\":1.5}")) {
                assertEquals(400, post(client, server, input).statusCode(), input);
            }
            assertEquals(403, post(client, server, "{\"first\":0,\"filter\":{\"secret\":{\"exists\":true}}}").statusCode());
        }
    }

    @Test
    void paginationRejectsAmbiguousOrMalformedInputsBeforeReading() {
        assertThrows(IllegalArgumentException.class, () -> new ConnectionPage(1, null, 1, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionPage(-1, null, null, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionPage(null, null, -1, null, 0));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionPage(null, null, 1, null, 1));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionPage(null, null, null, cursor(1), 1));
        assertThrows(IllegalArgumentException.class, () -> new ConnectionPage(null, cursor(1), null, null, 1));
        for (String cursor : List.of("", "bad", "Y3Vyc29yOi0x", "Y3Vyc29yOjIxNDc0ODM2NDc=")) {
            assertThrows(IllegalArgumentException.class, () -> new ConnectionPage(null, null, 1, cursor, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> ObjectConnectionQuery.fromJson(Map.of("last", "2")));
        assertThrows(IllegalArgumentException.class, () -> ObjectConnectionQuery.fromJson(Map.of("unsupported", true)));
    }

    private static String cursor(int index) { return ObjectQueryResult.cursor(index); }
    private static List<String> ids(ObjectQueryResult.Connection connection) { return connection.edges().stream().map(edge -> edge.node().id()).toList(); }
    private static HttpResponse<String> post(HttpClient client, JdkRestServer server, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/v1/Item/query"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static class Fixture {
        final StorageProvider storage;
        final TestClock clock;
        final Set<String> denied = new HashSet<>();
        Fixture(StorageProvider storage, TestClock clock) { this.storage = storage; this.clock = clock; }
        ApplicationService app() {
            return new ApplicationService(storage, new AuthorizationService((p, r, key) -> !denied.contains(key.id())), new ActionExecutor(), SCHEMA, Map.of(), Map.of());
        }
        ObjectQueryResult.Connection page(ConnectionPage page) {
            return app().queryConnection(CONTEXT, PRINCIPAL, "Item", new ObjectConnectionQuery(Map.of(), Map.of(), page));
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
