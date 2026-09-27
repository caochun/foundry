package org.openfoundry.foundation.conformance;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.schema.SchemaCompiler;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.*;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class DeclarativePropertiesTest {
    static final String ODL = """
            extend schema @namespace(name: "declared", version: "0.1.0")
            interface Identifiable { id: ID! @primary }
            interface Auditable {
              createdAt: DateTime! @readonly
              createdBy: String! @readonly
              updatedAt: DateTime! @readonly
              updatedBy: String! @readonly
            }
            interface Named implements Identifiable { name: String! @constraint(expr: "size(value) > 0") }
            enum State { READY DONE }
            type Item implements Named & Auditable @objectType @constraint(expr: "this.reserved <= this.quantity") {
              quantity: Int! @default(value: 5) @constraint(expr: "value >= 0")
              reserved: Int! @default(value: 0)
              amount: Float @constraint(expr: "value > 0")
              state: State! @default(value: READY)
              labels: [String!] @default(value: ["initial"])
              details: JSON @default(value: {source: "fixture", flags: [true, false]})
              fixed: String @immutable @constraint(expr: "value.matches('^[A-Z]+$')")
              engineKind: String! @readonly @default(value: "literal")
            }
            type Related implements Identifiable & Auditable @linkType(from: "Item", to: "Item", cardinality: MANY_TO_MANY)
              @constraint(expr: "this.weight <= this.limit") {
              weight: Int! @default(value: 1) @constraint(expr: "value > 0")
              limit: Int! @default(value: 5)
            }
            """;
    static final RequestContext CONTEXT = RequestContext.system("tenant", "creator");
    static final Instant START = Instant.parse("2030-01-01T00:00:00Z");

    @TestFactory
    Stream<DynamicTest> declarationsExecuteInBothProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                check(provider, "creation defaults and inherited audit fields", this::defaults),
                check(provider, "readonly cannot be spoofed on create or update", this::readonly),
                check(provider, "merged state constraints apply to untouched dependent fields", this::constraints),
                check(provider, "explicit null and updates do not reapply defaults", this::nulls),
                check(provider, "relationships use the same declarations", this::links),
                check(provider, "failed constraints never create history", this::failures),
                check(provider, "programmatic schema cannot bypass inheritance", this::invalidInheritance),
                check(provider, "schema defaults are not retroactive", this::newDefaults),
                check(provider, "evaluation errors cannot write facts", this::evaluationErrors)));
    }

    private DynamicTest check(String provider, String label, Consumer<Fixture> test) {
        return DynamicTest.dynamicTest(provider + " " + label, () -> {
            var schema = new SchemaCompiler().compile(new OdlParser().parse(ODL)).schema();
            var clock = new TestClock();
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider(clock);
            else {
                var data = new JdbcDataSource(); data.setURL("jdbc:h2:mem:declarations_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
            }
            storage.applySchema(CONTEXT, schema);
            test.accept(new Fixture(storage, clock));
        });
    }

    private void defaults(Fixture f) {
        create(f, "a", Map.of("name", "A", "amount", 1.5));
        var first = f.storage.getObject(CONTEXT, "Item", "a");
        assertEquals(5, first.properties().get("quantity"));
        assertEquals("READY", first.properties().get("state"));
        assertEquals(List.of("initial"), first.properties().get("labels"));
        assertEquals(Map.of("source", "fixture", "flags", List.of(true, false)), first.properties().get("details"));
        assertEquals(START.toString(), first.properties().get("createdAt"));
        assertEquals("creator", first.properties().get("createdBy"));
        assertEquals("literal", first.properties().get("engineKind"));
        f.clock.now = START.plusSeconds(10);
        try (var tx = f.storage.beginTransaction(RequestContext.system("tenant", "editor"))) {
            tx.updateObject("Item", "a", Map.of("name", "B"), 1); tx.commit();
        }
        var after = f.storage.getObject(CONTEXT, "Item", "a");
        assertEquals("creator", after.properties().get("createdBy"));
        assertEquals(START.toString(), after.properties().get("createdAt"));
        assertEquals("editor", after.properties().get("updatedBy"));
        assertEquals(f.clock.now.toString(), after.properties().get("updatedAt"));
        assertEquals("A", f.storage.getObjectAtTime(CONTEXT, "Item", "a", START, f.clock.now).state().get("name"));
    }

    private void readonly(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "bad", Map.of("name", "Bad", "createdBy", "forged")));
        }
        create(f, "a", Map.of("name", "A", "fixed", "ABC"));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(PropertyValidationException.class, () -> tx.updateObject("Item", "a", Map.of("updatedAt", START.toString()), 1));
            assertThrows(PropertyValidationException.class, () -> tx.updateObject("Item", "a", Map.of("engineKind", "other"), 1));
            assertThrows(PropertyValidationException.class, () -> tx.updateObject("Item", "a", Map.of("fixed", "ABC"), 1));
        }
        assertEquals(1, f.storage.getObject(CONTEXT, "Item", "a").version());
    }

    private void constraints(Fixture f) {
        create(f, "a", Map.of("name", "A", "reserved", 4));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            var failed = assertThrows(PropertyValidationException.class, () -> tx.updateObject("Item", "a", Map.of("quantity", 3), 1));
            assertEquals("$type", failed.field());
            assertEquals("CONSTRAINT_VIOLATION", failed.code());
            tx.updateObject("Item", "a", Map.of("quantity", 3, "reserved", 2), 1); tx.commit();
        }
        assertEquals(3, f.storage.getObject(CONTEXT, "Item", "a").properties().get("quantity"));
    }

    private void nulls(Fixture f) {
        var input = new java.util.HashMap<String, Object>(); input.put("name", "A"); input.put("quantity", null);
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "bad", input));
        }
        create(f, "a", Map.of("name", "A"));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            var clear = new java.util.HashMap<String, Object>(); clear.put("labels", null);
            tx.updateObject("Item", "a", clear, 1); tx.commit();
        }
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Item", "a", Map.of("name", "B"), 2); tx.commit();
        }
        assertNull(f.storage.getObject(CONTEXT, "Item", "a").properties().get("labels"));
    }

    private void links(Fixture f) {
        create(f, "a", Map.of("name", "A")); create(f, "b", Map.of("name", "B"));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.createLink("Related", "edge", new EntityKey("Item", "a"), new EntityKey("Item", "b"), Map.of()); tx.commit();
        }
        assertEquals(1, f.storage.getLink(CONTEXT, "Related", "edge").properties().get("weight"));
        assertEquals("creator", f.storage.getLink(CONTEXT, "Related", "edge").properties().get("createdBy"));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(PropertyValidationException.class, () -> tx.updateLink("Related", "edge", Map.of("limit", 0), 1));
        }
        assertEquals(1, f.storage.getEntityHistory(CONTEXT, new EntityKey("Related", "edge")).size());
    }

    private void failures(Fixture f) {
        for (var input : List.of(Map.<String, Object>of("name", ""), Map.<String, Object>of("name", "A", "amount", -1.5),
                Map.<String, Object>of("name", "A", "fixed", "lowercase"), Map.<String, Object>of("name", "A", "quantity", -1))) {
            try (var tx = f.storage.beginTransaction(CONTEXT)) {
                assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "bad", input));
            }
        }
        assertTrue(f.storage.getEntityHistory(CONTEXT, new EntityKey("Item", "bad")).isEmpty());
        assertTrue(f.storage.queryObjects(CONTEXT, "Item", QueryOptions.defaults()).isEmpty());
    }

    private void invalidInheritance(Fixture f) {
        var parsed = new OdlParser().parse(ODL);
        var item = parsed.objectTypes().getFirst();
        var missingField = item.properties().stream().filter(field -> !field.name().equals("createdAt")).toList();
        var bad = new OntologySchema(parsed.namespace(), parsed.version(),
                List.of(new ObjectTypeDefinition(item.name(), missingField, item.interfaces(), item.constraints())),
                parsed.linkTypes(), parsed.actionTypes(), parsed.enums(), parsed.interfaces());
        assertThrows(IllegalArgumentException.class, () -> f.storage.applySchema(CONTEXT, bad));
        var cycle = new OntologySchema("cycle", "1", List.of(), List.of(), List.of(), Map.of(), List.of(
                new InterfaceDefinition("A", List.of(), List.of("B"), List.of()),
                new InterfaceDefinition("B", List.of(), List.of("A"), List.of())));
        assertThrows(IllegalArgumentException.class, () -> f.storage.applySchema(CONTEXT, cycle));
        var missingConstraints = new OntologySchema("bad", "1", List.of(
                new ObjectTypeDefinition("Item", item.properties(), List.of("Rule"), List.of())),
                List.of(), List.of(), parsed.enums(), List.of(new InterfaceDefinition("Rule", List.of(), List.of(), List.of("false"))));
        assertThrows(IllegalArgumentException.class, () -> f.storage.applySchema(CONTEXT, missingConstraints));
        // Invalid applications leave the active valid schema usable.
        create(f, "a", Map.of("name", "A"));
    }

    private void newDefaults(Fixture f) {
        create(f, "old", Map.of("name", "Old"));
        var next = new OdlParser().parse(ODL.replace("quantity: Int!", "added: String @default(value: \"new\") quantity: Int!"));
        f.storage.applySchema(CONTEXT, next);
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Item", "old", Map.of("name", "Updated"), 1);
            tx.commit();
        }
        assertFalse(f.storage.getObject(CONTEXT, "Item", "old").properties().containsKey("added"));
        create(f, "new", Map.of("name", "New"));
        assertEquals("new", f.storage.getObject(CONTEXT, "Item", "new").properties().get("added"));
    }

    private void evaluationErrors(Fixture f) {
        var broken = new OdlParser().parse(ODL.replace("this.reserved <= this.quantity", "this.missing > 0"));
        f.storage.applySchema(CONTEXT, broken);
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            var error = assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "bad", Map.of("name", "A")));
            assertEquals("CONSTRAINT_EVALUATION_ERROR", error.code());
            tx.commit();
        }
        assertTrue(f.storage.queryObjects(CONTEXT, "Item", QueryOptions.defaults()).isEmpty());
        assertTrue(f.storage.getEntityHistory(CONTEXT, new EntityKey("Item", "bad")).isEmpty());
    }

    private void create(Fixture f, String id, Map<String, Object> input) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) { tx.createObject("Item", id, input); tx.commit(); }
    }
    private record Fixture(StorageProvider storage, TestClock clock) {}
    private static final class TestClock extends Clock {
        Instant now = START;
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
