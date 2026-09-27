package org.openfoundry.foundation.conformance;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.*;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PropertyConformanceTest {
    static final RequestContext CONTEXT = RequestContext.system("properties", "tester");
    static PropertyDefinition field(String name, String type, boolean required, boolean unique, boolean immutable) {
        return new PropertyDefinition(name, type, required, false, unique, false, false, immutable);
    }
    static final PropertyDefinition ID = new PropertyDefinition("id", "ID", true, true, false, false, false, true);
    static final OntologySchema SCHEMA = new OntologySchema("validation", "0.1.0",
            List.of(new ObjectTypeDefinition("Item", List.of(ID, field("name", "String", true, false, false),
                    field("serial", "String", false, true, false), field("fixed", "String", false, false, true),
                    field("count", "Int", false, false, false), field("when", "DateTime", false, true, false),
                    field("state", "Status", false, false, false), field("labels", "[String!]", false, false, false),
                    field("details", "JSON", false, false, false)))),
            List.of(new LinkTypeDefinition("Related", "Item", "Item", Cardinality.MANY_TO_ONE,
                    List.of(ID, field("role", "String", true, false, false), field("code", "String", false, true, false)))),
            List.of(), Map.of("Status", List.of("ACTIVE", "INACTIVE")));

    @TestFactory
    Stream<DynamicTest> bothProvidersValidateTheSameValues() {
        return Stream.of("memory", "jdbc").flatMap(name -> Stream.of(
                check(name, "required/type/unknown/primary validation", this::invalidCreates),
                check(name, "merged state and immutable fields", this::updates),
                check(name, "enum and list element types", this::enumsAndLists),
                check(name, "transactional uniqueness and release", this::uniqueLifecycle),
                check(name, "tenant and type-scoped unique values", this::tenantIsolation),
                check(name, "relationship properties and uniqueness", this::links),
                check(name, "structured values cannot mutate history", this::immutableValues),
                check(name, "dates normalize before uniqueness checking", this::dateUniqueness),
                check(name, "a rejected later constraint does not release a unique claim", this::rejectedUpdate)));
    }

    private DynamicTest check(String name, String label, Consumer<StorageProvider> test) {
        return DynamicTest.dynamicTest(name + " " + label, () -> {
            StorageProvider storage;
            if (name.equals("memory")) storage = new InMemoryStorageProvider();
            else {
                var data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:property_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2());
            }
            storage.applySchema(CONTEXT, SCHEMA);
            test.accept(storage);
        });
    }

    private void invalidCreates(StorageProvider storage) {
        for (var values : List.of(Map.<String, Object>of(), Map.<String, Object>of("name", 12),
                Map.<String, Object>of("name", "safe", "unknown", "private"), Map.<String, Object>of("name", "safe", "id", "different"))) {
            try (var tx = storage.beginTransaction(CONTEXT)) {
                var error = assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "a", values));
                assertFalse(error.getMessage().contains("private"));
            }
        }
        try (var tx = storage.beginTransaction(CONTEXT)) {
            assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", " ", Map.of("name", "valid")));
            assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "x".repeat(513), Map.of("name", "valid")));
            tx.commit();
        }
        assertTrue(storage.queryObjects(CONTEXT, "Item", QueryOptions.defaults()).isEmpty());
        assertTrue(storage.getEntityHistory(CONTEXT, new EntityKey("Item", "a")).isEmpty());
    }

    private void updates(StorageProvider storage) {
        create(storage, "a", Map.of("name", "original", "fixed", "immutable"));
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Item", "a", Map.of("count", 3L), 1);
            tx.commit();
        }
        var clear = new HashMap<String, Object>();
        clear.put("name", null);
        for (var patch : List.of(clear, Map.<String, Object>of("fixed", "immutable"), Map.<String, Object>of("count", "3"))) {
            try (var tx = storage.beginTransaction(CONTEXT)) {
                assertThrows(PropertyValidationException.class, () -> tx.updateObject("Item", "a", patch, 2));
            }
        }
        assertEquals(2, storage.getObject(CONTEXT, "Item", "a").version());
        assertEquals("original", storage.getObject(CONTEXT, "Item", "a").properties().get("name"));
        assertEquals(2, storage.getEntityHistory(CONTEXT, new EntityKey("Item", "a")).size());
    }

    private void enumsAndLists(StorageProvider storage) {
        for (var values : List.of(Map.<String, Object>of("name", "a", "state", "INVALID"),
                Map.<String, Object>of("name", "a", "labels", List.of(1)),
                Map.<String, Object>of("name", "a", "labels", java.util.Arrays.asList("x", null)))) {
            try (var tx = storage.beginTransaction(CONTEXT)) {
                assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "a", values));
            }
        }
        create(storage, "a", Map.of("name", "valid", "state", "ACTIVE", "labels", List.of("x")));
        assertEquals("ACTIVE", storage.getObject(CONTEXT, "Item", "a").properties().get("state"));
    }

    private void uniqueLifecycle(StorageProvider storage) {
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "a", Map.of("name", "a", "serial", "one"));
            assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "b", Map.of("name", "b", "serial", "one")));
            tx.updateObject("Item", "a", Map.of("serial", "two"), 1);
            tx.createObject("Item", "b", Map.of("name", "b", "serial", "one"));
            assertThrows(PropertyValidationException.class, () -> tx.updateObject("Item", "b", Map.of("serial", "two"), 1));
            tx.deleteObject("Item", "a", 2);
            tx.updateObject("Item", "b", Map.of("serial", "two"), 1);
            tx.commit();
        }
        assertEquals("two", storage.getObject(CONTEXT, "Item", "b").properties().get("serial"));
        try (var tx = storage.beginTransaction(CONTEXT)) {
            var patch = new HashMap<String, Object>(); patch.put("serial", null);
            tx.updateObject("Item", "b", patch, 2);
            tx.createObject("Item", "c", Map.of("name", "c", "serial", "two"));
            tx.rollback();
        }
        assertNull(storage.getObject(CONTEXT, "Item", "c"));
        try (var tx = storage.beginTransaction(CONTEXT)) {
            assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "c", Map.of("name", "c", "serial", "two")));
        }
    }

    private void tenantIsolation(StorageProvider storage) {
        create(storage, "a", Map.of("name", "a", "serial", "one"));
        var other = RequestContext.system("different", "tester");
        try (var tx = storage.beginTransaction(other)) {
            tx.createObject("Item", "a", Map.of("name", "other", "serial", "one"));
            tx.commit();
        }
        assertEquals("other", storage.getObject(other, "Item", "a").properties().get("name"));
        assertEquals("a", storage.getObject(CONTEXT, "Item", "a").properties().get("name"));
        var left = RequestContext.system("left", "tester");
        var right = RequestContext.system("left|Item|middle", "tester");
        try (var tx = storage.beginTransaction(left)) {
            tx.createObject("Item", "middle|Item|id", Map.of("name", "must-not-leak"));
            tx.commit();
        }
        assertNull(storage.getObject(right, "Item", "id"));
        assertTrue(storage.queryObjects(right, "Item", QueryOptions.defaults()).isEmpty());
        assertTrue(storage.getEntityHistory(right, new EntityKey("Item", "id")).isEmpty());

    }

    private void links(StorageProvider storage) {
        for (String id : List.of("a", "b", "c")) create(storage, id, Map.of("name", id));
        try (var tx = storage.beginTransaction(CONTEXT)) {
            var a = new EntityKey("Item", "a"); var b = new EntityKey("Item", "b"); var c = new EntityKey("Item", "c");
            assertThrows(PropertyValidationException.class, () -> tx.createLink("Related", "missing", a, b, Map.of()));
            tx.createLink("Related", "first", a, b, Map.of("role", "member", "code", "unique"));
            assertThrows(PropertyValidationException.class, () -> tx.createLink("Related", "second", c, b, Map.of("role", "member", "code", "unique")));
            tx.deleteLink("Related", "first", 1);
            tx.createLink("Related", "second", c, b, Map.of("role", "member", "code", "unique"));
            tx.commit();
        }
        assertEquals("unique", storage.getLink(CONTEXT, "Related", "second").properties().get("code"));
    }

    @SuppressWarnings("unchecked")
    private void immutableValues(StorageProvider storage) {
        var labels = new ArrayList<>(List.of("before"));
        var nested = new HashMap<String, Object>(); nested.put("value", "before");
        create(storage, "a", Map.of("name", "a", "labels", labels, "details", nested));
        labels.add("after"); nested.put("value", "after");
        var record = storage.getObject(CONTEXT, "Item", "a");
        assertEquals(List.of("before"), record.properties().get("labels"));
        assertThrows(UnsupportedOperationException.class, () -> ((Map<String, Object>) record.properties().get("details")).put("value", "mutated"));
        assertEquals("before", ((Map<?, ?>) storage.getObjectAtVersion(CONTEXT, "Item", "a", 1).state().get("details")).get("value"));
    }

    private void dateUniqueness(StorageProvider storage) {
        create(storage, "a", Map.of("name", "a", "when", "2030-01-01T08:00:00+08:00"));
        try (var tx = storage.beginTransaction(CONTEXT)) {
            assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "b", Map.of("name", "b", "when", "2030-01-01T00:00:00Z")));
            assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "b", Map.of("name", "b", "when", "yesterday")));
        }
    }

    private void rejectedUpdate(StorageProvider storage) {
        create(storage, "a", Map.of("name", "a", "serial", "occupied"));
        try (var tx = storage.beginTransaction(CONTEXT)) {
            var future = java.time.Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            assertThrows(IllegalArgumentException.class, () -> tx.updateObject("Item", "a", Map.of("serial", "new"), 1, future));
            assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "b", Map.of("name", "b", "serial", "occupied")));
            tx.commit();
        }
        assertEquals(1, storage.getObject(CONTEXT, "Item", "a").version());
        assertEquals("occupied", storage.getObject(CONTEXT, "Item", "a").properties().get("serial"));
        assertNull(storage.getObject(CONTEXT, "Item", "b"));
    }

    private void create(StorageProvider storage, String id, Map<String, Object> properties) {
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.createObject("Item", id, properties);
            tx.commit();
        }
    }
}
