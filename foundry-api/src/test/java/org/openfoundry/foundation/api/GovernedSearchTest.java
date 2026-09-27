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

class GovernedSearchTest {
    private static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name: "search", version: "1.0.0")
            enum Category { NEWS GUIDE }
            type Article @objectType {
                key: ID! @primary title: String! body: String secret: String @sensitive
                category: Category uri: URI date: Date amount: Float tags: [String!] payload: JSON
            }
            """);
    private static final RequestContext CONTEXT = RequestContext.system("tenant", "reader");
    private static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("reader", "tenant", Set.of());

    @TestFactory
    Stream<DynamicTest> searchesAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "both upstream modes have explicit deterministic scores", this::modes),
                test(provider, "Chinese Unicode and wildcard-like characters match literally", this::text),
                test(provider, "default search scores and highlights exclude hidden fields", this::visibility),
                test(provider, "explicit fields role policies and empty selections", this::fields),
                test(provider, "authorized counts and paging cross hidden rows", this::pagination),
                test(provider, "filters and validation run before searching", this::validation),
                test(provider, "temporal state and deletion determine matching text", this::temporal),
                test(provider, "tenant actor and revocation boundaries", this::isolation),
                test(provider, "GraphQL search fields cursors and schema", this::graphql),
                test(provider, "GET and POST HTTP search routes", this::http)));
    }

    private DynamicTest test(String provider, String label, Check check) {
        return DynamicTest.dynamicTest(provider + " " + label, () -> {
            var clock = new TestClock();
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider(clock);
            else {
                var data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:search_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
            }
            storage.applySchema(CONTEXT, SCHEMA);
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.createObject("Article", "a", Map.of("title", "Alpha alpha river", "body", "calm blue", "secret", "classified needle needle", "category", "NEWS"));
                tx.createObject("Article", "b", Map.of("title", "Alpha river", "body", "alpha river", "secret", "alpha alpha alpha alpha", "category", "GUIDE"));
                tx.createObject("Article", "c", Map.of("title", "Blue meadow", "body", "wide sky", "secret", "ALPHA alpha alpha", "category", "NEWS"));
                tx.createObject("Article", "d", Map.of("title", "Markup <b>blue</b> 50%_\\ path", "body", "政务对象库 关系历史"));
                tx.commit();
            }
            try { check.run(new Fixture(storage, clock)); }
            finally { if (storage instanceof AutoCloseable resource) resource.close(); }
        });
    }

    private void modes(Fixture f) {
        var terms = f.search(query("ALPHA river", null));
        assertEquals(List.of("b", "a"), ids(terms));
        assertEquals(List.of(4.0, 3.0), terms.hits().stream().map(SearchResult.Hit::score).toList());
        assertEquals(2, terms.totalCount());
        var phrase = f.search(mode("alpha river", SearchQuery.Mode.PHRASE));
        assertEquals(List.of("b", "a"), ids(phrase));
        assertEquals(List.of(2.0, 1.0), phrase.hits().stream().map(SearchResult.Hit::score).toList());
        assertEquals(0, f.search(mode("river alpha", SearchQuery.Mode.PHRASE)).totalCount());
        assertEquals(2, f.search(query("river alpha", null)).totalCount());
        assertEquals(List.of("a", "b"), ids(f.search(query("alpha", null))));
        assertEquals(List.of(4.0, 4.0), f.search(query("alpha alpha", null)).hits().stream().map(SearchResult.Hit::score).toList());
        assertEquals(List.of("Alpha river"), terms.hits().getFirst().highlights().get("title"));
        assertThrows(UnsupportedOperationException.class, () -> terms.hits().getFirst().highlights().put("secret", List.of("x")));
        try (var tx = f.storage.beginTransaction(CONTEXT)) { tx.createObject("Article", "e", Map.of("title", "banana")); tx.commit(); }
        assertEquals(1.0, f.search(query("ana", List.of("title"))).hits().getFirst().score());
    }

    private void text(Fixture f) {
        assertEquals(List.of("d"), ids(f.search(query("政务 关系", null))));
        assertEquals(2.0, f.search(query("政务 关系", null)).hits().getFirst().score());
        assertEquals(List.of("d"), ids(f.search(query("%_\\", null))));
        assertEquals(List.of("d"), ids(f.search(query("<b>blue</b>", null))));
        assertEquals(List.of("b", "a"), ids(f.search(query("alpha\u3000river", null))));
        assertEquals(0, f.search(query(".*", null)).totalCount());
        try (var tx = f.storage.beginTransaction(CONTEXT)) { tx.createObject("Article", "e", Map.of("title", "INDIGO ÉCLAIR")); tx.commit(); }
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(List.of("e"), ids(f.search(query("indigo éclair", null))));
        } finally { Locale.setDefault(previous); }
    }

    private void visibility(Fixture f) {
        assertEquals(0, f.search(query("needle", null)).totalCount());
        var before = f.search(query("alpha", null));
        assertEquals(List.of("a", "b"), ids(before));
        assertEquals(List.of(2.0, 2.0), before.hits().stream().map(SearchResult.Hit::score).toList());
        assertTrue(before.hits().stream().noneMatch(hit -> hit.node().properties().containsKey("secret") || hit.highlights().containsKey("secret")));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Article", "a", Map.of("secret", "alpha ".repeat(1000)), 1);
            tx.commit();
        }
        assertEquals(List.of(2.0, 2.0), f.search(query("alpha", null)).hits().stream().map(SearchResult.Hit::score).toList());
        var app = f.app(Map.of("Article", new FieldPolicy(Set.of("body"), Map.of("auditor", Set.of("secret")))));
        var body = app.searchObjects(CONTEXT, PRINCIPAL, "Article", query("alpha", null));
        assertEquals(List.of("b"), ids(body));
        assertEquals(1.0, body.hits().getFirst().score());
        assertEquals(Map.of("body", List.of("alpha river")), body.hits().getFirst().highlights());
        var none = f.app(Map.of("Article", new FieldPolicy(Set.of(), Map.of()))).searchObjects(CONTEXT, PRINCIPAL, "Article", query("alpha", null));
        assertTrue(none.hits().isEmpty());
        assertEquals(0, none.totalCount());
    }

    private void fields(Fixture f) {
        assertThrows(SecurityException.class, () -> f.search(query("alpha", List.of("secret"))));
        var app = f.app(Map.of("Article", new FieldPolicy(Set.of("title"), Map.of("auditor", Set.of("secret")))));
        var auditor = new SecurityPrincipal("reader", "tenant", Set.of("auditor"));
        var secret = app.searchObjects(CONTEXT, auditor, "Article", query("alpha", List.of("secret")));
        assertEquals(List.of("b", "c"), ids(secret));
        assertEquals(4.0, secret.hits().getFirst().score());
        assertTrue(secret.hits().getFirst().highlights().containsKey("secret"));
        assertEquals(0, app.searchObjects(CONTEXT, auditor, "Article", query("alpha", List.of())).totalCount());
        assertEquals(List.of("a", "c"), ids(f.search(query("NEWS", List.of("category")))));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.createObject("Article", "id-only-needle", Map.of("title", "Plain", "payload", "json-only-token", "uri", "https://example.org/reference", "date", "2026-09-28"));
            tx.commit();
        }
        assertEquals(0, f.search(query("id-only-needle", null)).totalCount());
        assertEquals(List.of("id-only-needle"), ids(f.search(query("id-only-needle", List.of("key")))));
        assertEquals(0, f.search(query("json-only-token", null)).totalCount());
        assertEquals(List.of("id-only-needle"), ids(f.search(query("reference", List.of("uri")))));
        assertEquals(List.of("id-only-needle"), ids(f.search(query("2026-09", List.of("date")))));
    }

    private void pagination(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            for (int index = 0; index < 130; index++) {
                String id = "0-hidden-" + index;
                tx.createObject("Article", id, Map.of("title", "alpha ".repeat(20)));
                f.denied.add(id);
            }
            tx.commit();
        }
        var first = f.search(page("alpha", 1, 0));
        assertEquals(List.of("a"), ids(first));
        assertEquals(2, first.totalCount());
        assertTrue(first.hasNextPage());
        int after = ObjectQueryResult.offsetAfter(first.hits().getFirst().cursor());
        assertEquals(1, after);
        var next = f.search(page("alpha", 1, after));
        assertEquals(List.of("b"), ids(next));
        assertFalse(next.hasNextPage());
        var beyond = f.search(page("alpha", 1, Integer.MAX_VALUE));
        assertEquals(2, beyond.totalCount());
        assertTrue(beyond.hits().isEmpty());
        assertFalse(beyond.hasNextPage());
        var zero = f.search(page("alpha", 0, 0));
        assertEquals(2, zero.totalCount());
        assertTrue(zero.hits().isEmpty());
        assertTrue(zero.hasNextPage());
    }

    private void validation(Fixture f) {
        var filtered = f.search(new SearchQuery("alpha", null, Map.of("category", Map.of("eq", "NEWS"))));
        assertEquals(List.of("a"), ids(filtered));
        for (String field : List.of("missing", "payload", "amount", "tags", "_tenantId")) {
            assertThrows(IllegalArgumentException.class, () -> f.search(query("alpha", List.of(field))));
        }
        assertThrows(SecurityException.class, () -> f.search(new SearchQuery("alpha", List.of(), Map.of("secret", Map.of("eq", "x")))));
        assertThrows(IllegalArgumentException.class, () -> f.search(new SearchQuery("alpha", null, Map.of("amount", Map.of("eq", "bad")))));
        f.denied.addAll(List.of("a", "b", "c", "d"));
        assertThrows(SecurityException.class, () -> f.search(query("alpha", List.of("secret"))));
        assertThrows(IllegalArgumentException.class, () -> f.search(query("alpha", List.of("missing"))));
        assertEquals(0, f.search(query("alpha", null)).totalCount());
        assertThrows(IllegalArgumentException.class, () -> f.app(Map.of()).searchObjects(CONTEXT, PRINCIPAL, "Missing", query("alpha", null)));
        var legacy = new ApplicationService(f.storage, new AuthorizationService((p, r, k) -> true), new ActionExecutor());
        assertThrows(IllegalArgumentException.class, () -> legacy.searchObjects(CONTEXT, PRINCIPAL, "Article", query("alpha", null)));
    }

    private void temporal(Fixture f) {
        Instant old = f.clock.instant();
        f.clock.now = old.plusSeconds(10);
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Article", "a", Map.of("title", "Replaced"), 1);
            tx.deleteObject("Article", "b", 1);
            tx.commit();
        }
        assertEquals(0, f.search(query("alpha", null)).totalCount());
        var past = f.search(new SearchQuery("alpha", null, Map.of(), SearchQuery.Mode.TERMS, 20, 0, old, f.clock.instant(), false));
        assertEquals(List.of("a", "b"), ids(past));
        assertEquals("Alpha alpha river", past.hits().getFirst().node().properties().get("title"));
        assertEquals(List.of("Alpha alpha river"), past.hits().getFirst().highlights().get("title"));
        var deleted = f.search(new SearchQuery("alpha", null, Map.of(), SearchQuery.Mode.TERMS, 20, 0, null, null, true));
        assertEquals(List.of("b"), ids(deleted));
        assertTrue(deleted.hits().getFirst().node().isDeleted());
        f.denied.add("b");
        assertEquals(1, f.search(new SearchQuery("alpha", null, Map.of(), SearchQuery.Mode.TERMS, 20, 0, old, f.clock.instant(), false)).totalCount());
    }

    private void isolation(Fixture f) {
        var other = RequestContext.system("other", "reader");
        f.storage.applySchema(other, SCHEMA);
        try (var tx = f.storage.beginTransaction(other)) { tx.createObject("Article", "a", Map.of("title", "alpha alpha alpha alpha")); tx.commit(); }
        var app = f.app(Map.of());
        assertEquals(2, app.searchObjects(CONTEXT, PRINCIPAL, "Article", query("alpha", null)).totalCount());
        assertEquals(1, app.searchObjects(other, new SecurityPrincipal("reader", "other", Set.of()), "Article", query("alpha", null)).totalCount());
        assertThrows(SecurityException.class, () -> app.searchObjects(other, PRINCIPAL, "Article", query("alpha", null)));
        assertThrows(SecurityException.class, () -> app.searchObjects(CONTEXT, new SecurityPrincipal("intruder", "tenant", Set.of()), "Article", query("alpha", null)));
        f.denied.add("a");
        assertEquals(List.of("b"), ids(app.searchObjects(CONTEXT, PRINCIPAL, "Article", query("alpha", null))));
    }

    private void graphql(Fixture f) {
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app(Map.of()));
        var context = Map.<String, Object>of("request", new ApiRequestContext(CONTEXT, PRINCIPAL));
        var result = graph.execute(ExecutionInput.newExecutionInput("""
                { searchArticles(query: "alpha", first: 1) {
                    hits { node { key title secret } score highlights cursor } totalCount hasNextPage
                } }
                """).graphQLContext(context).build());
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        Map<?, ?> data = result.getData();
        var search = (Map<?, ?>) data.get("searchArticles");
        assertEquals(2, search.get("totalCount"));
        assertEquals(true, search.get("hasNextPage"));
        var hit = (Map<?, ?>) ((List<?>) search.get("hits")).getFirst();
        assertEquals(2.0, hit.get("score"));
        assertNull(((Map<?, ?>) hit.get("node")).get("secret"));
        assertFalse(((Map<?, ?>) hit.get("highlights")).containsKey("secret"));
        var after = graph.execute(ExecutionInput.newExecutionInput("query($after:String!){searchArticles(query:\"alpha\",first:1,after:$after){hits{node{key}} totalCount hasNextPage}}")
                .variables(Map.of("after", hit.get("cursor"))).graphQLContext(context).build());
        assertTrue(after.getErrors().isEmpty(), after.getErrors().toString());
        assertTrue(after.getData().toString().contains("key=b"));
        var phrase = graph.execute(ExecutionInput.newExecutionInput("{searchArticles(query:\"river alpha\",mode:PHRASE){totalCount}}")
                .graphQLContext(context).build());
        assertTrue(phrase.getErrors().isEmpty(), phrase.getErrors().toString());
        assertEquals(Map.of("searchArticles", Map.of("totalCount", 0)), phrase.getData());
        var filtered = graph.execute(ExecutionInput.newExecutionInput("{searchArticles(query:\"alpha\",filter:{category:{eq:NEWS}},first:0){hits{score}totalCount hasNextPage}}")
                .graphQLContext(context).build());
        assertTrue(filtered.getErrors().isEmpty(), filtered.getErrors().toString());
        assertEquals(Map.of("searchArticles", Map.of("hits", List.of(), "totalCount", 1, "hasNextPage", true)), filtered.getData());
        for (String bad : List.of("{searchArticles(query:\"alpha\",fields:[\"secret\"]){totalCount}}", "{searchArticles(query:\"alpha\",after:\"bad\"){totalCount}}")) {
            var denied = graph.execute(ExecutionInput.newExecutionInput(bad).graphQLContext(context).build());
            assertFalse(denied.getErrors().isEmpty());
            assertNull(denied.getData());
        }
        String sdl = new GraphqlContractGenerator().generate(SCHEMA);
        assertTrue(new graphql.schema.idl.SchemaParser().parse(sdl).getType("SearchResult_Article").isPresent());
        assertTrue(sdl.contains("searchArticles(query: String!"));
    }

    private void http(Fixture f) throws Exception {
        try (var server = new JdkRestServer(0, new RestApiRouter(f.app(Map.of())), () -> new ApiRequestContext(CONTEXT, PRINCIPAL));
             var client = HttpClient.newHttpClient()) {
            server.start();
            var get = get(client, server, "?q=blue&limit=1&offset=1");
            assertEquals(200, get.statusCode(), get.body());
            var body = new ObjectMapper().readTree(get.body());
            assertEquals(3, body.path("totalCount").asInt());
            assertEquals("c", body.path("hits").get(0).path("node").path("id").asText());
            assertFalse(get.body().contains("secret"));
            var post = post(client, server, "{\"query\":\"blue\",\"filter\":{\"category\":{\"eq\":\"NEWS\"}},\"limit\":1}");
            assertEquals(200, post.statusCode());
            assertEquals(2, new ObjectMapper().readTree(post.body()).path("totalCount").asInt());
            assertEquals(1, new ObjectMapper().readTree(get(client, server, "?q=%25_%5C").body()).path("totalCount").asInt());
            assertEquals(0, new ObjectMapper().readTree(get(client, server, "?q=alpha&fields=").body()).path("totalCount").asInt());
            assertEquals(403, get(client, server, "?q=alpha&fields=secret").statusCode());
            for (String query : List.of("", "?q=", "?q=alpha&q=beta", "?q=alpha&mode=unknown", "?q=alpha&limit=-1", "?q=alpha&fields=title,", "?q=alpha&extra=true")) {
                assertEquals(400, get(client, server, query).statusCode(), query);
            }
            assertEquals(400, post(client, server, "{\"query\":\"alpha\",\"includeDeleted\":\"false\"}").statusCode());
            assertEquals(400, post(client, server, "{\"query\":\"alpha\",\"asOfValidTime\":\"2026-09-28T00:00:00Z\"}").statusCode());
        }
    }

    @Test
    void malformedInputsAndGeneratedNamesFailExplicitly() {
        for (String text : List.of("", " \t\r\n", "\u3000\u00a0\uFEFF", "a".repeat(4097), "a ".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> query(text, null));
        }
        assertThrows(IllegalArgumentException.class, () -> query("alpha", List.of("title", "title")));
        assertThrows(IllegalArgumentException.class, () -> SearchQuery.fromJson(Map.of("query", "alpha", "first", 1, "limit", 1)));
        assertThrows(IllegalArgumentException.class, () -> SearchQuery.fromJson(Map.of("query", "alpha", "after", "Y3Vyc29yOjA=", "offset", 1)));
        for (String name : List.of("SearchMode", "SearchHit_Article", "SearchResult_Article", "SearchArticles")) {
            var schema = new OdlParser().parse("""
                    extend schema @namespace(name: "collision", version: "1.0.0")
                    type Article @objectType { id: ID! @primary }
                    type %s @objectType { id: ID! @primary }
                    """.formatted(name));
            assertThrows(IllegalArgumentException.class, () -> new GraphqlContractGenerator().generate(schema), name);
            var app = new ApplicationService(new InMemoryStorageProvider(), new AuthorizationService((p, r, k) -> true), new ActionExecutor(), schema, Map.of(), Map.of());
            assertThrows(IllegalArgumentException.class, () -> GraphqlApiRuntime.create(schema, app), name);
        }
    }

    private static SearchQuery query(String text, List<String> fields) { return new SearchQuery(text, fields, Map.of()); }
    private static SearchQuery mode(String text, SearchQuery.Mode mode) { return new SearchQuery(text, null, Map.of(), mode, 20, 0, null, null, false); }
    private static SearchQuery page(String text, int limit, int offset) { return new SearchQuery(text, null, Map.of(), SearchQuery.Mode.TERMS, limit, offset, null, null, false); }
    private static List<String> ids(SearchResult result) { return result.hits().stream().map(hit -> hit.node().id()).toList(); }

    private static HttpResponse<String> get(HttpClient client, JdkRestServer server, String query) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/v1/Article/search" + query)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private static HttpResponse<String> post(HttpClient client, JdkRestServer server, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/v1/Article/search"))
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
        SearchResult search(SearchQuery query) { return app(Map.of()).searchObjects(CONTEXT, PRINCIPAL, "Article", query); }
    }
    @FunctionalInterface private interface Check { void run(Fixture fixture) throws Exception; }
    private static class TestClock extends Clock {
        Instant now = Instant.parse("2026-09-28T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
