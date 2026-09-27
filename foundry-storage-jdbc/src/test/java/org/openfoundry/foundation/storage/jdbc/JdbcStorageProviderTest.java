package org.openfoundry.foundation.storage.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.HistorySnapshot;
import org.openfoundry.foundation.spi.LinkRecord;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.Transaction;
import org.openfoundry.foundation.spi.TraversalStep;
import org.openfoundry.foundation.spi.schema.Cardinality;
import org.openfoundry.foundation.spi.schema.LinkTypeDefinition;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;

import java.time.Instant;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcStorageProviderTest {
    private static final RequestContext CONTEXT = RequestContext.system("tenant-a", "tester");
    private JdbcStorageProvider storage;
    private JdbcDataSource dataSource;
    private final EntityKey person = new EntityKey("Person", "p-1");
    private final EntityKey orgA = new EntityKey("Organization", "org-a");
    private final EntityKey orgB = new EntityKey("Organization", "org-b");

    @BeforeEach
    void setUp() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:foundry_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        storage = new JdbcStorageProvider(dataSource, DatabaseDialect.h2());
        PropertyDefinition id = new PropertyDefinition("id", "ID", true, true, true, true, false, true);
        storage.applySchema(CONTEXT, new OntologySchema("example", "0.1.0",
                List.of(new ObjectTypeDefinition("Person", List.of(id, new PropertyDefinition("name", "String", false, false, false, false, false, false))), new ObjectTypeDefinition("Organization", List.of(id, new PropertyDefinition("name", "String", false, false, false, false, false, false)))),
                List.of(new LinkTypeDefinition("BelongsTo", "Person", "Organization", Cardinality.MANY_TO_ONE, List.of(id, new PropertyDefinition("role", "String", false, false, false, false, false, false)))),
                List.of()));
    }

    @Test
    void persistsCurrentAndHistoricalRelationshipState() {
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            transaction.createObject("Person", person.id(), Map.of("name", "Alice"));
            transaction.createObject("Organization", orgA.id(), Map.of("name", "A"));
            transaction.createObject("Organization", orgB.id(), Map.of("name", "B"));
            transaction.createLink("BelongsTo", "assignment-a", person, orgA, Map.of("role", "staff"));
            transaction.commit();
        }
        Instant before = storage.getLink(CONTEXT, "BelongsTo", "assignment-a").validFrom().plusMillis(1);
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            transaction.deleteLink("BelongsTo", "assignment-a", 1);
            transaction.createLink("BelongsTo", "assignment-b", person, orgB, Map.of("role", "manager"));
            transaction.commit();
        }

        assertEquals(orgB, storage.getLinks(CONTEXT, person, "BelongsTo", StorageProvider.Direction.OUTBOUND,
                QueryOptions.defaults()).getFirst().to());
        HistorySnapshot old = storage.getLinkAtTime(CONTEXT, "BelongsTo", "assignment-a", before, Instant.now());
        assertNotNull(old);
        assertEquals(orgA.id(), old.state().get("_toId"));
        assertEquals(2, storage.getEntityHistory(CONTEXT, new EntityKey("BelongsTo", "assignment-a")).size());
    }

    @Test
    void rollsBackObjectAndHistoryTogether() {
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            transaction.createObject("Person", person.id(), Map.of("name", "Alice"));
            transaction.rollback();
        }
        assertEquals(null, storage.getObject(CONTEXT, person.type(), person.id()));
        assertEquals(0, storage.getEntityHistory(CONTEXT, person).size());
    }

    @Test
    void enforcesExpectedVersion() {
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            transaction.createObject("Person", person.id(), Map.of("name", "Alice"));
            transaction.commit();
        }
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            assertThrows(IllegalStateException.class,
                    () -> transaction.updateObject("Person", person.id(), Map.of("name", "Bob"), 2));
            transaction.rollback();
        }
    }

    @Test
    void traversesRelationshipGraphAtHistoricalTime() throws InterruptedException {
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            transaction.createObject("Person", person.id(), Map.of());
            transaction.createObject("Organization", orgA.id(), Map.of());
            transaction.createObject("Organization", orgB.id(), Map.of());
            transaction.createLink("BelongsTo", "assignment-a", person, orgA, Map.of());
            transaction.commit();
        }
        Instant beforeTransfer = storage.getLink(CONTEXT, "BelongsTo", "assignment-a").validFrom().plusMillis(1);
        Thread.sleep(5);
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            transaction.deleteLink("BelongsTo", "assignment-a", 1);
            transaction.createLink("BelongsTo", "assignment-b", person, orgB, Map.of());
            transaction.commit();
        }

        var result = storage.traverseAsOf(CONTEXT, person,
                List.of(new TraversalStep("BelongsTo", StorageProvider.Direction.OUTBOUND)),
                beforeTransfer, Instant.now(), QueryOptions.defaults());
        assertEquals(List.of(orgA), result.nodes());
    }

    @Test
    void auditAndOutboxShareTheObjectTransaction() throws Exception {
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            transaction.createObject("Person", person.id(), Map.of());
            transaction.appendAudit(new org.openfoundry.foundation.spi.AuditEntry(
                    "audit-tx", Instant.now(), "tenant-a", "tester", "create",
                    "Person", person.id(), null, transaction.transactionId(), "success", Map.of()));
            transaction.enqueueOutbox(new org.openfoundry.foundation.spi.OutboxEntry(
                    "event-tx", "tenant-a", "openfoundry.object.created", "Person/p-1",
                    Instant.now(), transaction.transactionId(), Map.of("id", person.id())));
            transaction.commit();
        }
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM of_audit_records")) {
                result.next();
                assertEquals(1, result.getInt(1));
            }
            try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM of_outbox_events")) {
                result.next();
                assertEquals(1, result.getInt(1));
            }
        }
    }
}
