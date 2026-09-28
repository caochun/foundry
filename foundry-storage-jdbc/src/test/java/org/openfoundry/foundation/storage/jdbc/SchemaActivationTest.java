package org.openfoundry.foundation.storage.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.schema.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.*;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SchemaActivationTest {
    static final RequestContext CONTEXT = RequestContext.system("tenant", "deployer");
    static final String ODL = """
            extend schema @namespace(name: "activation", version: "1.0.0")
            type Node @objectType { id: ID! @primary name: String! code: String score: Int }
            type Other @objectType { id: ID! @primary }
            type Edge @linkType(from:"Node", to:"Node", cardinality:MANY_TO_MANY) { id: ID! @primary note: String }
            """;
    static final OntologySchema BASE = new OdlParser().parse(ODL);
    static final OntologySchema ADDED = new OdlParser().parse(ODL.replace("score: Int", "score: Int extra: String"));
    static final MigrationPlan APPROVED = new MigrationPlan("Reviewed schema transition after current-state checks", true);

    @Test
    void candidatesDoNotActivateAndOrdinaryStartupCannotReplaceTheActiveModel() {
        var f = fixture();
        assertEquals(1, f.storage.boundSchemaVersion());
        assertEquals(1, count(f.data, "of_schema_activations"));
        assertEquals(2, f.registry.applyIfChanged(ADDED, null).version());
        create(f.storage, "a", Map.of("name", "A"));
        var restarted = provider(f.data, BASE);
        assertEquals(1, restarted.boundSchemaVersion());
        assertThrows(SchemaDriftException.class, () -> restarted.applySchema(CONTEXT, ADDED));
        assertEquals(1, restarted.boundSchemaVersion());
        assertEquals(2, restarted.activateSchema(CONTEXT, ADDED, null, 1).version());
        assertEquals(2, f.registry.currentVersion(), "Activating a registered candidate must not duplicate its version");
        assertThrows(SchemaVersionMismatchException.class, () -> create(f.storage, "old", Map.of("name", "Old")));
        create(restarted, "b", Map.of("name", "B", "extra", "new"));
        assertEquals(2, count(f.data, "of_schema_activations"));
        assertEquals(2, provider(f.data, ADDED).boundSchemaVersion());
        assertEquals(2, count(f.data, "of_schema_activations"), "Reattachment is not another activation");
    }

    @Test
    void approvalDoesNotBackfillRequiredDefaultsAndExpandBackfillContractCanSucceed() {
        var f = fixture();
        create(f.storage, "a", Map.of("name", "A"));
        var required = parse("score: Int badge: String! @default(value: \"automatic\")");
        assertThrows(SchemaValidationException.class, () -> f.storage.activateSchema(CONTEXT, required, null, 1));
        var missing = assertThrows(PropertyValidationException.class, () -> f.storage.activateSchema(CONTEXT, required, APPROVED, 1));
        assertEquals("REQUIRED_PROPERTY", missing.code());
        assertEquals(1, f.registry.currentVersion());
        assertEquals(1, f.storage.getObject(CONTEXT, "Node", "a").version());
        assertFalse(f.storage.getObject(CONTEXT, "Node", "a").properties().containsKey("badge"));
        var expanded = parse("score: Int badge: String @default(value: \"automatic\")");
        f.storage.activateSchema(CONTEXT, expanded, null, 1);
        assertFalse(f.storage.getObject(CONTEXT, "Node", "a").properties().containsKey("badge"));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.updateObject("Node", "a", Map.of("badge", "verified"), 1);
            tx.commit();
        }
        var version = f.storage.activateSchema(CONTEXT, required, APPROVED, 2);
        assertEquals(3, version.version());
        assertEquals("verified", f.storage.getObject(CONTEXT, "Node", "a").properties().get("badge"));
        assertEquals(2, f.storage.getEntityHistory(CONTEXT, new EntityKey("Node", "a")).size());
    }

    @Test
    void uniquenessAndConstraintsAreValidatedAcrossEveryTenantWithoutGeneratingAuditFields() {
        var f = fixture();
        create(f.storage, "a", Map.of("name", "A", "code", "same", "score", -1));
        create(f.storage, "b", Map.of("name", "B", "code", "same", "score", 2));
        var unique = new OdlParser().parse(ODL.replace("code: String", "code: String @unique"));
        assertEquals("SCHEMA_UNIQUE_CONFLICT", assertThrows(PropertyValidationException.class,
                () -> f.storage.activateSchema(CONTEXT, unique, APPROVED, 1)).code());
        try (var tx = f.storage.beginTransaction(CONTEXT)) { tx.updateObject("Node", "b", Map.of("code", "different"), 1); tx.commit(); }
        var other = RequestContext.system("other", "deployer");
        f.storage.applySchema(other, BASE);
        try (var tx = f.storage.beginTransaction(other)) {
            tx.createObject("Node", "a", Map.of("name", "Other", "code", "same", "score", -2));
            tx.commit();
        }
        f.storage.activateSchema(CONTEXT, unique, APPROVED, 1);
        var constrained = new OdlParser().parse(ODL.replace("code: String", "code: String @unique")
                .replace("type Node @objectType", "type Node @objectType @constraint(expr: \"this.score >= 0\")"));
        assertThrows(PropertyValidationException.class, () -> f.storage.activateSchema(CONTEXT, constrained, APPROVED, 2));
        try (var tx = f.storage.beginTransaction(CONTEXT)) { tx.updateObject("Node", "a", Map.of("score", 1), 1); tx.commit(); }
        assertThrows(PropertyValidationException.class, () -> f.storage.activateSchema(CONTEXT, constrained, APPROVED, 2), "Other tenant must also be checked");
        var audit = new OdlParser().parse(ODL.replace("code: String", "code: String @unique").replace("score: Int", "score: Int createdAt: DateTime! @readonly"));
        assertThrows(PropertyValidationException.class, () -> f.storage.activateSchema(CONTEXT, audit, APPROVED, 2));
        assertEquals(2, f.registry.currentVersion());
        assertFalse(f.storage.getObject(CONTEXT, "Node", "a").properties().containsKey("createdAt"));
    }

    @Test
    void activeRelationshipCardinalityAndEndpointsAreCheckedAndOldEndedEdgesCannotRestoreUnderNewEndpoints() {
        var f = fixture();
        for (String id : List.of("a", "b", "c")) create(f.storage, id, Map.of("name", id));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.createLink("Edge", "one", new EntityKey("Node", "a"), new EntityKey("Node", "b"), Map.of());
            tx.createLink("Edge", "two", new EntityKey("Node", "a"), new EntityKey("Node", "c"), Map.of());
            tx.commit();
        }
        var one = new OdlParser().parse(ODL.replace("MANY_TO_MANY", "MANY_TO_ONE"));
        assertThrows(IllegalArgumentException.class, () -> f.storage.activateSchema(CONTEXT, one, APPROVED, 1));
        try (var tx = f.storage.beginTransaction(CONTEXT)) { tx.deleteLink("Edge", "two", 1); tx.commit(); }
        f.storage.activateSchema(CONTEXT, one, APPROVED, 1);
        var changed = new OdlParser().parse(ODL.replace("from:\"Node\"", "from:\"Other\"").replace("MANY_TO_MANY", "MANY_TO_ONE"));
        assertThrows(IllegalArgumentException.class, () -> f.storage.activateSchema(CONTEXT, changed, APPROVED, 2));
        try (var tx = f.storage.beginTransaction(CONTEXT)) { tx.deleteLink("Edge", "one", 1); tx.commit(); }
        f.storage.activateSchema(CONTEXT, changed, APPROVED, 2);
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(IllegalArgumentException.class, () -> tx.restoreLink("Edge", "one", 2));
            tx.commit();
        }
        assertEquals(2, f.storage.getLink(CONTEXT, "Edge", "one").version());
        assertTrue(f.storage.getLink(CONTEXT, "Edge", "one").isDeleted());
    }

    @Test
    void activationFencesAlreadyMutatedAndUnstartedOldTransactions() throws Exception {
        var f = fixture();
        var next = provider(f.data, BASE);
        try (var executor = Executors.newSingleThreadExecutor(); var old = f.storage.beginTransaction(CONTEXT)) {
            old.createObject("Node", "old-committed", Map.of("name", "Old"));
            var entered = new CountDownLatch(1);
            var activation = executor.submit(() -> {
                entered.countDown();
                return next.activateSchema(CONTEXT, ADDED, null, 1);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, activation.get(10, TimeUnit.SECONDS).version());
            assertThrows(SchemaVersionMismatchException.class, old::commit);
        }
        assertNull(next.getObject(CONTEXT, "Node", "old-committed"));
        try (var dormant = next.beginTransaction(CONTEXT)) {
            var third = parse("score: Int extra: String extraTwo: String");
            next.activateSchema(CONTEXT, third, null, 2);
            assertThrows(SchemaVersionMismatchException.class, () -> dormant.createObject("Node", "late", Map.of("name", "Late")));
            assertThrows(IllegalStateException.class, dormant::commit);
        }
        assertNull(next.getObject(CONTEXT, "Node", "late"));
    }

    @Test
    void allTransactionalWritesAndEvenEmptyCommitsRejectAnOldActivation() {
        var f = fixture();
        provider(f.data, BASE).activateSchema(CONTEXT, ADDED, null, 1);
        List<Consumer<Transaction>> operations = List.of(
                tx -> tx.createObject("Node", "stale", Map.of("name", "stale")),
                tx -> tx.appendAudit(new AuditEntry("audit", Instant.now(), "tenant", "deployer", "TEST", null, null, null, tx.transactionId(), "OK", Map.of())),
                tx -> tx.enqueueOutbox(new OutboxEntry("event", "tenant", "test", "subject", Instant.now(), tx.transactionId(), Map.of())),
                tx -> tx.putCommandReceipt(new CommandReceipt("a".repeat(64), "deployer", "Test", "b".repeat(64), Map.of())),
                tx -> tx.putActionExecution(new ActionExecution("execution", "deployer", "Test", 1, "PENDING", null, Map.of()), 0),
                Transaction::commit);
        for (var operation : operations) {
            try (var tx = f.storage.beginTransaction(CONTEXT)) {
                assertThrows(SchemaVersionMismatchException.class, () -> operation.accept(tx));
            }
        }
        for (String table : List.of("of_objects", "of_audit_records", "of_outbox_events", "of_command_receipts", "of_action_executions")) assertEquals(0, count(f.data, table), table);
    }

    @Test
    void competingActivationPlansHaveOneWinnerAndReturningToAnOldModelDoesNotReviveOldWriters() throws Exception {
        var f = fixture();
        var first = provider(f.data, BASE);
        var second = provider(f.data, BASE);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var attempts = new ArrayList<Future<Boolean>>();
            for (var provider : List.of(first, second)) attempts.add(executor.submit(() -> {
                start.await();
                try { provider.activateSchema(CONTEXT, ADDED, null, 1); return true; }
                catch (SchemaVersionConflictException conflict) { return false; }
            }));
            start.countDown();
            int accepted = 0;
            for (var result : attempts) if (result.get(10, TimeUnit.SECONDS)) accepted++;
            assertEquals(1, accepted);
        }
        var active = provider(f.data, ADDED);
        assertEquals(3, active.activateSchema(CONTEXT, BASE, APPROVED, 2).version());
        assertThrows(SchemaVersionMismatchException.class, () -> create(f.storage, "aba", Map.of("name", "ABA")));
        create(provider(f.data, BASE), "new", Map.of("name", "New"));
    }

    @Test
    void activationPointerFailureRollsBackRegistryAndActivationEvidence() throws Exception {
        var f = fixture();
        try (var connection = f.data.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_activation BEFORE UPDATE ON of_active_schema FOR EACH ROW CALL '" + FailActivation.class.getName() + "'");
        }
        assertThrows(IllegalStateException.class, () -> f.storage.activateSchema(CONTEXT, ADDED, null, 1));
        assertEquals(1, f.registry.currentVersion());
        assertEquals(1, count(f.data, "of_schema_activations"));
        assertEquals(1, f.storage.boundSchemaVersion());
        create(f.storage, "still-old", Map.of("name", "Old"));
        try (var connection = f.data.getConnection(); var statement = connection.createStatement()) { statement.execute("DROP TRIGGER reject_activation"); }
        assertEquals(2, f.storage.activateSchema(CONTEXT, ADDED, null, 1).version());
    }

    @Test
    void mismatchedActivationMetadataCannotBeAttachedOrUsedForWrites() throws Exception {
        var f = fixture();
        try (var connection = f.data.getConnection(); var statement = connection.createStatement()) {
            statement.execute("UPDATE of_active_schema SET fingerprint = 'corrupt'");
        }
        assertThrows(IllegalStateException.class, () -> provider(f.data, BASE));
        assertThrows(SchemaVersionMismatchException.class, () -> create(f.storage, "corrupt", Map.of("name", "corrupt")));
        assertEquals(0, count(f.data, "of_objects"));
    }

    @Test
    void firstAdoptionValidatesLegacyFactsWithoutInventingHistoricalData() throws Exception {
        var data = SchemaRegistryPersistenceTest.data("jdbc:h2:mem:legacy_activation_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            for (String ddl : DatabaseDialect.h2().currentTablesDdl().split(";\\s*")) if (!ddl.isBlank()) statement.execute(ddl);
            statement.execute("INSERT INTO of_objects (tenant_id, object_type, object_id, version, created_at, updated_at, properties_json) "
                    + "VALUES ('tenant', 'Node', 'legacy', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, '{}')");
        }
        assertThrows(PropertyValidationException.class, () -> provider(data, BASE));
        var registry = new JdbcSchemaRegistry(data, DatabaseDialect.h2(), JdbcSchemaActivation.REGISTRY_KEY, Clock.systemUTC());
        assertEquals(0, registry.currentVersion());
        assertEquals(0, count(data, "of_schema_activations"));
        try (var connection = data.getConnection(); var statement = connection.prepareStatement("UPDATE of_objects SET properties_json = ?")) {
            statement.setString(1, "{\"name\":\"Legacy\"}");
            statement.executeUpdate();
        }
        var adopted = provider(data, BASE);
        assertEquals(1, adopted.boundSchemaVersion());
        assertEquals(1, adopted.getObject(CONTEXT, "Node", "legacy").version());
        assertEquals(0, count(data, "of_object_history"));
    }

    @Test
    void differentTenantMutationsCanOverlapBeforeTheShortCommitFence() {
        var f = fixture();
        var other = RequestContext.system("other", "deployer");
        try (var first = f.storage.beginTransaction(CONTEXT); var second = f.storage.beginTransaction(other)) {
            first.createObject("Node", "a", Map.of("name", "A"));
            second.createObject("Node", "b", Map.of("name", "B"));
            second.commit();
            first.commit();
        }
        assertNotNull(f.storage.getObject(CONTEXT, "Node", "a"));
        assertNotNull(f.storage.getObject(other, "Node", "b"));
    }

    private static OntologySchema parse(String scoreReplacement) { return new OdlParser().parse(ODL.replace("score: Int", scoreReplacement)); }
    static JdbcStorageProvider provider(JdbcDataSource data, OntologySchema schema) {
        var storage = new JdbcStorageProvider(data, DatabaseDialect.h2());
        storage.applySchema(CONTEXT, schema);
        return storage;
    }
    static void create(JdbcStorageProvider storage, String id, Map<String, Object> values) {
        try (var tx = storage.beginTransaction(CONTEXT)) { tx.createObject("Node", id, values); tx.commit(); }
    }
    static long count(JdbcDataSource data, String table) {
        try (var connection = data.getConnection(); var statement = connection.createStatement(); var row = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            row.next(); return row.getLong(1);
        } catch (SQLException failure) { throw new IllegalStateException(failure); }
    }
    private static Fixture fixture() {
        var data = SchemaRegistryPersistenceTest.data("jdbc:h2:mem:activation_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        return new Fixture(data, provider(data, BASE), new JdbcSchemaRegistry(data, DatabaseDialect.h2(), JdbcSchemaActivation.REGISTRY_KEY, Clock.systemUTC()));
    }
    private record Fixture(JdbcDataSource data, JdbcStorageProvider storage, JdbcSchemaRegistry registry) {}
    public static final class FailActivation implements org.h2.api.Trigger {
        @Override public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow) throws SQLException { throw new SQLException("injected activation pointer failure"); }
    }
}
