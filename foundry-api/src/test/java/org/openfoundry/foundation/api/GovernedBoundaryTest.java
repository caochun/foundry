package org.openfoundry.foundation.api;

import graphql.ExecutionInput;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class GovernedBoundaryTest {
    private static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name: "boundary", version: "0.1.0")
            type Item @objectType {
              id: ID! @primary
              name: String!
              secret: String! @sensitive
            }
            type Rename @actionType(permission: "can_rename") {
              item: Item! @param
              name: String! @param
            }
            type Create @actionType(permission: "can_create") {
              id: ID! @param
              name: String! @param
            }
            """);
    private static final ActionManifest RENAME = new ActionManifest("Rename", 1, false, List.of(),
            List.of(new ActionManifest.UpdateObject("item", Map.of("name", "params.name"))));
    private static final ActionManifest CREATE = new ActionManifest("Create", 1, false, List.of(),
            List.of(new ActionManifest.CreateObject("Item", "params.id", Map.of("name", "params.name"))));
    private static final RequestContext CONTEXT = RequestContext.system("tenant", "reader");
    private static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("reader", "tenant", Set.of("viewer"));

    private InMemoryStorageProvider storage() {
        var storage = new InMemoryStorageProvider();
        storage.applySchema(CONTEXT, SCHEMA);
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "a", Map.of("name", "Initial", "secret", "synthetic", "unregistered", "must-not-leak"));
            tx.createObject("Item", "b", Map.of("name", "Other", "secret", "synthetic-other"));
            tx.commit();
        }
        return storage;
    }

    private ApplicationService app(InMemoryStorageProvider storage, RelationshipAuthorizer authorizer, Map<String, FieldPolicy> policies) {
        return new ApplicationService(storage, new AuthorizationService(authorizer),
                new ActionExecutor(ExpressionEvaluator.simple(), new InMemoryIdempotencyStore()),
                SCHEMA, Map.of("Rename", RENAME, "Create", CREATE), policies);
    }

    @Test
    void deniesRegisteredCreatesUpdatesAndReplaysWithoutWriting() {
        var storage = storage();
        var denied = app(storage, (p, relation, resource) -> false, Map.of());
        assertThrows(SecurityException.class, () -> denied.execute(CREATE, CONTEXT, PRINCIPAL, Map.of("id", "new", "name", "New"), "create"));
        assertThrows(SecurityException.class, () -> denied.execute(RENAME, CONTEXT, PRINCIPAL, Map.of("item", "a", "name", "Changed"), "rename"));
        assertNull(storage.getObject(CONTEXT, "Item", "new"));
        assertEquals(1, storage.getObject(CONTEXT, "Item", "a").version());
        assertTrue(storage.outboxEntries(CONTEXT).isEmpty());
        var allowed = new AtomicBoolean(true);
        var app = app(storage, (p, relation, resource) -> allowed.get(), Map.of());
        var input = Map.<String, Object>of("item", "a", "name", "Changed");
        var first = app.execute(RENAME, CONTEXT, PRINCIPAL, input, "rename");
        assertEquals(first, app.execute(RENAME, CONTEXT, PRINCIPAL, input, "rename"));
        allowed.set(false);
        assertThrows(SecurityException.class, () -> app.execute(RENAME, CONTEXT, PRINCIPAL, input, "rename"));
        assertEquals(2, storage.getObject(CONTEXT, "Item", "a").version());
    }

    @Test
    void requiresRegisteredEffectsAndObjectLevelPermissionAndMatchingIdentity() {
        var storage = storage();
        var app = app(storage, (p, relation, resource) -> resource.type().equals("ActionType") || resource.id().equals("a"), Map.of());
        assertThrows(SecurityException.class, () -> app.execute(RENAME, CONTEXT, PRINCIPAL, Map.of("item", "b", "name", "Denied"), null));
        var altered = new ActionManifest("Rename", 1, false, List.of(), List.of(new ActionManifest.UpdateObject("item", Map.of("secret", "stolen"))));
        assertThrows(SecurityException.class, () -> app.execute(altered, CONTEXT, PRINCIPAL, Map.of("item", "a", "name", "New"), null));
        assertThrows(SecurityException.class, () -> app.execute(RENAME, RequestContext.system("another-tenant", "reader"), PRINCIPAL, Map.of(), null));
        assertThrows(SecurityException.class, () -> app.history(RequestContext.system("tenant", "another-user"), PRINCIPAL, new EntityKey("Item", "a")));
        assertThrows(IllegalArgumentException.class, () -> app.execute(RENAME, CONTEXT, PRINCIPAL,
                Map.of("item", storage.getObject(CONTEXT, "Item", "a"), "name", "Forged"), null));
        assertThrows(IllegalArgumentException.class, () -> app.execute(RENAME, CONTEXT, PRINCIPAL, Map.of("item", "a", "name", 123), null));
        assertThrows(IllegalArgumentException.class, () -> app.execute(RENAME, CONTEXT, PRINCIPAL, Map.of("item", "a", "name", "New", "role", "admin"), null));
        assertTrue(app.execute(RENAME, CONTEXT, PRINCIPAL, Map.of("item", "a", "name", "Allowed"), null).success());
    }

    @Test
    void hidesSensitiveAndUnknownFieldsAcrossReadsHistoryAndGraphql() {
        var storage = storage();
        var app = app(storage, (p, relation, resource) -> true, Map.of());
        assertEquals(Map.of("name", "Initial"), app.getObject(CONTEXT, PRINCIPAL, "Item", "a").properties());
        assertTrue(app.listObjects(CONTEXT, PRINCIPAL, "Item", QueryOptions.defaults()).stream().noneMatch(o -> o.properties().containsKey("secret")));
        assertEquals(Map.of("name", "Initial"), app.history(CONTEXT, PRINCIPAL, new EntityKey("Item", "a")).getFirst().state());
        var gql = GraphqlApiRuntime.create(SCHEMA, app);
        var result = gql.execute(ExecutionInput.newExecutionInput("{ item(id: \"a\") { id name secret } }")
                .graphQLContext(Map.of("request", new ApiRequestContext(CONTEXT, PRINCIPAL))).build());
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        Map<String, Object> data = result.getData();
        Map<?, ?> item = (Map<?, ?>) data.get("item");
        assertEquals("Initial", item.get("name"));
        assertNull(item.get("secret"));
        assertEquals("synthetic", storage.getObject(CONTEXT, "Item", "a").properties().get("secret"));
        var legacy = new ApplicationService(storage, new AuthorizationService((p, relation, resource) -> true), new ActionExecutor());
        assertTrue(legacy.getObject(CONTEXT, PRINCIPAL, "Item", "a").properties().isEmpty());
        assertThrows(SecurityException.class, () -> legacy.execute(CREATE, CONTEXT, PRINCIPAL, Map.of(), null));
    }

    @Test
    void explicitFieldPolicyAlsoAppliesToHistoricalValues() {
        var storage = storage();
        var app = app(storage, (p, relation, resource) -> true, Map.of("Item", new FieldPolicy(Set.of("name"), Map.of("sensitive-reader", Set.of("secret")))));
        var privileged = new SecurityPrincipal("reader", "tenant", Set.of("sensitive-reader"));
        assertEquals("synthetic", app.getObject(CONTEXT, privileged, "Item", "a").properties().get("secret"));
        assertEquals("synthetic", app.history(CONTEXT, privileged, new EntityKey("Item", "a")).getFirst().state().get("secret"));
        assertFalse(app.history(CONTEXT, PRINCIPAL, new EntityKey("Item", "a")).getFirst().state().containsKey("secret"));
        assertFalse(app.getObject(CONTEXT, privileged, "Item", "a").properties().containsKey("unregistered"));
    }

    @Test
    void celPreconditionsUseServerResolvedObjectsAndCannotBeSpoofedByClientSnapshots() {
        var storage = storage();
        var manifest = new ActionManifest("Rename", 1, false,
                List.of(new ActionManifest.Precondition("item.name == 'Initial' && params.name != ''", "unexpected current value")), RENAME.effects());
        var app = new ApplicationService(storage, new AuthorizationService((p, r, e) -> true),
                new ActionExecutor(new CelExpressionEvaluator(), new InMemoryIdempotencyStore()), SCHEMA, Map.of("Rename", manifest), Map.of());
        assertTrue(app.execute(manifest, CONTEXT, PRINCIPAL, Map.of("item", "a", "name", "Updated"), "once").success());
        assertFalse(app.execute(manifest, CONTEXT, PRINCIPAL, Map.of("item", "a", "name", "Again"), "twice").success());
        assertEquals("Updated", storage.getObject(CONTEXT, "Item", "a").properties().get("name"));
        assertEquals(1, storage.outboxEntries(CONTEXT).size());
    }

    @Test
    void linkDeletionChecksBothEndpointsEvenWhenTheActionHasNoObjectParameter() {
        var schema = new OdlParser().parse("""
                extend schema @namespace(name: "links", version: "0.1.0")
                type Item @objectType { id: ID! @primary }
                type Related @linkType(from: "Item", to: "Item", cardinality: MANY_TO_MANY) { id: ID! @primary }
                type Unlink @actionType(permission: "can_unlink") { linkId: ID! @param }
                """);
        var storage = new InMemoryStorageProvider();
        storage.applySchema(CONTEXT, schema);
        try (var tx = storage.beginTransaction(CONTEXT)) {
            var a = tx.createObject("Item", "a", Map.of());
            var b = tx.createObject("Item", "b", Map.of());
            tx.createLink("Related", "edge", a.key(), b.key(), Map.of());
            tx.commit();
        }
        var manifest = new ActionManifest("Unlink", 1, false, List.of(), List.of(new ActionManifest.DeleteLink("Related", "params.linkId")));
        var policy = new AtomicBoolean(false);
        var app = new ApplicationService(storage, new AuthorizationService((p, r, e) -> !e.id().equals("b") || policy.get()),
                new ActionExecutor(ExpressionEvaluator.simple(), new InMemoryIdempotencyStore()), schema, Map.of("Unlink", manifest), Map.of());
        assertThrows(SecurityException.class, () -> app.execute(manifest, CONTEXT, PRINCIPAL, Map.of("linkId", "edge"), null));
        assertFalse(storage.getLink(CONTEXT, "Related", "edge").isDeleted());
        policy.set(true);
        var deleted = app.execute(manifest, CONTEXT, PRINCIPAL, Map.of("linkId", "edge"), "delete-once");
        assertTrue(deleted.success());
        assertTrue(storage.getLink(CONTEXT, "Related", "edge").isDeleted());
        assertEquals(deleted, app.execute(manifest, CONTEXT, PRINCIPAL, Map.of("linkId", "edge"), "delete-once"));
        assertEquals(2, storage.getLink(CONTEXT, "Related", "edge").version());
        policy.set(false);
        assertThrows(SecurityException.class, () -> app.execute(manifest, CONTEXT, PRINCIPAL, Map.of("linkId", "edge"), "delete-once"));
    }

    @Test
    void restAndGraphqlMutationUseTheSameGovernedBoundary() throws Exception {
        var storage = storage();
        var allow = new AtomicBoolean(false);
        var app = app(storage, (p, relation, resource) -> allow.get(), Map.of());
        try (var server = new JdkRestServer(0, new RestApiRouter(app), () -> new ApiRequestContext(CONTEXT, PRINCIPAL), Map.of("Rename", RENAME))) {
            server.start();
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/v1/actions/Rename"))
                    .header("Content-Type", "application/json").header("Idempotency-Key", "http-rename")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"a\",\"name\":\"HTTP\"}")).build();
            assertEquals(403, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            allow.set(true);
            assertEquals(200, HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals("HTTP", storage.getObject(CONTEXT, "Item", "a").properties().get("name"));
        }
        var gql = GraphqlApiRuntime.create(SCHEMA, app, Map.of("Rename", RENAME));
        String mutation = "mutation($input: String!) { rename(input: $input) }";
        var input = ExecutionInput.newExecutionInput(mutation).variables(Map.of("input", "{\"item\":\"a\",\"name\":\"GraphQL\"}"))
                .graphQLContext(Map.of("request", new ApiRequestContext(CONTEXT, PRINCIPAL))).build();
        assertTrue(gql.execute(input).getErrors().isEmpty());
        assertEquals("GraphQL", storage.getObject(CONTEXT, "Item", "a").properties().get("name"));
        allow.set(false);
        assertFalse(gql.execute(input).getErrors().isEmpty());
        assertEquals(3, storage.getObject(CONTEXT, "Item", "a").version());
    }
}
