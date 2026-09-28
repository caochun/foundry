package org.openfoundry.foundation.api;

import graphql.*;
import graphql.execution.instrumentation.*;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.schema.*;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.lang.reflect.*;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class SchemaReadBindingTest {
    private static final String ODL = """
            extend schema @namespace(name: "read-binding", version: "1.0.0")
            type Item @objectType {
                id: ID! @primary name: String! secret: String amount: Int
                neighbors: [Item!] @link(type:"Edge", direction:OUTBOUND)
                count: Int @computed(fn:"countLinks", args:{type:"Edge", direction:OUTBOUND})
            }
            type Edge @linkType(from:"Item", to:"Item", cardinality:MANY_TO_MANY) { id: ID! @primary note: String }
            type Rename @actionType(permission:"can_rename") { item: Item! @param name: String! @param }
            """;
    private static final OntologySchema BASE = new OdlParser().parse(ODL);
    private static final OntologySchema PRIVATE = new OdlParser().parse(ODL.replace("secret: String", "secret: String @sensitive"));
    private static final RequestContext CONTEXT = RequestContext.system("tenant", "reader");
    private static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("reader", "tenant", Set.of());
    private static final Instant TIME = Instant.parse("2026-09-28T00:00:00Z");
    private static final ActionManifest RENAME = new ActionManifest("Rename", 1, false, List.of(),
            List.of(new ActionManifest.UpdateObject("item", Map.of("name", "params.name"))));

    @TestFactory
    Stream<DynamicTest> bindingAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "all application read families reject an old model", this::readFamilies),
                test(provider, "storage rebinding does not silently upgrade old applications", this::rebind),
                test(provider, "switch after storage read discards the captured value", this::afterRead),
                test(provider, "switch during authorization rejects even empty results", this::authorization),
                test(provider, "switch between command preparation and transaction creation is rejected", this::transactionRace),
                test(provider, "already captured transactions reject a schema transition", this::transactionPin),
                test(provider, "old applications do not revive when a schema returns", this::aba),
                test(provider, "GraphQL discards whole queries but retains committed mutation receipts", this::graphql),
                test(provider, "JDK REST rejects stale read projections", this::http)));
    }

    private DynamicTest test(String provider, String name, Check check) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> {
            var clock = Clock.fixed(TIME, ZoneOffset.UTC);
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider(clock);
            else {
                var data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:read_binding_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
            }
            storage.applySchema(CONTEXT, BASE);
            seed(storage);
            check.run(new Fixture(storage));
        });
    }

    private void readFamilies(Fixture f) {
        var app = f.app(f.storage);
        var computed = new ComputedFieldEvaluator(f.storage, BASE);
        assertEquals("private-value", app.getObject(CONTEXT, PRINCIPAL, "Item", "a").properties().get("secret"));
        f.activate(PRIVATE);
        List<Runnable> reads = List.of(
                () -> app.getObject(CONTEXT, PRINCIPAL, "Item", "a"),
                () -> app.listObjects(CONTEXT, PRINCIPAL, "Item", QueryOptions.defaults()),
                () -> app.queryObjects(CONTEXT, PRINCIPAL, "Item", ObjectQuery.all(QueryOptions.defaults())),
                () -> app.queryConnection(CONTEXT, PRINCIPAL, "Item", new ObjectConnectionQuery(Map.of(), Map.of(), new ConnectionPage(0, null, null, null, 0))),
                () -> app.aggregateObjects(CONTEXT, PRINCIPAL, "Item", new AggregateQuery(List.of(new AggregateQuery.Field("*", AggregateQuery.Function.COUNT)), List.of(), Map.of(), List.of())),
                () -> app.searchObjects(CONTEXT, PRINCIPAL, "Item", new SearchQuery("A", null, Map.of())),
                () -> app.history(CONTEXT, PRINCIPAL, new EntityKey("Item", "a")),
                () -> app.readLinkField(CONTEXT, PRINCIPAL, new EntityKey("Item", "a"), "neighbors", QueryOptions.defaults()),
                () -> app.readComputedField(CONTEXT, PRINCIPAL, new EntityKey("Item", "a"), "count"),
                () -> computed.evaluate(CONTEXT, new EntityKey("Item", "a"), "count"));
        for (var read : reads) assertThrows(SchemaVersionMismatchException.class, read::run);
        var fresh = f.app(f.storage);
        assertFalse(fresh.getObject(CONTEXT, PRINCIPAL, "Item", "a").properties().containsKey("secret"));
        assertEquals(1, fresh.readComputedField(CONTEXT, PRINCIPAL, new EntityKey("Item", "a"), "count"));
        assertFalse(fresh.history(CONTEXT, PRINCIPAL, new EntityKey("Item", "a")).getFirst().state().containsKey("secret"));
    }

    private void rebind(Fixture f) {
        var old = f.app(f.storage);
        f.activate(PRIVATE);
        assertThrows(SchemaVersionMismatchException.class, () -> old.execute(RENAME, CONTEXT, PRINCIPAL, Map.of("item", "a", "name", "Denied"), "old"));
        assertEquals("A", f.storage.getObject(CONTEXT, "Item", "a").properties().get("name"));
        assertThrows(SchemaVersionMismatchException.class, () -> new ApplicationService(f.storage, allow(), new ActionExecutor(), BASE, Map.of("Rename", RENAME), Map.of()));
        var fresh = f.app(f.storage);
        assertTrue(fresh.execute(RENAME, CONTEXT, PRINCIPAL, Map.of("item", "a", "name", "Current"), "new").success());
        assertEquals("Current", fresh.getObject(CONTEXT, PRINCIPAL, "Item", "a").properties().get("name"));
        assertThrows(SchemaVersionMismatchException.class, () -> GraphqlApiRuntime.create(BASE, fresh));
    }

    private void afterRead(Fixture f) {
        for (String operation : List.of("getObject", "getLinks", "getEntityHistory", "queryObjects")) {
            if (f.schema != BASE) f.activate(BASE);
            var changed = new AtomicBoolean();
            var wrapped = proxy(f.storage, (method, args, invoke) -> {
                Object result = invoke.get();
                if (method.getName().equals(operation) && changed.compareAndSet(false, true)) f.activate(PRIVATE);
                return result;
            });
            var app = f.app(wrapped);
            Runnable read = switch (operation) {
                case "getLinks" -> () -> app.readComputedField(CONTEXT, PRINCIPAL, new EntityKey("Item", "a"), "count");
                case "getEntityHistory" -> () -> app.history(CONTEXT, PRINCIPAL, new EntityKey("Item", "a"));
                case "queryObjects" -> () -> app.listObjects(CONTEXT, PRINCIPAL, "Item", QueryOptions.defaults());
                default -> () -> app.getObject(CONTEXT, PRINCIPAL, "Item", "a");
            };
            assertThrows(SchemaVersionMismatchException.class, read::run, operation);
            assertTrue(changed.get(), operation);
        }
    }

    private void authorization(Fixture f) {
        var changed = new AtomicBoolean();
        var auth = new AuthorizationService((principal, relation, key) -> {
            if (changed.compareAndSet(false, true)) f.activate(PRIVATE);
            return false;
        });
        var app = new ApplicationService(f.storage, auth, new ActionExecutor(), BASE, Map.of("Rename", RENAME), Map.of());
        assertThrows(SchemaVersionMismatchException.class, () -> app.getObject(CONTEXT, PRINCIPAL, "Item", "a"));
    }

    private void transactionRace(Fixture f) {
        var changed = new AtomicBoolean();
        var wrapped = proxy(f.storage, (method, args, invoke) -> {
            if (method.getName().equals("beginTransaction") && changed.compareAndSet(false, true)) {
                assertEquals(2, args.length, "The application must pass its pinned binding into transaction creation");
                f.activate(PRIVATE);
            }
            return invoke.get();
        });
        var app = f.app(wrapped);
        assertThrows(SchemaVersionMismatchException.class, () -> app.execute(RENAME, CONTEXT, PRINCIPAL, Map.of("item", "a", "name", "Must not commit"), "race"));
        assertEquals(1, f.storage.getObject(CONTEXT, "Item", "a").version());
    }

    private void transactionPin(Fixture f) {
        var expected = f.storage.schemaBinding();
        try (var tx = f.storage.beginTransaction(CONTEXT, expected)) {
            f.activate(PRIVATE);
            assertThrows(SchemaVersionMismatchException.class, () -> tx.updateObject("Item", "a", Map.of("name", "Uncommitted"), 1));
            assertThrows(IllegalStateException.class, tx::commit);
        }
        assertEquals("A", f.storage.getObject(CONTEXT, "Item", "a").properties().get("name"));
    }

    private void aba(Fixture f) {
        var app = f.app(f.storage);
        var binding = f.storage.schemaBinding();
        f.activate(PRIVATE);
        f.activate(BASE);
        assertNotEquals(binding.id(), f.storage.schemaBinding().id());
        assertThrows(SchemaVersionMismatchException.class, () -> app.getObject(CONTEXT, PRINCIPAL, "Item", "a"));
        assertEquals("private-value", f.app(f.storage).getObject(CONTEXT, PRINCIPAL, "Item", "a").properties().get("secret"));
    }

    private void graphql(Fixture f) {
        var app = f.app(f.storage);
        var graph = GraphqlApiRuntime.create(BASE, app, Map.of("Rename", RENAME));
        var switchAtSecondRoot = new SimplePerformantInstrumentation() {
            @Override public graphql.schema.DataFetcher<?> instrumentDataFetcher(graphql.schema.DataFetcher<?> fetcher,
                    graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters parameters, InstrumentationState state) {
                if (!"second".equals(parameters.getEnvironment().getField().getAlias())) return fetcher;
                return environment -> { f.activate(PRIVATE); return fetcher.get(environment); };
            }
        };
        var partial = graph.transform(builder -> builder.instrumentation(new ChainedInstrumentation(List.of(switchAtSecondRoot, graph.getInstrumentation()))))
                .execute(input("{ first:item(id:\"a\"){secret} second:item(id:\"b\"){name} }"));
        assertNull(partial.getData(), "A later root failure must not leave earlier private values in the response");
        assertEquals("SCHEMA_VERSION_MISMATCH", partial.getErrors().getFirst().getExtensions().get("code"));
        f.activate(BASE);
        var reboundGraph = GraphqlApiRuntime.create(BASE, f.app(f.storage), Map.of("Rename", RENAME));
        var query = withCompletionSwitch(reboundGraph, () -> f.activate(PRIVATE));
        var result = query.execute(input("{ item(id:\"a\") { id secret } }"));
        assertNull(result.getData(), "No old projected values can survive an activation before query completion");
        assertEquals("SCHEMA_VERSION_MISMATCH", result.getErrors().getFirst().getExtensions().get("code"));
        assertFalse(result.getErrors().toString().contains("private-value"));
        var metadata = graph.execute(input("{__schema{queryType{name}}}"));
        assertNull(metadata.getData());
        assertEquals("SCHEMA_VERSION_MISMATCH", metadata.getErrors().getFirst().getExtensions().get("code"));
        var wrongContext = RequestContext.system("another-tenant", "reader");
        var denied = query.execute(ExecutionInput.newExecutionInput("{item(id:\"a\"){id}}")
                .graphQLContext(Map.of("request", new ApiRequestContext(wrongContext, PRINCIPAL))).build());
        assertNull(denied.getData());
        assertEquals("FORBIDDEN", denied.getErrors().getFirst().getExtensions().get("code"));
        var fresh = f.app(f.storage);
        var mutation = withCompletionSwitch(GraphqlApiRuntime.create(PRIVATE, fresh, Map.of("Rename", RENAME)), () -> f.activate(BASE));
        var committed = mutation.execute(input("mutation { rename(input:{item:\"a\",name:\"Committed\"}) { success actionId } }"));
        assertTrue(committed.getErrors().isEmpty(), committed.getErrors().toString());
        assertEquals(true, ((Map<?, ?>) ((Map<?, ?>) committed.getData()).get("rename")).get("success"));
        assertEquals("Committed", f.storage.getObject(CONTEXT, "Item", "a").properties().get("name"));
        var rejected = graph.execute(input("mutation { rename(input:{item:\"a\",name:\"Stale\"}) { success } }"));
        assertFalse(rejected.getErrors().isEmpty());
        assertEquals("SCHEMA_VERSION_MISMATCH", rejected.getErrors().getFirst().getExtensions().get("code"));
    }

    private void http(Fixture f) throws Exception {
        var app = f.app(f.storage);
        f.activate(PRIVATE);
        try (var server = new JdkRestServer(0, new RestApiRouter(app), () -> new ApiRequestContext(CONTEXT, PRINCIPAL));
             var client = HttpClient.newHttpClient()) {
            server.start();
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/v1/Item/a")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, response.statusCode());
            assertTrue(response.body().contains("SCHEMA_VERSION_MISMATCH"));
            assertFalse(response.body().contains("private-value"));
        }
    }

    @Test
    void inactiveJdbcInstancesRejectEveryOntologyReadSpi() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:inactive_reads;DB_CLOSE_DELAY=-1");
        var old = new JdbcStorageProvider(data, DatabaseDialect.h2(), Clock.fixed(TIME, ZoneOffset.UTC));
        old.applySchema(CONTEXT, BASE);
        seed(old);
        var fresh = new JdbcStorageProvider(data, DatabaseDialect.h2());
        fresh.applySchema(CONTEXT, BASE);
        fresh.activateSchema(CONTEXT, PRIVATE, new MigrationPlan("Tighten field visibility", true), 1);
        List<Runnable> reads = List.of(
                () -> old.getObject(CONTEXT, "Item", "a"), () -> old.queryObjects(CONTEXT, "Item", QueryOptions.defaults()),
                () -> old.getObjectAtVersion(CONTEXT, "Item", "a", 1), () -> old.getObjectAtTime(CONTEXT, "Item", "a", TIME, TIME),
                () -> old.getLink(CONTEXT, "Edge", "edge"), () -> old.getLinks(CONTEXT, new EntityKey("Item", "a"), "Edge", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()),
                () -> old.getLinkAtVersion(CONTEXT, "Edge", "edge", 1), () -> old.getLinkAtTime(CONTEXT, "Edge", "edge", TIME, TIME),
                () -> old.getEntityHistory(CONTEXT, new EntityKey("Item", "a")),
                () -> old.traverseAsOf(CONTEXT, new EntityKey("Item", "a"), List.of(), TIME, TIME, QueryOptions.defaults()),
                () -> old.pendingActions(CONTEXT, TIME, 1));
        for (var read : reads) assertThrows(SchemaVersionMismatchException.class, read::run);
        assertNotNull(fresh.getObject(CONTEXT, "Item", "a"));
    }

    @Test
    void jdbcDiscardsARowWhenAnotherProviderActivatesBeforeTheSqlReadReturns() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:read_cutover;DB_CLOSE_DELAY=-1");
        var armed = new AtomicBoolean();
        var changed = new AtomicBoolean();
        var active = new JdbcStorageProvider(data, DatabaseDialect.h2());
        javax.sql.DataSource intercepted = (javax.sql.DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{javax.sql.DataSource.class}, (proxy, method, args) -> {
            Object result = invoke(method, data, args);
            if (!(result instanceof java.sql.Connection connection)) return result;
            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.Connection.class}, (ignored, operation, arguments) -> {
                Object prepared = invoke(operation, connection, arguments);
                if (!(prepared instanceof java.sql.PreparedStatement statement) || arguments == null || !(arguments[0] instanceof String sql)
                        || !sql.contains("FROM of_objects WHERE tenant_id")) return prepared;
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.PreparedStatement.class}, (unused, query, values) -> {
                    Object selected = invoke(query, statement, values);
                    if (!(selected instanceof java.sql.ResultSet rows)) return selected;
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{java.sql.ResultSet.class}, (unusedRow, rowMethod, rowArgs) -> {
                        Object value = invoke(rowMethod, rows, rowArgs);
                        if (rowMethod.getName().equals("close") && armed.get() && changed.compareAndSet(false, true)) {
                            active.activateSchema(CONTEXT, PRIVATE, new MigrationPlan("Tighten visibility during a read", true), 1);
                        }
                        return value;
                    });
                });
            });
        });
        var old = new JdbcStorageProvider(intercepted, DatabaseDialect.h2());
        old.applySchema(CONTEXT, BASE);
        seed(old);
        active.applySchema(CONTEXT, BASE);
        armed.set(true);
        assertThrows(SchemaVersionMismatchException.class, () -> old.getObject(CONTEXT, "Item", "a"));
        assertTrue(changed.get());
    }

    private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }

    @Test
    void declarationOnlyAppsCanBeBuiltBeforeStorageInitializationButCannotAdoptADifferentModel() {
        var storage = new InMemoryStorageProvider();
        var app = new ApplicationService(storage, allow(), new ActionExecutor(), BASE, Map.of(), Map.of());
        var graph = GraphqlApiRuntime.create(BASE, app);
        assertTrue(graph.execute("{__schema{queryType{name}}}").getErrors().isEmpty());
        assertThrows(IllegalStateException.class, () -> app.getObject(CONTEXT, PRINCIPAL, "Item", "a"));
        var unavailable = graph.execute(input("{item(id:\"a\"){id}}"));
        assertNull(unavailable.getData());
        assertEquals("SCHEMA_BINDING_UNAVAILABLE", unavailable.getErrors().getFirst().getExtensions().get("code"));
        storage.applySchema(CONTEXT, PRIVATE);
        assertThrows(SchemaVersionMismatchException.class, () -> app.getObject(CONTEXT, PRINCIPAL, "Item", "a"));
    }

    @Test
    void boundReadsAndCommandsWorkWithOneJdbcConnection() {
        var pool = JdbcConnectionPool.create("jdbc:h2:mem:bound_pool;DB_CLOSE_DELAY=-1", "sa", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(2);
        try {
            var storage = new JdbcStorageProvider(pool, DatabaseDialect.h2());
            storage.applySchema(CONTEXT, BASE);
            seed(storage);
            var app = new ApplicationService(storage, allow(), new ActionExecutor(), BASE, Map.of("Rename", RENAME), Map.of());
            assertEquals(1, app.readComputedField(CONTEXT, PRINCIPAL, new EntityKey("Item", "a"), "count"));
            assertTrue(app.execute(RENAME, CONTEXT, PRINCIPAL, Map.of("item", "a", "name", "One connection"), "one").success());
            assertEquals("One connection", app.getObject(CONTEXT, PRINCIPAL, "Item", "a").properties().get("name"));
        } finally { pool.dispose(); }
    }

    @Test
    void mutableNumericDefaultsCannotChangeAnAlreadyBoundSchemaInPlace() {
        var counter = new java.util.concurrent.atomic.AtomicInteger(1);
        var customDecimal = new java.math.BigDecimal("1") {
            @Override public String toString() { return counter.toString(); }
        };
        for (Number value : List.of(counter, customDecimal)) {
            assertThrows(IllegalArgumentException.class, () -> new org.openfoundry.foundation.spi.schema.PropertyDefinition(
                    "payload", "JSON", false, false, false, false, false, false, false, true, Map.of("counter", value), List.of()));
        }
    }

    private static GraphQL withCompletionSwitch(GraphQL graph, Runnable transition) {
        var hook = new SimplePerformantInstrumentation() {
            @Override public CompletableFuture<ExecutionResult> instrumentExecutionResult(ExecutionResult result, InstrumentationExecutionParameters parameters, InstrumentationState state) {
                transition.run();
                return CompletableFuture.completedFuture(result);
            }
        };
        return graph.transform(builder -> builder.instrumentation(new ChainedInstrumentation(List.of(hook, graph.getInstrumentation()))));
    }
    private static ExecutionInput input(String query) {
        return ExecutionInput.newExecutionInput(query).graphQLContext(Map.of("request", new ApiRequestContext(CONTEXT, PRINCIPAL))).build();
    }
    private static AuthorizationService allow() { return new AuthorizationService((p, r, key) -> true); }
    private static void seed(StorageProvider storage) {
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "a", Map.of("name", "A", "secret", "private-value", "amount", 1));
            tx.createObject("Item", "b", Map.of("name", "B", "amount", 2));
            tx.createLink("Edge", "edge", new EntityKey("Item", "a"), new EntityKey("Item", "b"), Map.of("note", "linked"));
            tx.commit();
        }
    }
    private static StorageProvider proxy(StorageProvider provider, Interceptor interceptor) {
        return (StorageProvider) Proxy.newProxyInstance(StorageProvider.class.getClassLoader(), new Class<?>[]{StorageProvider.class}, (proxy, method, args) ->
                interceptor.call(method, args, () -> {
                    try { return method.invoke(provider, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                }));
    }
    private static final class Fixture {
        final StorageProvider storage;
        OntologySchema schema = BASE;
        Fixture(StorageProvider storage) { this.storage = storage; }
        void activate(OntologySchema next) {
            if (storage instanceof JdbcStorageProvider jdbc) jdbc.activateSchema(CONTEXT, next, new MigrationPlan("Reviewed test model transition", true), jdbc.boundSchemaVersion());
            else storage.applySchema(CONTEXT, next);
            schema = next;
        }
        ApplicationService app(StorageProvider provider) { return new ApplicationService(provider, allow(), new ActionExecutor(), schema, Map.of("Rename", RENAME), Map.of()); }
    }
    @FunctionalInterface private interface Check { void run(Fixture fixture) throws Exception; }
    @FunctionalInterface private interface Invocation { Object get() throws Throwable; }
    @FunctionalInterface private interface Interceptor { Object call(Method method, Object[] args, Invocation invocation) throws Throwable; }
}
