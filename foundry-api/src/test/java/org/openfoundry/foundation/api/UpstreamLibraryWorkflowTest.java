package org.openfoundry.foundation.api;

import graphql.ExecutionInput;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.actions.StandardSideEffectHandler;
import org.openfoundry.foundation.events.CloudEvent;
import org.openfoundry.foundation.events.EventSink;
import org.openfoundry.foundation.events.JdbcIdempotentEventSink;
import org.openfoundry.foundation.pack.DomainPackLoader;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class UpstreamLibraryWorkflowTest {
    @TestFactory
    Stream<DynamicTest> unmodifiedUpstreamPacksBorrowReturnAndCompensate() {
        return Stream.of("memory", "jdbc-single-connection").map(provider -> DynamicTest.dynamicTest(provider, () -> {
            Path fixtures = Path.of(getClass().getResource("/upstream-v0.3.0").toURI());
            var loaded = new DomainPackLoader().loadAll(List.of(fixtures.resolve("core"), fixtures.resolve("library"))).stream()
                    .filter(pack -> pack.manifest().name().equals("library")).findFirst().orElseThrow();
            var schema = loaded.ontology().schema();
            var clock = new SideEffectWorkflowTest.TestClock();
            var context = RequestContext.system("tenant", "librarian");
            var principal = new SecurityPrincipal("librarian", "tenant", Set.of("librarian"));
            JdbcConnectionPool pool = null;
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider(clock);
            else {
                pool = JdbcConnectionPool.create("jdbc:h2:mem:library_actions_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
                pool.setMaxConnections(1);
                pool.setLoginTimeout(2);
                storage = new JdbcStorageProvider(pool, DatabaseDialect.h2(), clock);
            }
            try {
                storage.applySchema(context, schema);
                Map<?, ?> catalogue = new Yaml().load(Files.readString(fixtures.resolve("library/seeds/catalogue.yaml")));
                try (var tx = storage.beginTransaction(context)) {
                    for (Object raw : (List<?>) catalogue.get("objects")) {
                        var row = (Map<?, ?>) raw;
                        @SuppressWarnings("unchecked") var values = (Map<String, Object>) row.get("fields");
                        tx.createObject((String) row.get("type"), (String) row.get("ref"), values);
                    }
                    tx.commit();
                }
                var events = new ArrayList<CloudEvent>();
                EventSink destination = event -> {
                    // The callback must run after commit and without retaining the only JDBC connection.
                    assertEquals("ON_LOAN", storage.getObject(context, "Book", "book-dune").properties().get("status"));
                    events.add(event);
                };
                if (pool != null) destination = new JdbcIdempotentEventSink(pool, DatabaseDialect.h2(), "library", destination, clock, Duration.ofSeconds(30));
                var executor = new ActionExecutor().withSideEffects(new StandardSideEffectHandler(destination), clock, Duration.ofSeconds(30));
                var app = new ApplicationService(storage, new AuthorizationService((p, permission, key) -> true), executor,
                        schema, loaded.actions(), Map.of());
                var borrow = loaded.actions().get("BorrowBook");
                var inputs = Map.<String, Object>of("book", "book-dune", "member", "member-ada");
                var result = app.execute(borrow, context, principal, inputs, "borrow");
                assertTrue(result.success());
                assertEquals("COMPLETED", result.status());
                assertEquals(1, events.size());
                assertEquals("example.library.book.borrowed", events.getFirst().type());
                assertEquals(Map.of("bookId", "book-dune", "memberId", "member-ada"), events.getFirst().data());
                assertEquals(storage.getObject(context, "Book", "book-dune").lastTransactionId(), events.getFirst().transactionId());
                var bookKey = new EntityKey("Book", "book-dune");
                var loan = storage.getLinks(context, bookKey, "BorrowedBy", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).getFirst();
                assertEquals(events.getFirst().time(), Instant.parse((String) loan.properties().get("borrowedAt")));
                assertNull(loan.properties().get("dueAt"));
                assertEquals(result, app.execute(borrow, context, principal, inputs, "borrow"));
                assertEquals(1, events.size());
                var graph = GraphqlApiRuntime.create(schema, app);
                var projection = graph.execute(ExecutionInput.newExecutionInput("{ book(id: \"book-dune\") { borrower { id books { id } } } }")
                        .graphQLContext(Map.of("request", new ApiRequestContext(context, principal))).build());
                assertTrue(projection.getErrors().isEmpty(), projection.getErrors().toString());
                Map<?, ?> data = projection.getData();
                assertEquals("member-ada", ((Map<?, ?>) ((Map<?, ?>) data.get("book")).get("borrower")).get("id"));
                clock.advance(1);
                var returned = app.execute(loaded.actions().get("ReturnBook"), context, principal, Map.of("book", "book-dune"), "return");
                assertTrue(returned.success());
                assertEquals("AVAILABLE", storage.getObject(context, "Book", "book-dune").properties().get("status"));
                assertTrue(storage.getLink(context, "BorrowedBy", loan.id()).isDeleted());
                assertEquals(1, events.size());

                // The unchanged BorrowBook declares ROLLBACK_ALL with the default three attempts.
                var rejected = new AtomicInteger();
                var failing = new ApplicationService(storage, new AuthorizationService((p, permission, key) -> true),
                        new ActionExecutor().withSideEffects(new StandardSideEffectHandler(event -> {
                            rejected.incrementAndGet();
                            throw new IllegalStateException("destination unavailable");
                        }), clock, Duration.ofSeconds(30)), schema, loaded.actions(), Map.of());
                var failedInputs = Map.<String, Object>of("book", "book-ubik", "member", "member-ada");
                var pending = failing.execute(borrow, context, principal, failedInputs, "failed-borrow");
                assertEquals("PENDING", pending.status());
                for (int i = 0; i < 2; i++) {
                    clock.advance(1);
                    pending = failing.execute(borrow, context, principal, failedInputs, "failed-borrow");
                }
                assertEquals("ROLLED_BACK", pending.status());
                assertEquals(3, rejected.get());
                assertEquals("AVAILABLE", storage.getObject(context, "Book", "book-ubik").properties().get("status"));
                assertTrue(storage.getLinks(context, new EntityKey("Book", "book-ubik"), "BorrowedBy", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).isEmpty());
                assertEquals(3, storage.getEntityHistory(context, new EntityKey("Book", "book-ubik")).size());
            } finally {
                if (storage instanceof AutoCloseable resource) resource.close();
                if (pool != null) pool.dispose();
            }
        }));
    }
}
