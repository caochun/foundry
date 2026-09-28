package org.openfoundry.foundation.api;

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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OntologyAuthorizationTest {
    static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name: "permissions", version: "1")
            type Book @objectType { id: ID! @primary name: String! borrower: Member @link(type: "Loan") }
            type Member @objectType { id: ID! @primary name: String! books: [Book!] @link(type: "Loan", direction: INBOUND) }
            type Notice @objectType { id: ID! @primary name: String! }
            type Loan @linkType(from: "Book", to: "Member", cardinality: MANY_TO_ONE) { id: ID! @primary }
            type NoticeFor @linkType(from: "Notice", to: "Book", cardinality: MANY_TO_ONE) { id: ID! @primary }
            type Borrow @actionType(permission: "can_borrow") { note: String @param book: Book! @param member: Member! @param }
            type Create @actionType(permission: "can_create") { name: String! @param }
            """);
    static final RequestContext CONTEXT = RequestContext.system("tenant", "user");
    static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("user", "tenant", Set.of("librarian"));
    static final ActionManifest BORROW = new ActionManifest("Borrow", 1, false, List.of(), List.of(
            new ActionManifest.UpdateObject("book", Map.of("name", "Borrowed")),
            new ActionManifest.CreateLink("Loan", "book", "member", Map.of())));

    @TestFactory
    Stream<DynamicTest> targetsAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "primary action relation and secondary viewer use declared object types", this::primary),
                test(provider, "participant visibility and current permission govern replay", this::participants),
                test(provider, "secondary object modification requires editor and rolls back all effects", this::secondaryWrite),
                test(provider, "created resources do not require pre-existing grants", this::created),
                test(provider, "objectless actions remain explicitly authorized", this::creationGate),
                test(provider, "legacy and malformed access proofs never imply creation authority", this::legacyReceipt),
                test(provider, "collection targets require every target and cannot fall back to a participant", this::collectionTargets)));
    }

    private DynamicTest test(String provider, String name, Consumer<Fixture> test) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> {
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider();
            else {
                var data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:ontology_auth_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2());
            }
            storage.applySchema(CONTEXT, SCHEMA);
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.createObject("Book", "b", Map.of("name", "Initial"));
                tx.createObject("Member", "m", Map.of("name", "Member"));
                tx.commit();
            }
            var f = new Fixture(storage);
            f.grants.add("Book/b#can_borrow");
            f.grants.add("Member/m#viewer");
            try { test.accept(f); }
            finally { if (storage instanceof AutoCloseable closeable) closeable.close(); }
        });
    }

    private void primary(Fixture f) {
        var app = f.app(BORROW);
        var result = f.execute(app, BORROW, "borrow");
        assertTrue(result.success());
        assertEquals(result, f.execute(app, BORROW, "borrow"));
        assertEquals(2, f.storage.getObject(CONTEXT, "Book", "b").version());
        f.grants.add("Book/b#viewer");
        assertEquals("m", ((ObjectRecord) app.readLinkField(CONTEXT, PRINCIPAL, new EntityKey("Book", "b"), "borrower", QueryOptions.defaults())).id());
        assertEquals(1, app.history(CONTEXT, PRINCIPAL, result.affected().getLast()).size());
        assertTrue(f.checks.stream().noneMatch(check -> check.startsWith("Loan/") || check.startsWith("ActionType/")));
        assertTrue(f.checks.stream().noneMatch(check -> check.equals("Member/m#can_borrow")));
    }

    private void participants(Fixture f) {
        var app = f.app(BORROW);
        f.grants.remove("Member/m#viewer");
        assertThrows(SecurityException.class, () -> f.execute(app, BORROW, "borrow"));
        assertEquals(1, f.storage.getObject(CONTEXT, "Book", "b").version());
        f.grants.add("Member/m#viewer");
        var result = f.execute(app, BORROW, "borrow");
        f.grants.remove("Book/b#can_borrow");
        assertThrows(SecurityException.class, () -> f.execute(app, BORROW, "borrow"));
        f.grants.add("Book/b#can_borrow");
        f.grants.remove("Member/m#viewer");
        assertThrows(SecurityException.class, () -> f.execute(app, BORROW, "borrow"));
        f.grants.add("Member/m#viewer");
        assertEquals(result, f.execute(app, BORROW, "borrow"));
    }

    private void secondaryWrite(Fixture f) {
        var action = new ActionManifest("Borrow", 1, false, List.of(), List.of(
                new ActionManifest.UpdateObject("book", Map.of("name", "Changed")),
                new ActionManifest.UpdateObject("member", Map.of("name", "Edited"))));
        var app = f.app(action);
        var invalidWithoutEditor = new ActionManifest("Borrow", 1, false, List.of(), List.of(
                new ActionManifest.UpdateObject("member", Map.of("name", "params.note"))));
        assertThrows(SecurityException.class, () -> f.execute(f.app(invalidWithoutEditor), invalidWithoutEditor, "not-a-validation-oracle"));
        assertThrows(SecurityException.class, () -> f.execute(app, action, "edit"));
        assertEquals("Initial", f.storage.getObject(CONTEXT, "Book", "b").properties().get("name"));
        assertEquals(1, f.storage.getEntityHistory(CONTEXT, new EntityKey("Book", "b")).size());
        f.grants.add("Member/m#editor");
        var done = f.execute(app, action, "edit");
        assertTrue(done.success());
        f.grants.remove("Member/m#editor");
        assertThrows(SecurityException.class, () -> f.execute(app, action, "edit"));
        assertEquals("Edited", f.storage.getObject(CONTEXT, "Member", "m").properties().get("name"));
    }

    private void created(Fixture f) {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var action = new ActionManifestParser().parse("""
                action: Borrow
                version: 1
                effects:
                  - type: createObject
                    objectType: Notice
                    properties: {name: New}
                  - type: updateObject
                    target: notice
                    set: {name: Edited}
                  - type: createLink
                    linkType: NoticeFor
                    from: notice
                    to: book
                  - type: deleteLink
                    linkType: NoticeFor
                    filter: {from: notice}
                    expect: ONE
                sideEffects:
                  - name: event
                    type: event
                    config: {type: example.created, data: {id: notice.id}}
                """);
        var app = new ApplicationService(f.storage, new AuthorizationService(f::check),
                new ActionExecutor().withSideEffects(invocation -> calls.incrementAndGet()), SCHEMA, Map.of("Borrow", action), Map.of(), AuthorizationMode.ONTOLOGY_TARGETS);
        var result = f.execute(app, action, "create");
        assertTrue(result.success());
        assertEquals(result, app.resume(CONTEXT, PRINCIPAL, "Borrow", result.actionId()));
        assertEquals(result, f.execute(app, action, "create"));
        assertEquals(1, calls.get());
        assertNull(app.getObject(CONTEXT, PRINCIPAL, "Notice", result.affected().getFirst().id()));
        assertTrue(f.checks.stream().noneMatch(check -> check.startsWith("Notice/") && !check.endsWith("#viewer")));
    }

    private void creationGate(Fixture f) {
        var action = new ActionManifest("Create", 1, false, List.of(), List.of(new ActionManifest.CreateObject("Notice", null, Map.of("name", "params.name"))));
        var app = f.app(action);
        assertThrows(SecurityException.class, () -> app.execute(action, CONTEXT, PRINCIPAL, Map.of("name", "New"), "new"));
        f.grants.add("ActionType/Create#can_create");
        var result = app.execute(action, CONTEXT, PRINCIPAL, Map.of("name", "New"), "new");
        assertTrue(result.success());
        assertEquals(result, app.execute(action, CONTEXT, PRINCIPAL, Map.of("name", "New"), "new"));
    }

    private void legacyReceipt(Fixture f) {
        var action = new ActionManifest("Create", 1, false, List.of(), List.of(new ActionManifest.CreateObject("Notice", null, Map.of("name", "params.name"))));
        f.grants.add("ActionType/Create#can_create");
        var initial = f.app(action).execute(action, CONTEXT, PRINCIPAL, Map.of("name", "New"), "legacy");
        for (String proof : List.of("legacy", "invalid", "mismatched")) {
            StorageProvider wrapped = (StorageProvider) java.lang.reflect.Proxy.newProxyInstance(StorageProvider.class.getClassLoader(), new Class<?>[]{StorageProvider.class}, (proxy, method, args) -> {
                try {
                    Object value = method.invoke(f.storage, args);
                    if (value instanceof Transaction tx) {
                        return java.lang.reflect.Proxy.newProxyInstance(Transaction.class.getClassLoader(), new Class<?>[]{Transaction.class}, (ignored, operation, parameters) -> {
                            try {
                                Object result = operation.invoke(tx, parameters);
                                if (result instanceof CommandReceipt receipt) {
                                    var data = new java.util.LinkedHashMap<>(receipt.result());
                                    if (proof.equals("invalid")) data.put("access", "invalid");
                                    else if (proof.equals("mismatched")) data.put("affected", List.of(Map.of("type", "Notice", "id", "unrelated")));
                                    else data.remove("access");
                                    return new CommandReceipt(receipt.key(), receipt.actorId(), receipt.action(), receipt.requestHash(), data);
                                }
                                return result;
                            } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                        });
                    }
                    return value;
                } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
            });
            var app = new ApplicationService(wrapped, new AuthorizationService(f::check), new ActionExecutor(), SCHEMA,
                    Map.of("Create", action), Map.of(), AuthorizationMode.ONTOLOGY_TARGETS);
            if (!proof.equals("legacy")) {
                assertThrows(IllegalStateException.class, () -> app.execute(action, CONTEXT, PRINCIPAL, Map.of("name", "New"), "legacy"));
            } else {
                assertThrows(SecurityException.class, () -> app.execute(action, CONTEXT, PRINCIPAL, Map.of("name", "New"), "legacy"));
                f.grants.add("Notice/" + initial.affected().getFirst().id() + "#editor");
                var replayed = app.execute(action, CONTEXT, PRINCIPAL, Map.of("name", "New"), "legacy");
                assertEquals(initial.actionId(), replayed.actionId());
                assertEquals(initial.affected(), replayed.affected());
                assertTrue(replayed.success());
                assertEquals(ActionResult.ChangeType.UNKNOWN, replayed.changes().getFirst().changeType());
            }
        }
        assertEquals(1, f.storage.queryObjects(CONTEXT, "Notice", QueryOptions.defaults()).size());
    }

    private void collectionTargets(Fixture f) {
        var definition = new org.openfoundry.foundation.spi.schema.ActionTypeDefinition("Batch", List.of(
                new org.openfoundry.foundation.spi.schema.ActionParameter("books", "[Book!]", true),
                new org.openfoundry.foundation.spi.schema.ActionParameter("member", "Member", true)), "can_borrow");
        var schema = new OntologySchema(SCHEMA.namespace(), SCHEMA.version(), SCHEMA.objectTypes(), SCHEMA.linkTypes(), List.of(definition));
        if (f.storage instanceof JdbcStorageProvider jdbc) {
            jdbc.activateSchema(CONTEXT, schema, new org.openfoundry.foundation.schema.MigrationPlan("Replace Action declarations for collection-target authorization test", true), jdbc.boundSchemaVersion());
        } else f.storage.applySchema(CONTEXT, schema);
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.createObject("Book", "other", Map.of("name", "Other"));
            tx.commit();
        }
        var action = new ActionManifest("Batch", 1, false, List.of(), List.of());
        var app = new ApplicationService(f.storage, new AuthorizationService(f::check), new ActionExecutor(), schema,
                Map.of("Batch", action), Map.of(), AuthorizationMode.ONTOLOGY_TARGETS);
        var values = Map.<String, Object>of("books", List.of("b", "other"), "member", "m");
        assertThrows(SecurityException.class, () -> app.execute(action, CONTEXT, PRINCIPAL, values, "batch"));
        f.grants.add("Book/other#can_borrow");
        assertTrue(app.execute(action, CONTEXT, PRINCIPAL, values, "batch").success());
        assertThrows(SecurityException.class, () -> app.execute(action, CONTEXT, PRINCIPAL,
                Map.of("books", List.of(), "member", "m"), "empty"));
    }

    private static final class Fixture {
        final StorageProvider storage;
        final Set<String> grants = new HashSet<>();
        final java.util.List<String> checks = new java.util.ArrayList<>();
        final Map<String, Set<String>> relations = Map.of("Book", Set.of("viewer", "editor", "can_borrow"),
                "Member", Set.of("viewer", "editor"), "Notice", Set.of("viewer", "editor"), "ActionType", Set.of("can_create"));
        Fixture(StorageProvider storage) { this.storage = storage; }
        boolean check(SecurityPrincipal principal, String relation, EntityKey key) {
            assertTrue(relations.getOrDefault(key.type(), Set.of()).contains(relation), "Undeclared relation: " + key.type() + "." + relation);
            String check = key.type() + "/" + key.id() + "#" + relation;
            checks.add(check);
            return grants.contains(check);
        }
        ApplicationService app(ActionManifest action) {
            return new ApplicationService(storage, new AuthorizationService(this::check), new ActionExecutor(), SCHEMA,
                    Map.of(action.action(), action), Map.of(), AuthorizationMode.ONTOLOGY_TARGETS);
        }
        ActionResult execute(ApplicationService app, ActionManifest action, String key) {
            return app.execute(action, CONTEXT, PRINCIPAL, Map.of("book", "b", "member", "m"), key);
        }
    }
}
