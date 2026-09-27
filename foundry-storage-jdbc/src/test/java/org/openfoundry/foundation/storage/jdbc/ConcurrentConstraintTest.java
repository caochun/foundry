package org.openfoundry.foundation.storage.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrentConstraintTest {
    private static final RequestContext CONTEXT = RequestContext.system("tenant", "writer");
    private static final PropertyDefinition ID = new PropertyDefinition("id", "ID", true, true, false, false, false, true);
    private static final OntologySchema SCHEMA = new OntologySchema("locking", "0.1.0",
            List.of(new ObjectTypeDefinition("Item", List.of(ID, new PropertyDefinition("code", "String", true, false, true, false, false, false)))),
            List.of(new LinkTypeDefinition("Parent", "Item", "Item", Cardinality.MANY_TO_ONE, List.of(ID))), List.of());

    @Test
    void differentProviderInstancesCannotCommitTheSameUniqueValue() throws Exception {
        var data = data();
        var first = provider(data);
        var second = provider(data);
        var entered = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor(); var tx = first.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "a", Map.of("code", "shared"));
            var result = pool.submit(() -> {
                try (var other = second.beginTransaction(CONTEXT)) {
                    entered.countDown();
                    other.createObject("Item", "b", Map.of("code", "shared"));
                    other.commit();
                    return false;
                } catch (PropertyValidationException expected) {
                    return expected.code().equals("UNIQUE_PROPERTY");
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            tx.commit();
            assertTrue(result.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, first.queryObjects(CONTEXT, "Item", QueryOptions.defaults()).size());
        assertTrue(first.getEntityHistory(CONTEXT, new EntityKey("Item", "b")).isEmpty());
    }

    @Test
    void competingRelationshipsObeyCardinalityAcrossProviderInstances() throws Exception {
        var data = data();
        var first = provider(data);
        var second = provider(data);
        try (var tx = first.beginTransaction(CONTEXT)) {
            for (String id : List.of("child", "a", "b")) tx.createObject("Item", id, Map.of("code", id));
            tx.commit();
        }
        var entered = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor(); var tx = first.beginTransaction(CONTEXT)) {
            tx.createLink("Parent", "first", new EntityKey("Item", "child"), new EntityKey("Item", "a"), Map.of());
            var result = pool.submit(() -> {
                try (var other = second.beginTransaction(CONTEXT)) {
                    entered.countDown();
                    other.createLink("Parent", "second", new EntityKey("Item", "child"), new EntityKey("Item", "b"), Map.of());
                    other.commit();
                    return false;
                } catch (IllegalStateException rejected) {
                    return rejected.getMessage().contains("cardinality");
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            tx.commit();
            assertTrue(result.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, first.getLinks(CONTEXT, new EntityKey("Item", "child"), "Parent", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).size());
    }

    @Test
    void aRolledBackClaimDoesNotReserveTheValue() {
        var data = data();
        var first = provider(data);
        try (var tx = first.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "a", Map.of("code", "shared"));
        }
        var restarted = provider(data);
        try (var tx = restarted.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "b", Map.of("code", "shared"));
            tx.commit();
        }
        assertNull(restarted.getObject(CONTEXT, "Item", "a"));
        assertEquals("shared", restarted.getObject(CONTEXT, "Item", "b").properties().get("code"));
    }

    @Test
    void singleConnectionPoolsDoNotNeedANestedConnectionDuringWrites() {
        var pool = org.h2.jdbcx.JdbcConnectionPool.create("jdbc:h2:mem:single_" + System.nanoTime(), "sa", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(1);
        try {
            var storage = new JdbcStorageProvider(pool, DatabaseDialect.h2());
            storage.applySchema(CONTEXT, SCHEMA);
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.createObject("Item", "single", Map.of("code", "single"));
                tx.commit();
            }
            assertNotNull(storage.getObject(CONTEXT, "Item", "single"));
        } finally {
            pool.dispose();
        }
    }

    @Test
    void legacyDuplicateValuesRejectReuseButCanBeExplicitlyRepaired() throws Exception {
        var data = data();
        var storage = provider(data);
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "a", Map.of("code", "A"));
            tx.createObject("Item", "b", Map.of("code", "B"));
            tx.commit();
        }
        try (var connection = data.getConnection(); var statement = connection.prepareStatement(
                "UPDATE of_objects SET properties_json = ? WHERE tenant_id = ? AND object_type = 'Item' AND object_id = 'b'")) {
            statement.setString(1, "{\"code\":\"A\"}");
            statement.setString(2, CONTEXT.tenantId());
            statement.executeUpdate();
        }
        try (var tx = storage.beginTransaction(CONTEXT)) {
            var problem = assertThrows(PropertyValidationException.class, () -> tx.createObject("Item", "c", Map.of("code", "A")));
            assertEquals("LEGACY_UNIQUE_CONFLICT", problem.code());
        }
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Item", "b", Map.of("code", "B"), 1);
            tx.createObject("Item", "c", Map.of("code", "C"));
            tx.commit();
        }
        assertEquals(3, storage.queryObjects(CONTEXT, "Item", QueryOptions.defaults()).size());
        assertEquals("A", storage.getObject(CONTEXT, "Item", "a").properties().get("code"));
        assertEquals("B", storage.getObject(CONTEXT, "Item", "b").properties().get("code"));
    }

    @Test
    void historyWriteFailureCannotCommitAnObjectWithoutItsHistory() throws Exception {
        var data = data();
        var storage = provider(data);
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "a", Map.of("code", "original"));
            tx.commit();
        }
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE of_object_history ADD CONSTRAINT test_history_failure CHECK (version <= 1)");
        }
        try (var tx = storage.beginTransaction(CONTEXT)) {
            assertThrows(IllegalStateException.class, () -> tx.updateObject("Item", "a", Map.of("code", "changed"), 1));
            assertThrows(IllegalStateException.class, tx::commit);
        }
        assertEquals(1, storage.getObject(CONTEXT, "Item", "a").version());
        assertEquals("original", storage.getObject(CONTEXT, "Item", "a").properties().get("code"));
        assertEquals(1, storage.getEntityHistory(CONTEXT, new EntityKey("Item", "a")).size());
    }

    private JdbcDataSource data() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:constraints_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        return data;
    }

    private JdbcStorageProvider provider(JdbcDataSource data) {
        var storage = new JdbcStorageProvider(data, DatabaseDialect.h2());
        storage.applySchema(CONTEXT, SCHEMA);
        return storage;
    }
}
