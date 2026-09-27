package org.openfoundry.foundation.storage.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.LinkRecord;
import org.openfoundry.foundation.spi.ObjectRecord;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryStorageProviderTest {
    private static final RequestContext CONTEXT = RequestContext.system("tenant-a", "tester");
    private final InMemoryStorageProvider storage = new InMemoryStorageProvider();
    private final EntityKey person = new EntityKey("Person", "person-1");
    private final EntityKey organizationA = new EntityKey("Organization", "org-a");
    private final EntityKey organizationB = new EntityKey("Organization", "org-b");

    @BeforeEach
    void setUp() {
        PropertyDefinition id = new PropertyDefinition("id", "ID", true, true,
                true, true, false, true);
        storage.applySchema(CONTEXT, new OntologySchema(
                "example", "0.1.0",
                List.of(new ObjectTypeDefinition("Person", List.of(id, new PropertyDefinition("name", "String", false, false, false, false, false, false))),
                        new ObjectTypeDefinition("Organization", List.of(id, new PropertyDefinition("name", "String", false, false, false, false, false, false)))),
                List.of(new LinkTypeDefinition("BelongsTo", "Person", "Organization",
                        Cardinality.MANY_TO_ONE, List.of(id, new PropertyDefinition("role", "String", false, false, false, false, false, false)))),
                List.of()));
    }

    @Test
    void storesObjectAndLinkHistoryAcrossTransfer() {
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            transaction.createObject("Person", person.id(), Map.of("name", "Alice"));
            transaction.createObject("Organization", organizationA.id(), Map.of("name", "A"));
            transaction.createObject("Organization", organizationB.id(), Map.of("name", "B"));
            transaction.createLink("BelongsTo", "assignment-a", person, organizationA,
                    Map.of("role", "staff"));
            transaction.commit();
        }

        ObjectRecord before = storage.getObject(CONTEXT, person.type(), person.id());
        assertNotNull(before);
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            transaction.deleteLink("BelongsTo", "assignment-a", 1);
            transaction.createLink("BelongsTo", "assignment-b", person, organizationB,
                    Map.of("role", "manager"));
            transaction.commit();
        }

        assertEquals(2, storage.getEntityHistory(CONTEXT, new EntityKey("BelongsTo", "assignment-a")).size());
        assertEquals(1, storage.getLinks(CONTEXT, person, "BelongsTo",
                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).size());
        assertEquals(organizationB, storage.getLinks(CONTEXT, person, "BelongsTo",
                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).getFirst().to());
        assertEquals(1, before.version());
    }

    @Test
    void readsRelationshipAtHistoricalValidTime() {
        createBaseObjects();
        Instant beforeTransfer;
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            LinkRecord created = transaction.createLink("BelongsTo", "assignment-a", person,
                    organizationA, Map.of());
            beforeTransfer = created.validFrom().plusMillis(1);
            transaction.commit();
        }

        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            transaction.deleteLink("BelongsTo", "assignment-a", 1);
            transaction.commit();
        }

        assertEquals(organizationA.id(),
                storage.getLinkAtTime(CONTEXT, "BelongsTo", "assignment-a",
                        beforeTransfer, Instant.now()).state().get("_toId"));
    }

    @Test
    void enforcesManyToOneCardinalityForActiveRelations() {
        createBaseObjects();
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            transaction.createLink("BelongsTo", "assignment-a", person, organizationA, Map.of());
            transaction.commit();
        }

        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            assertThrows(IllegalStateException.class,
                    () -> transaction.createLink("BelongsTo", "assignment-b", person, organizationB, Map.of()));
            transaction.rollback();
        }
    }

    @Test
    void traversesRelationshipGraphAtHistoricalTime() throws InterruptedException {
        createBaseObjects();
        Instant beforeTransfer;
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            LinkRecord created = transaction.createLink("BelongsTo", "assignment-a", person, organizationA, Map.of());
            beforeTransfer = created.validFrom().plusMillis(1);
            transaction.commit();
        }
        Thread.sleep(5);
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            transaction.deleteLink("BelongsTo", "assignment-a", 1);
            transaction.createLink("BelongsTo", "assignment-b", person, organizationB, Map.of());
            transaction.commit();
        }

        var result = storage.traverseAsOf(CONTEXT, person,
                List.of(new TraversalStep("BelongsTo", StorageProvider.Direction.OUTBOUND)),
                beforeTransfer, Instant.now(), QueryOptions.defaults());
        assertEquals(List.of(organizationA), result.nodes());
    }

    @Test
    void rejectsConcurrentCommit() {
        createBaseObjects();
        Transaction first = storage.beginTransaction(CONTEXT);
        Transaction second = storage.beginTransaction(CONTEXT);
        first.createLink("BelongsTo", "assignment-a", person, organizationA, Map.of());
        first.commit();
        second.createLink("BelongsTo", "assignment-b", person, organizationB, Map.of());
        assertThrows(IllegalStateException.class, second::commit);
        second.rollback();
    }

    private void createBaseObjects() {
        try (Transaction transaction = storage.beginTransaction(CONTEXT)) {
            transaction.createObject("Person", person.id(), Map.of());
            transaction.createObject("Organization", organizationA.id(), Map.of());
            transaction.createObject("Organization", organizationB.id(), Map.of());
            transaction.commit();
        }
    }
}
