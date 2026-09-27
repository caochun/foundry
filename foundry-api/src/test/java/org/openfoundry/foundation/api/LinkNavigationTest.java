package org.openfoundry.foundation.api;

import graphql.ExecutionInput;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.security.FieldPolicy;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyValidationException;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LinkNavigationTest {
    private static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name: "library", version: "1.0.0")
            interface Borrowable { borrower: Member @link(type: "BorrowedBy") }
            interface Identifiable { id: ID! @primary }
            type Book implements Identifiable & Borrowable @objectType {
              title: String!
              loans: [BorrowedBy!]! @link(type: "BorrowedBy", history: true)
              privateBorrower: Member @link(type: "BorrowedBy") @sensitive
            }
            type Member implements Identifiable @objectType {
              name: String!
              email: String @sensitive
              books: [Book!]! @link(type: "BorrowedBy", direction: INBOUND)
            }
            type BorrowedBy implements Identifiable @linkType(from: "Book", to: "Member", cardinality: MANY_TO_ONE) {
              dueAt: DateTime
              note: String @sensitive
            }
            """);
    private static final RequestContext CONTEXT = RequestContext.system("tenant", "reader");
    private static final SecurityPrincipal READER = new SecurityPrincipal("reader", "tenant", Set.of());
    private static final EntityKey BOOK = new EntityKey("Book", "b");
    private static final EntityKey MEMBER = new EntityKey("Member", "m");

    @TestFactory
    Stream<DynamicTest> navigationThroughBothProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "nested GraphQL navigation and REST", this::readBothDirections),
                test(provider, "source edge and target permissions", this::permissions),
                test(provider, "field policies apply to projected records", this::policies),
                test(provider, "terminated links remain distinct from active endpoints", this::terminated),
                test(provider, "pagination skips hidden edges across raw pages", this::pagination),
                test(provider, "projection fields cannot be stored or used across tenants", this::writeAndTenantIsolation)));
    }

    private DynamicTest test(String provider, String name, Consumer<Fixture> verify) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> {
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider();
            else {
                var data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:navigation_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2());
            }
            storage.applySchema(CONTEXT, SCHEMA);
            var fixture = new Fixture(storage);
            try (var tx = storage.beginTransaction(CONTEXT)) {
                tx.createObject("Book", "b", Map.of("title", "Reference"));
                tx.createObject("Member", "m", Map.of("name", "Reader", "email", "private@example.invalid"));
                tx.createLink("BorrowedBy", "loan", BOOK, MEMBER,
                        Map.of("dueAt", "2030-01-01T00:00:00Z", "note", "private-note"));
                tx.commit();
            }
            try {
                verify.accept(fixture);
            } finally {
                if (storage instanceof AutoCloseable resource) resource.close();
            }
        });
    }

    private void readBothDirections(Fixture f) {
        var graph = GraphqlApiRuntime.create(SCHEMA, f.app(Map.of()));
        var result = graph.execute(ExecutionInput.newExecutionInput("""
                { book(id: "b") {
                    ... on Borrowable { borrower { id name email books { id title } } }
                    privateBorrower { id }
                    loans { ... on Identifiable { id } dueAt note }
                  }
                }
                """).graphQLContext(Map.of("request", new ApiRequestContext(CONTEXT, READER))).build());
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        Map<String, Object> data = result.getData();
        var book = (Map<?, ?>) data.get("book");
        var borrower = (Map<?, ?>) book.get("borrower");
        assertEquals("Reader", borrower.get("name"));
        assertNull(borrower.get("email"));
        assertNull(book.get("privateBorrower"));
        assertEquals(List.of(Map.of("id", "b", "title", "Reference")), borrower.get("books"));
        var loan = (Map<?, ?>) ((List<?>) book.get("loans")).getFirst();
        assertEquals("loan", loan.get("id"));
        assertEquals("2030-01-01T00:00:00Z", loan.get("dueAt"));
        assertNull(loan.get("note"));
        var rest = new RestApiRouter(f.app(Map.of())).get(CONTEXT, READER, "/api/v1/Book/b/links/borrower", QueryOptions.defaults());
        assertEquals(200, rest.status());
        assertEquals("m", ((ObjectRecord) rest.body()).id());
        String sdl = new GraphqlContractGenerator().generate(SCHEMA);
        var registry = new graphql.schema.idl.SchemaParser().parse(sdl);
        assertTrue(registry.getType("BorrowedBy").isPresent());
        assertTrue(sdl.contains("loans(first: Int = 100, offset: Int = 0): [BorrowedBy!]"));
    }

    private void permissions(Fixture f) {
        var app = f.app(Map.of());
        for (EntityKey key : List.of(BOOK, MEMBER, new EntityKey("BorrowedBy", "loan"))) {
            f.denied.add(key);
            assertNull(app.readLinkField(CONTEXT, READER, BOOK, "borrower", QueryOptions.defaults()));
            Object loans = app.readLinkField(CONTEXT, READER, BOOK, "loans", QueryOptions.defaults());
            assertTrue(loans == null || ((List<?>) loans).isEmpty());
            f.denied.clear();
        }
        assertNotNull(app.readLinkField(CONTEXT, READER, BOOK, "borrower", QueryOptions.defaults()));
    }

    private void policies(Fixture f) {
        var policy = new FieldPolicy(Set.of("title"), Map.of("librarian", Set.of("borrower", "privateBorrower", "loans")));
        var app = f.app(Map.of("Book", policy,
                "Member", new FieldPolicy(Set.of("name"), Map.of("librarian", Set.of("email"))),
                "BorrowedBy", new FieldPolicy(Set.of("dueAt"), Map.of("librarian", Set.of("note")))));
        assertNull(app.readLinkField(CONTEXT, READER, BOOK, "borrower", QueryOptions.defaults()));
        var librarian = new SecurityPrincipal("reader", "tenant", Set.of("librarian"));
        var member = (ObjectRecord) app.readLinkField(CONTEXT, librarian, BOOK, "privateBorrower", QueryOptions.defaults());
        assertEquals("private@example.invalid", member.properties().get("email"));
        var loans = (List<?>) app.readLinkField(CONTEXT, librarian, BOOK, "loans", QueryOptions.defaults());
        assertEquals("private-note", ((LinkRecord) loans.getFirst()).properties().get("note"));
    }

    private void terminated(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            tx.deleteLink("BorrowedBy", "loan", 1);
            tx.createObject("Member", "next", Map.of("name", "Next"));
            tx.createLink("BorrowedBy", "next-loan", BOOK, new EntityKey("Member", "next"), Map.of());
            tx.deleteObject("Member", "m", 1);
            tx.commit();
        }
        var app = f.app(Map.of());
        var member = (ObjectRecord) app.readLinkField(CONTEXT, READER, BOOK, "borrower", QueryOptions.defaults());
        assertEquals("next", member.id());
        var loans = (List<?>) app.readLinkField(CONTEXT, READER, BOOK, "loans", QueryOptions.defaults());
        assertEquals(2, loans.size());
        assertTrue(((LinkRecord) loans.getFirst()).isDeleted());
        assertEquals("loan", ((LinkRecord) loans.getFirst()).id());
        f.denied.add(MEMBER);
        assertEquals(1, ((List<?>) app.readLinkField(CONTEXT, READER, BOOK, "loans", QueryOptions.defaults())).size());
    }

    private void pagination(Fixture f) {
        f.denied.add(new EntityKey("BorrowedBy", "loan"));
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            for (int i = 0; i < 108; i++) {
                String id = "b%03d".formatted(i);
                tx.createObject("Book", id, Map.of("title", id));
                tx.createLink("BorrowedBy", id, new EntityKey("Book", id), MEMBER, Map.of());
                if (i < 105) f.denied.add(new EntityKey("BorrowedBy", id));
            }
            tx.commit();
        }
        var rows = (List<?>) f.app(Map.of()).readLinkField(CONTEXT, READER, MEMBER, "books", new QueryOptions(1, 1, null, null, false));
        assertEquals(1, rows.size());
        assertEquals("b106", ((ObjectRecord) rows.getFirst()).id());
    }

    private void writeAndTenantIsolation(Fixture f) {
        try (var tx = f.storage.beginTransaction(CONTEXT)) {
            assertThrows(PropertyValidationException.class, () -> tx.updateObject("Book", "b", Map.of("borrower", "other"), 1));
        }
        var app = f.app(Map.of());
        assertThrows(SecurityException.class, () -> app.readLinkField(RequestContext.system("other", "reader"), READER, BOOK, "borrower", QueryOptions.defaults()));
        assertNull(app.readLinkField(RequestContext.system("other", "reader"),
                new SecurityPrincipal("reader", "other", Set.of()), BOOK, "borrower", QueryOptions.defaults()));
        assertThrows(IllegalArgumentException.class, () -> app.readLinkField(CONTEXT, READER, BOOK, "borrower",
                new QueryOptions(10, 0, Instant.now(), Instant.now(), false)));
        assertThrows(IllegalArgumentException.class, () -> app.readLinkField(CONTEXT, READER, BOOK, "missing", QueryOptions.defaults()));
    }

    private static final class Fixture {
        final StorageProvider storage;
        final Set<EntityKey> denied = new HashSet<>();

        Fixture(StorageProvider storage) {
            this.storage = storage;
        }

        ApplicationService app(Map<String, FieldPolicy> policies) {
            return new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> !denied.contains(key)),
                    new ActionExecutor(), SCHEMA, Map.of(), policies);
        }
    }
}
