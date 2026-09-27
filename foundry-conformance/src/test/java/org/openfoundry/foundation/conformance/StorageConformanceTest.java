package org.openfoundry.foundation.conformance;

import org.h2.jdbcx.JdbcDataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.LinkRecord;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.Transaction;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;
import org.openfoundry.foundation.spi.schema.Cardinality;
import org.openfoundry.foundation.spi.schema.LinkTypeDefinition;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Same behavioural contract runs against every provider. */
class StorageConformanceTest {
    private static final RequestContext TENANT_A = RequestContext.system("tenant-a", "test");
    private static final RequestContext TENANT_B = RequestContext.system("tenant-b", "test");
    private static final EntityKey PERSON = new EntityKey("Person", "p-1");
    private static final EntityKey ORG = new EntityKey("Organization", "org-1");
    private static final PropertyDefinition ID = new PropertyDefinition("id", "ID", true, true, true, true, false, true);
    private static final OntologySchema SCHEMA = new OntologySchema("conformance", "0.1.0",
            List.of(new ObjectTypeDefinition("Person", List.of(ID, new PropertyDefinition("name", "String", false, false, false, false, false, false))), new ObjectTypeDefinition("Organization", List.of(ID, new PropertyDefinition("name", "String", false, false, false, false, false, false))),
                    new ObjectTypeDefinition("Assignment", List.of(ID, new PropertyDefinition("title", "String", false, false, false, false, false, false)))),
            List.of(
                    new LinkTypeDefinition("BelongsTo", "Person", "Organization", Cardinality.MANY_TO_ONE, List.of(ID)),
                    new LinkTypeDefinition("HoldsAssignment", "Person", "Assignment", Cardinality.MANY_TO_ONE, List.of(ID)),
                    new LinkTypeDefinition("AssignmentInOrganization", "Assignment", "Organization", Cardinality.MANY_TO_ONE, List.of(ID))), List.of());

    @TestFactory
    Stream<DynamicTest> providersObeyCoreContract() {
        Stream<ProviderCase> builtIns = Stream.of(
                new ProviderCase("memory", InMemoryStorageProvider::new),
                new ProviderCase("jdbc", StorageConformanceTest::jdbcProvider));
        String pgUrl = System.getenv("PG_TEST_URL");
        Stream<ProviderCase> external = pgUrl == null || pgUrl.isBlank() ? Stream.empty()
                : Stream.of(new ProviderCase("postgresql", () -> postgresProvider(pgUrl)));
        return Stream.concat(builtIns, external)
                .flatMap(testCase -> Stream.of(
                        DynamicTest.dynamicTest(testCase.name + " object/link history", () -> history(testCase.provider.get())),
                        DynamicTest.dynamicTest(testCase.name + " rollback", () -> rollback(testCase.provider.get())),
                        DynamicTest.dynamicTest(testCase.name + " tenant isolation", () -> tenancy(testCase.provider.get())),
                        DynamicTest.dynamicTest(testCase.name + " person assignment transfer", () -> assignmentTransfer(testCase.provider.get()))));
    }

    private static void history(StorageProvider storage) {
        storage.applySchema(TENANT_A, SCHEMA);
        try (Transaction tx = storage.beginTransaction(TENANT_A)) {
            tx.createObject("Person", PERSON.id(), Map.of("name", "Alice"));
            tx.createObject("Organization", ORG.id(), Map.of("name", "Org"));
            tx.createLink("BelongsTo", "assignment-1", PERSON, ORG, Map.of());
            tx.commit();
        }
        LinkRecord current = storage.getLink(TENANT_A, "BelongsTo", "assignment-1");
        assertEquals(1, current.version());
        assertEquals(1, storage.getEntityHistory(TENANT_A, new EntityKey("BelongsTo", "assignment-1")).size());
    }

    private static void rollback(StorageProvider storage) {
        storage.applySchema(TENANT_A, SCHEMA);
        try (Transaction tx = storage.beginTransaction(TENANT_A)) {
            tx.createObject("Person", PERSON.id(), Map.of());
            tx.rollback();
        }
        assertNull(storage.getObject(TENANT_A, PERSON.type(), PERSON.id()));
    }

    private static void tenancy(StorageProvider storage) {
        storage.applySchema(TENANT_A, SCHEMA);
        try (Transaction tx = storage.beginTransaction(TENANT_A)) {
            tx.createObject("Person", PERSON.id(), Map.of());
            tx.commit();
        }
        assertNull(storage.getObject(TENANT_B, PERSON.type(), PERSON.id()));
    }

    private static void assignmentTransfer(StorageProvider storage) {
        storage.applySchema(TENANT_A, SCHEMA);
        EntityKey assignment = new EntityKey("Assignment", "assignment-1");
        EntityKey orgB = new EntityKey("Organization", "org-2");
        try (Transaction tx = storage.beginTransaction(TENANT_A)) {
            tx.createObject("Person", PERSON.id(), Map.of("name", "Alice"));
            tx.createObject("Organization", ORG.id(), Map.of("name", "A"));
            tx.createObject("Organization", orgB.id(), Map.of("name", "B"));
            tx.createObject("Assignment", assignment.id(), Map.of("title", "Engineer"));
            tx.createLink("HoldsAssignment", "person-assignment", PERSON, assignment, Map.of());
            tx.createLink("AssignmentInOrganization", "assignment-org-a", assignment, ORG, Map.of());
            tx.commit();
        }
        Instant before = storage.getLink(TENANT_A, "AssignmentInOrganization", "assignment-org-a").validFrom().plusMillis(1);
        try { Thread.sleep(5); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        try (Transaction tx = storage.beginTransaction(TENANT_A)) {
            tx.deleteLink("AssignmentInOrganization", "assignment-org-a", 1);
            tx.createLink("AssignmentInOrganization", "assignment-org-b", assignment, orgB, Map.of());
            tx.commit();
        }
        assertEquals(orgB, storage.getLinks(TENANT_A, assignment, "AssignmentInOrganization",
                StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).getFirst().to());
        var historical = storage.getLinkAtTime(TENANT_A, "AssignmentInOrganization", "assignment-org-a", before, Instant.now());
        assertEquals(ORG.id(), historical.state().get("_toId"));
    }

    private static StorageProvider jdbcProvider() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:conformance_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        return new JdbcStorageProvider(dataSource, DatabaseDialect.h2());
    }

    private static StorageProvider postgresProvider(String url) {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(url);
        return new JdbcStorageProvider(dataSource, DatabaseDialect.postgresql());
    }

    private record ProviderCase(String name, Supplier<StorageProvider> provider) {}
}
