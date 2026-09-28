package org.openfoundry.foundation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionInput;
import graphql.schema.idl.SchemaParser;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.schema.*;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.*;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class CustomScalarTest {
    @TempDir Path directory;
    static final RequestContext CONTEXT = RequestContext.system("tenant", "operator");
    static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("operator", "tenant", Set.of());
    static final String ODL = """
            extend schema @namespace(name:"custom",version:"1.0.0")
            "A code or structured payload; not an object reference."
            scalar Payload
            scalar Date
            interface Described { payload: Payload! }
            type Item implements Described @objectType {
                id: ID! @primary
                secret: Payload @sensitive
                payloads: [Payload!]
                defaulted: Payload @default(value:{code:"default",flags:[true,false]})
                uniqueValue: Payload @unique
            }
            type Related @linkType(from:"Item",to:"Item",cardinality:MANY_TO_MANY) { id: ID! @primary payload: Payload! }
            type Create @actionType(permission:"can_create") { id: ID! @param payload: Payload! @param payloads: [Payload!] @param numeric: Float @param }
            """;
    static final OntologySchema SCHEMA = new OdlParser().parse(ODL);
    static final ActionManifest CREATE = new ActionManifestParser().parse("""
            action: Create
            version: 1
            preconditions:
              - expr: "!has(params.numeric) || params.numeric == null || params.numeric > 0.0"
                error: "numeric must be positive"
            effects:
              - type: createObject
                objectType: Item
                target: params.id
                properties: {payload: params.payload, payloads: params.payloads, secret: params.payload}
            """);
    static final Map<String, Object> VALUE = Map.of("code", "A", "nested", Map.of("value", 1, "enabled", true), "list", List.of("x", 2));

    @TestFactory
    Stream<DynamicTest> scalarRoundTrips() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "object link and history retain custom values", this::storage),
                test(provider, "mutable input cannot mutate stored custom values", this::immutability),
                test(provider, "required lists unique values and invalid values are checked", this::validation),
                test(provider, "Action input accepts values without interpreting identity fields", this::actions),
                test(provider, "typed GraphQL literal variables output and descriptions agree", this::graphql),
                test(provider, "scalar metadata changes invalidate old applications", this::binding),
                test(provider, "custom scalar constraints validate nested values", this::constraints),
                test(provider, "precise nested numbers survive persistence and activation", this::preciseNumbers),
                test(provider, "opaque equality grouping and field visibility govern queries", this::queries),
                test(provider, "HTTP and legacy GraphQL preserve numeric input before validation", this::wirePrecision)));
    }

    private DynamicTest test(String provider, String name, Consumer<Fixture> check) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> check.accept(fixture(provider.equals("memory") ? null : data("jdbc:h2:mem:scalar_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"))));
    }

    private void storage(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "a", Map.of("payload", VALUE));
            tx.createObject("Item", "b", Map.of("payload", "code-only"));
            tx.createLink("Related", "edge", new EntityKey("Item", "a"), new EntityKey("Item", "b"), Map.of("payload", VALUE));
            tx.commit();
        }
        assertEquals(VALUE, f.storage.getObject(CONTEXT, "Item", "a").properties().get("payload"));
        assertEquals(Map.of("code", "default", "flags", List.of(true, false)), f.storage.getObject(CONTEXT, "Item", "a").properties().get("defaulted"));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Item", "a", Map.of("payload", List.of(VALUE, "changed")), 1);
            tx.updateLink("Related", "edge", Map.of("payload", "updated"), 1);
            tx.commit();
        }
        assertEquals(VALUE, f.storage.getEntityHistory(CONTEXT, new EntityKey("Item", "a")).getFirst().state().get("payload"));
        assertEquals(VALUE, f.storage.getEntityHistory(CONTEXT, new EntityKey("Related", "edge")).getFirst().state().get("payload"));
        assertEquals("updated", f.storage.getLink(CONTEXT, "Related", "edge").properties().get("payload"));
    }

    @SuppressWarnings("unchecked")
    private void immutability(Fixture f) {
        var nested = new ArrayList<Object>(List.of("original"));
        var mutable = new LinkedHashMap<String, Object>();
        mutable.put("nested", nested);
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "a", Map.of("payload", mutable));
            nested.add("client mutation");
            tx.commit();
        }
        var stored = (Map<String, Object>) f.storage.getObject(CONTEXT, "Item", "a").properties().get("payload");
        assertEquals(List.of("original"), stored.get("nested"));
        assertThrows(UnsupportedOperationException.class, () -> ((List<Object>) stored.get("nested")).add("server mutation"));
    }

    private void validation(Fixture f) {
        for (Object invalid : List.of(new Object(), Map.of(1, "not a JSON key"), Double.NaN)) {
            try (var tx = f.storage.beginTransaction(CONTEXT)) {
                assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "bad", Map.of("payload", invalid)));
            }
        }
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "missing", Map.of()));
            assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "bad-list", Map.of("payload", VALUE, "payloads", Arrays.asList(VALUE, null))));
        }
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "a", Map.of("payload", VALUE, "uniqueValue", Map.of("a", 1, "b", 2)));
            tx.commit();
        }
        var reversed = new LinkedHashMap<String, Object>();
        reversed.put("b", 2L);
        reversed.put("a", 1L);
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "b", Map.of("payload", VALUE, "uniqueValue", reversed)));
        }
    }

    private void actions(Fixture f) {
        var identityLike = Map.<String, Object>of("id", "hidden-person", "_type", "Person", "nested", VALUE);
        var parameters = Map.<String, Object>of("id", "a", "payload", identityLike, "payloads", List.of(VALUE, "code"));
        var result = f.app.execute(CREATE, CONTEXT, PRINCIPAL, parameters, "same");
        assertTrue(result.success());
        assertEquals(result, f.app.execute(CREATE, CONTEXT, PRINCIPAL, parameters, "same"));
        assertEquals(identityLike, f.storage.getObject(CONTEXT, "Item", "a").properties().get("payload"));
        assertFalse(f.app.getObject(CONTEXT, PRINCIPAL, "Item", "a").properties().containsKey("secret"));
        var forged = f.storage.getObject(CONTEXT, "Item", "a");
        assertThrows(IllegalArgumentException.class, () -> f.app.execute(CREATE, CONTEXT, PRINCIPAL, Map.of("id", "b", "payload", forged), "forged"));
        assertNull(f.storage.getObject(CONTEXT, "Item", "b"));
    }

    private void graphql(Fixture f) {
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app, Map.of("Create", CREATE));
        var created = graph.execute(input("mutation{create(input:{id:\"a\",payload:{code:\"inline\",nested:[1,true,null]},payloads:[\"plain\",{flag:true}]}){success}}", Map.of()));
        assertTrue(created.getErrors().isEmpty(), created.getErrors().toString());
        assertEquals(Map.of("create", Map.of("success", true)), created.getData());
        var variable = graph.execute(input("mutation($payload:Payload!){create(input:{id:\"b\",payload:$payload}){success}}", Map.of("payload", VALUE)));
        assertTrue(variable.getErrors().isEmpty(), variable.getErrors().toString());
        var read = graph.execute(input("{item(id:\"b\"){payload secret} __type(name:\"Payload\"){kind name description}}", Map.of()));
        assertTrue(read.getErrors().isEmpty(), read.getErrors().toString());
        var result = (Map<?, ?>) read.getData();
        assertEquals(VALUE, ((Map<?, ?>) result.get("item")).get("payload"));
        assertNull(((Map<?, ?>) result.get("item")).get("secret"));
        assertEquals(Map.of("kind", "SCALAR", "name", "Payload", "description", "A code or structured payload; not an object reference."), result.get("__type"));
        var contract = new GraphqlContractGenerator().generate(SCHEMA);
        var parsed = new SchemaParser().parse(contract);
        assertTrue(parsed.scalars().containsKey("Payload"));
        assertEquals(1, contract.split("scalar Payload", -1).length - 1);
    }

    private void wirePrecision(Fixture f) {
        String body = "{\"id\":\"a\",\"numeric\":1.0000000000000000001,\"payload\":{\"number\":1.0000000000000000001}}";
        try (var server = new JdkRestServer(0, new RestApiRouter(f.app), () -> new ApiRequestContext(CONTEXT, PRINCIPAL), Map.of("Create", CREATE));
             var client = java.net.http.HttpClient.newHttpClient()) {
            server.start();
            var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + server.port() + "/api/v1/actions/Create"))
                    .header("Content-Type", "application/json").header("Idempotency-Key", "wire")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(response.body(), client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString()).body());
            var read = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + server.port() + "/api/v1/Item/a")).build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, read.statusCode());
            assertTrue(read.body().contains("1.0000000000000000001"), read.body());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
        var legacy = GraphqlApiRuntime.createLegacy(SCHEMA, f.app, Map.of("Create", CREATE));
        var result = legacy.execute(input("mutation($input:String!){create(input:$input)}", Map.of("input", body.replace("\"a\"", "\"b\""))));
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        for (String id : List.of("a", "b")) {
            assertEquals(Map.of("number", new java.math.BigDecimal("1.0000000000000000001")), f.storage.getObject(CONTEXT, "Item", id).properties().get("payload"));
        }
    }

    private void queries(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "a", Map.of("payload", Map.of("number", 1), "secret", VALUE));
            tx.createObject("Item", "b", Map.of("payload", Map.of("number", 1L)));
            tx.createObject("Item", "c", Map.of("payload", "plain"));
            tx.commit();
        }
        var equal = new ObjectConnectionQuery(Map.of("payload", Map.of("eq", Map.of("number", 1.0))), Map.of(), ConnectionPage.defaults());
        assertEquals(2, f.app.queryConnection(CONTEXT, PRINCIPAL, "Item", equal).totalCount());
        var member = new ObjectConnectionQuery(Map.of("payload", Map.of("in", List.of("plain"))), Map.of(), ConnectionPage.defaults());
        assertEquals(1, f.app.queryConnection(CONTEXT, PRINCIPAL, "Item", member).totalCount());
        assertThrows(SecurityException.class, () -> f.app.queryConnection(CONTEXT, PRINCIPAL, "Item",
                new ObjectConnectionQuery(Map.of("secret", Map.of("eq", VALUE)), Map.of(), ConnectionPage.defaults())));
        assertThrows(IllegalArgumentException.class, () -> f.app.queryConnection(CONTEXT, PRINCIPAL, "Item",
                new ObjectConnectionQuery(Map.of("payload", Map.of("contains", "plain")), Map.of(), ConnectionPage.defaults())));
        assertThrows(IllegalArgumentException.class, () -> f.app.queryConnection(CONTEXT, PRINCIPAL, "Item",
                new ObjectConnectionQuery(Map.of(), Map.of("payload", "ASC"), ConnectionPage.defaults())));
        var counts = new AggregateQuery(List.of(new AggregateQuery.Field("*", AggregateQuery.Function.COUNT, "count")), List.of("payload"), Map.of(), List.of());
        var aggregate = f.app.aggregateObjects(CONTEXT, PRINCIPAL, "Item", counts);
        assertEquals(2, aggregate.totalGroups());
        assertEquals(Set.of(1L, 2L), aggregate.groups().stream().map(group -> ((Number) group.values().get("count")).longValue()).collect(java.util.stream.Collectors.toSet()));
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app, Map.of("Create", CREATE));
        var result = graph.execute(input("query($value:Payload!){items(filter:{payload:{eq:$value}}){totalCount}}", Map.of("value", Map.of("number", 1))));
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        assertEquals(Map.of("items", Map.of("totalCount", 2)), result.getData());
    }

    private void preciseNumbers(Fixture f) {
        var first = new java.math.BigDecimal("1.0000000000000000001");
        var second = new java.math.BigDecimal("1.0000000000000000002");
        var payload = Map.of("number", first);
        var params = Map.<String, Object>of("id", "a", "payload", payload);
        var created = f.app.execute(CREATE, CONTEXT, PRINCIPAL, params, "decimal");
        assertTrue(created.success());
        assertEquals(payload, f.storage.getObject(CONTEXT, "Item", "a").properties().get("payload"));
        assertEquals(payload, f.storage.getEntityHistory(CONTEXT, new EntityKey("Item", "a")).getFirst().state().get("payload"));
        assertEquals(created, f.app.execute(CREATE, CONTEXT, PRINCIPAL, params, "decimal"));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Item", "a", Map.of("uniqueValue", first), 1);
            tx.createObject("Item", "b", Map.of("payload", payload, "uniqueValue", second));
            tx.commit();
        }
        var schema = new OdlParser().parse(ODL.replace("A code or structured payload; not an object reference.", "A precise payload."));
        if (f.storage instanceof JdbcStorageProvider jdbc) jdbc.activateSchema(CONTEXT, schema, null, jdbc.boundSchemaVersion());
        else f.storage.applySchema(CONTEXT, schema);
        assertEquals(first, f.storage.getObject(CONTEXT, "Item", "a").properties().get("uniqueValue"));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "duplicate", Map.of("payload", payload, "uniqueValue", first)));
        }
        var exactDefault = new OdlParser().parse(ODL.replace("flags:[true,false]", "number:1.0000000000000000001,large:9223372036854775808"));
        var defaultProperty = exactDefault.objectTypes().getFirst().properties().stream().filter(field -> field.name().equals("defaulted")).findFirst().orElseThrow();
        assertEquals(first, ((Map<?, ?>) defaultProperty.defaultValue()).get("number"));
        assertEquals(new java.math.BigInteger("9223372036854775808"), ((Map<?, ?>) defaultProperty.defaultValue()).get("large"));
        new SchemaCompiler().compile(exactDefault);
        assertThrows(SchemaValidationException.class, () -> new SchemaCompiler().compile(new OdlParser().parse(
                "extend schema @namespace(name:\"invalid-int\",version:\"1\") type Item { id: ID! @primary count: Int @default(value:2147483648) }")));
    }

    private void constraints(Fixture f) {
        var schema = new OdlParser().parse(ODL.replace("interface Described { payload: Payload! }",
                "interface Described { payload: Payload! @constraint(expr:\"value.code != ''\") }"));
        if (f.storage instanceof JdbcStorageProvider jdbc) jdbc.activateSchema(CONTEXT, schema, new MigrationPlan("add scalar constraint", true), jdbc.boundSchemaVersion());
        else f.storage.applySchema(CONTEXT, schema);
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "bad", Map.of("payload", Map.of("code", ""))));
        }
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "good", Map.of("payload", VALUE));
            tx.commit();
        }
        assertEquals(VALUE, f.storage.getObject(CONTEXT, "Item", "good").properties().get("payload"));
    }

    private void binding(Fixture f) {
        var changed = new OdlParser().parse(ODL.replace("A code or structured payload; not an object reference.", "Revised scalar documentation."));
        if (f.storage instanceof JdbcStorageProvider jdbc) jdbc.activateSchema(CONTEXT, changed, null, jdbc.boundSchemaVersion());
        else f.storage.applySchema(CONTEXT, changed);
        assertThrows(SchemaVersionMismatchException.class, () -> f.app.getObject(CONTEXT, PRINCIPAL, "Item", "a"));
    }

    @Test
    void declarationsCompositionAndVersionIdentity() {
        var parser = new OdlParser();
        assertEquals(List.of("Payload", "Date"), SCHEMA.scalars().stream().map(ScalarDefinition::name).toList());
        new SchemaCompiler().compile(SCHEMA);
        assertThrows(PropertyValidationException.class, () -> PropertyValues.normalize(SCHEMA, "Date", "invalid", "date"));
        var duplicate = parser.compose("bundle", "1", List.of(ODL, "extend schema @namespace(name:\"extra\",version:\"1\") scalar Date"));
        assertEquals(2, duplicate.scalars().size());
        assertThrows(SchemaValidationException.class, () -> parser.compose("bundle", "1", List.of(ODL,
                "extend schema @namespace(name:\"extra\",version:\"1\") scalar Payload")));
        var renamed = parser.parse(ODL.replace("Payload", "Packet"));
        assertNotEquals(new SchemaCompiler().compile(SCHEMA).schemaDigest(), new SchemaCompiler().compile(renamed).schemaDigest());
        assertNotEquals(SchemaFingerprint.of(SCHEMA), SchemaFingerprint.of(renamed));
        assertEquals(MigrationClass.BREAKING, new SchemaDiffer().diff(SCHEMA, renamed).classification());
        var description = parser.parse(ODL.replace("A code or structured payload; not an object reference.", "Updated description."));
        assertEquals(MigrationClass.SAFE, new SchemaDiffer().diff(SCHEMA, description).classification());
        assertNotEquals(SchemaFingerprint.of(SCHEMA), SchemaFingerprint.of(description));
    }

    @Test
    void malformedScalarAndApiNameCollisionsFail() {
        for (String suffix : List.of("scalar Payload", "scalar __Hidden", "scalar Extra @constraint(expr:\"true\")")) {
            assertThrows(SchemaValidationException.class, () -> new OdlParser().parse(ODL + suffix));
        }
        for (String name : List.of("Item", "Create", "Described")) {
            assertThrows(SchemaValidationException.class, () -> new SchemaCompiler().compile(new OdlParser().parse(ODL + "scalar " + name)));
        }
        for (String name : List.of("Query", "PageInfo", "CreateInput", "PayloadFilter", "ConsentInput")) {
            var schema = new OdlParser().parse(ODL + "scalar " + name);
            assertThrows(IllegalArgumentException.class, () -> new GraphqlContractGenerator().generate(schema));
        }
        assertThrows(SchemaValidationException.class, () -> new SchemaCompiler().compile(new OdlParser().parse(ODL.replace("scalar Payload", "scalar Other"))));
    }

    @Test
    void unannotatedUpstreamObjectsAreNotSilentlyDropped() {
        var parsed = new OdlParser().parse("extend schema @namespace(name:\"plain\",version:\"1\") type Plain { id: ID! @primary name: String! }");
        assertEquals("Plain", parsed.objectTypes().getFirst().name());
        new SchemaCompiler().compile(parsed);
    }

    @Test
    void legacySchemaSnapshotsKeepTheirFingerprint() throws Exception {
        String json = "{\"namespace\":\"legacy\",\"version\":\"1\",\"objectTypes\":[],\"linkTypes\":[],\"actionTypes\":[],\"enums\":{},\"interfaces\":[]}";
        var schema = new ObjectMapper().readValue(json, OntologySchema.class);
        assertTrue(schema.scalars().isEmpty());
        var oldValue = new ObjectMapper().readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(("registry-schema-v1:" + PropertyValues.canonical(oldValue)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(expected, SchemaFingerprint.of(schema));
    }

    @Test
    void customScalarRegistrySurvivesReopening() {
        var source = data("jdbc:h2:file:" + directory.resolve("scalars"));
        var registry = new JdbcSchemaRegistry(source, DatabaseDialect.h2(), "custom", java.time.Clock.systemUTC());
        registry.applyIfChanged(SCHEMA, null);
        var reopened = new JdbcSchemaRegistry(source, DatabaseDialect.h2(), "custom", java.time.Clock.systemUTC());
        assertEquals(SCHEMA, reopened.current());
        assertEquals(1, reopened.applyIfChanged(SCHEMA, null).version());
    }

    private static ExecutionInput input(String query, Map<String, Object> variables) {
        return ExecutionInput.newExecutionInput(query).variables(variables)
                .graphQLContext(Map.of("request", new ApiRequestContext(CONTEXT, PRINCIPAL))).build();
    }
    private static Fixture fixture(JdbcDataSource source) {
        StorageProvider storage = source == null ? new InMemoryStorageProvider() : new JdbcStorageProvider(source, DatabaseDialect.h2());
        storage.applySchema(CONTEXT, SCHEMA);
        var app = new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> true),
                new ActionExecutor(), SCHEMA, Map.of("Create", CREATE), Map.of());
        return new Fixture(storage, app);
    }
    private static JdbcDataSource data(String url) {
        var source = new JdbcDataSource();
        source.setURL(url);
        return source;
    }
    record Fixture(StorageProvider storage, ApplicationService app) {}
}
