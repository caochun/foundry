package org.openfoundry.foundation.api;

import graphql.ExecutionInput;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class TypedActionGraphqlTest {
    static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name: "typed", version: "1.0.0")
            enum Status { READY DONE }
            type Item @objectType {
              id: ID! @primary state: Status! due: DateTime duration: Duration uri: URI location: GeoPoint
              payload: JSON states: [Status!] matrix: [[Int!]!]
            }
            type Configure @actionType(permission: "can_configure") {
              item: Item! @param state: Status! @param deadline: DateTime! @param duration: Duration! @param
              url: URI! @param position: GeoPoint! @param payload: JSON @param states: [Status!] @param matrix: [[Int!]!] @param
            }
            type Pulse @actionType(permission: "can_pulse")
            type Refuse @actionType(permission: "can_refuse") { item: Item! @param }
            """);
    static final ActionManifest CONFIGURE = new ActionManifest("Configure", 1, false, List.of(), List.of(
            new ActionManifest.UpdateObject("item", Map.of("state", "params.state", "due", "params.deadline", "duration", "params.duration",
                    "uri", "params.url", "location", "params.position", "payload", "params.payload", "states", "params.states", "matrix", "params.matrix"))));
    static final ActionManifest PULSE = new ActionManifest("Pulse", 1, false, List.of(), List.of());
    static final ActionManifest REFUSE = new ActionManifest("Refuse", 1, false,
            List.of(new ActionManifest.Precondition("false", "Cannot configure now")), List.of());
    static final Map<String, ActionManifest> MANIFESTS = Map.of("Configure", CONFIGURE, "Pulse", PULSE, "Refuse", REFUSE);
    static final RequestContext CONTEXT = RequestContext.system("tenant", "actor");
    static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("actor", "tenant", Set.of("editor"));
    static final String MUTATION = "mutation($input: ConfigureInput!) { configure(input: $input) { success actionId status errors { code message field } affectedObjects { typeName id changeType } } }";

    @TestFactory
    Stream<DynamicTest> typedActionsAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "enum and custom scalar variables execute with typed affected objects", this::variables),
                test(provider, "inline JSON accepts nested variables", this::literals),
                test(provider, "invalid GraphQL and REST values leave data untouched", this::invalid),
                test(provider, "zero-parameter actions and declared failure messages have valid types", this::results),
                test(provider, "legacy JSON entry remains explicit and governed", this::legacy),
                test(provider, "runtime input shape and generated SDL agree", this::contract)));
    }

    private DynamicTest test(String provider, String label, Consumer<Fixture> verify) {
        return DynamicTest.dynamicTest(provider + " " + label, () -> {
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider();
            else {
                var data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:typed_actions_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2());
            }
            storage.applySchema(CONTEXT, SCHEMA);
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.createObject("Item", "item", Map.of("state", "READY"));
                tx.commit();
            }
            try { verify.accept(new Fixture(storage)); }
            finally { if (storage instanceof AutoCloseable resource) resource.close(); }
        });
    }

    private void variables(Fixture f) {
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app, MANIFESTS);
        var request = input(MUTATION, Map.of("input", values()), "typed");
        var result = graph.execute(request);
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        Map<?, ?> data = result.getData();
        var action = (Map<?, ?>) data.get("configure");
        assertEquals(true, action.get("success"));
        assertEquals("COMPLETED", action.get("status"));
        assertEquals(List.of(Map.of("typeName", "Item", "id", "item", "changeType", "UPDATED")), action.get("affectedObjects"));
        assertEquals(data, graph.execute(request).getData());
        var item = f.storage.getObject(CONTEXT, "Item", "item");
        assertEquals("DONE", item.properties().get("state"));
        assertEquals("2030-01-01T00:00:00Z", item.properties().get("due"));
        assertEquals(Map.of("lat", 30.0, "lon", 120.0), item.properties().get("location"));
        assertEquals(List.of(List.of(1, 2), List.of(3)), item.properties().get("matrix"));
        assertEquals(2, item.version());
    }

    private void literals(Fixture f) {
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app, MANIFESTS);
        var result = graph.execute(input("""
                mutation($label: String!) {
                  configure(input: {item: "item", state: DONE, deadline: "2030-01-01T00:00:00Z", duration: "PT1H",
                    url: "https://example.invalid", position: {lat: 30, lon: 120},
                    payload: {label: $label, values: [1, null, true]}, states: [READY, DONE], matrix: [[1, 2], [3]]}) {
                    success affectedObjects { changeType }
                  }
                }
                """, Map.of("label", "nested"), "literal"));
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        var payload = (Map<?, ?>) f.storage.getObject(CONTEXT, "Item", "item").properties().get("payload");
        assertEquals("nested", payload.get("label"));
        assertEquals(java.util.Arrays.asList(1, null, true), payload.get("values"));
    }

    private void invalid(Fixture f) {
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app, MANIFESTS);
        for (var patch : List.<Map<String, Object>>of(Map.of("state", "MISSING"), Map.of("duration", "tomorrow"),
                Map.of("url", "relative"), Map.of("position", Map.of("lat", 91, "lon", 0)),
                Map.of("item", Map.of("id", "item")), Map.of("states", List.of("READY", "MISSING")),
                Map.of("matrix", List.of(java.util.Arrays.asList(1, null))))) {
            var invalid = new java.util.LinkedHashMap<>(values());
            invalid.putAll(patch);
            assertFalse(graph.execute(input(MUTATION, Map.of("input", invalid), "invalid")).getErrors().isEmpty(), patch.toString());
            assertThrows(IllegalArgumentException.class, () -> f.app.execute(CONFIGURE, CONTEXT, PRINCIPAL, invalid, "rest-invalid"));
        }
        assertEquals(1, f.storage.getObject(CONTEXT, "Item", "item").version());
        var allowed = new java.util.LinkedHashMap<>(values());
        allowed.put("payload", null);
        assertTrue(f.app.execute(CONFIGURE, CONTEXT, PRINCIPAL, allowed, "rest-ok").success());
        assertNull(f.storage.getObject(CONTEXT, "Item", "item").properties().get("payload"));
    }

    private void results(Fixture f) {
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app, MANIFESTS);
        var result = graph.execute(input("mutation { pulse { success status } refuse(input: {item: \"item\"}) { success status errors { code message } } }", Map.of(), null));
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        Map<?, ?> data = result.getData();
        assertEquals(Map.of("success", true, "status", "COMPLETED"), data.get("pulse"));
        assertEquals(List.of(Map.of("code", "PRECONDITION_FAILED", "message", "Cannot configure now")), ((Map<?, ?>) data.get("refuse")).get("errors"));
        assertNull(graph.getGraphQLSchema().getType("PulseInput"));
    }

    private void legacy(Fixture f) {
        var graph = GraphqlApiRuntime.createLegacy(SCHEMA, f.app, MANIFESTS);
        try {
            var request = input("mutation($input: String!) { configure(input: $input) }",
                    Map.of("input", new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(values())), "legacy");
            var result = graph.execute(request);
            assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
            Map<?, ?> data = result.getData();
            assertTrue(new com.fasterxml.jackson.databind.ObjectMapper().readTree((String) data.get("configure")).path("success").asBoolean());
            assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().readTree((String) data.get("configure")).has("changes"));
            f.allowed.set(false);
            assertFalse(graph.execute(request).getErrors().isEmpty());
        } catch (java.io.IOException failed) { throw new AssertionError(failed); }
    }

    private void contract(Fixture f) {
        String sdl = new GraphqlContractGenerator().generate(SCHEMA);
        assertTrue(sdl.contains("input ConfigureInput"));
        assertTrue(sdl.contains("item: ID!"));
        assertTrue(sdl.contains("state: Status!"));
        assertTrue(sdl.contains("matrix: [[Int!]!]"));
        assertTrue(sdl.contains("affectedObjects: [AffectedObject!]"));
        assertDoesNotThrow(() -> new graphql.schema.idl.SchemaParser().parse(sdl));
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app, MANIFESTS);
        var input = (graphql.schema.GraphQLInputObjectType) graph.getGraphQLSchema().getType("ConfigureInput");
        assertEquals("ID!", graphql.schema.GraphQLTypeUtil.simplePrint(input.getFieldDefinition("item").getType()));
        assertEquals("Status!", graphql.schema.GraphQLTypeUtil.simplePrint(input.getFieldDefinition("state").getType()));
    }

    private static ExecutionInput input(String query, Map<String, Object> variables, String key) {
        var context = new java.util.HashMap<String, Object>();
        context.put("request", new ApiRequestContext(CONTEXT, PRINCIPAL));
        if (key != null) context.put("idempotencyKey", key);
        return ExecutionInput.newExecutionInput(query).variables(variables).graphQLContext(context).build();
    }

    private static Map<String, Object> values() {
        return Map.of("item", "item", "state", "DONE", "deadline", "2030-01-01T00:00:00Z", "duration", "PT1H",
                "url", "https://example.invalid", "position", Map.of("lat", 30, "lon", 120), "payload", Map.of("source", "test"),
                "states", List.of("READY", "DONE"), "matrix", List.of(List.of(1, 2), List.of(3)));
    }

    private static final class Fixture {
        final StorageProvider storage;
        final java.util.concurrent.atomic.AtomicBoolean allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
        final ApplicationService app;
        Fixture(StorageProvider storage) {
            this.storage = storage;
            app = new ApplicationService(storage, new AuthorizationService((principal, permission, key) -> allowed.get()), new ActionExecutor(), SCHEMA, MANIFESTS, Map.of());
        }
    }
}
