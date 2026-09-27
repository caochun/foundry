package org.openfoundry.foundation.api;

import graphql.ExecutionInput;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.actions.StandardSideEffectHandler;
import org.openfoundry.foundation.events.CloudEvent;
import org.openfoundry.foundation.pack.DomainPackLoader;
import org.openfoundry.foundation.pack.PackSeeder;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PackBundleApplicationTest {
    @TestFactory
    Stream<DynamicTest> originalLibraryAssetsComposeSeedAndGovernTheApplication() {
        return Stream.of("memory", "jdbc").map(provider -> DynamicTest.dynamicTest(provider, () -> {
            Path root = Path.of(getClass().getResource("/upstream-v0.3.0").toURI());
            var bundle = new DomainPackLoader().loadBundle(List.of(root.resolve("library"), root.resolve("core")));
            var bootstrap = RequestContext.system("tenant", "bootstrap");
            var context = RequestContext.system("tenant", "librarian");
            var principal = new SecurityPrincipal("librarian", "tenant", Set.of("librarian"));
            JdbcConnectionPool pool = null;
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider();
            else {
                pool = JdbcConnectionPool.create("jdbc:h2:mem:bundle_application_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
                pool.setMaxConnections(1);
                pool.setLoginTimeout(2);
                storage = new JdbcStorageProvider(pool, DatabaseDialect.h2());
            }
            try {
                storage.applySchema(bootstrap, bundle.ontology().schema());
                var seeded = new PackSeeder().apply(bootstrap, bundle, storage);
                assertEquals(3, seeded.createdObjects());
                var book = seeded.references().get("example.library:book-dune");
                var member = seeded.references().get("example.library:member-ada");
                var events = new ArrayList<CloudEvent>();
                var app = ApplicationService.fromBundle(storage, new AuthorizationService((who, permission, key) -> true),
                        new ActionExecutor().withSideEffects(new StandardSideEffectHandler(events::add)), bundle, AuthorizationMode.ONTOLOGY_TARGETS);
                assertEquals("ada@example.org", app.getObject(context, principal, member.type(), member.id()).properties().get("email"));
                assertFalse(app.getObject(context, new SecurityPrincipal("librarian", "tenant", Set.of()), member.type(), member.id()).properties().containsKey("email"));
                var borrowed = app.execute(bundle.actions().get("BorrowBook"), context, principal, Map.of("book", book.id(), "member", member.id()), "borrow");
                assertTrue(borrowed.success());
                var graph = GraphqlApiRuntime.create(bundle.ontology().schema(), app);
                var view = graph.execute(ExecutionInput.newExecutionInput("query($id: ID!) { member(id: $id) { name email books { id title } } }")
                        .variables(Map.of("id", member.id())).graphQLContext(Map.of("request", new ApiRequestContext(context, principal))).build());
                assertTrue(view.getErrors().isEmpty(), view.getErrors().toString());
                Map<String, Object> data = view.getData();
                var record = (Map<?, ?>) data.get("member");
                assertEquals("ada@example.org", record.get("email"));
                assertEquals(1, ((List<?>) record.get("books")).size());
                assertEquals(0, new PackSeeder().apply(bootstrap, bundle, storage).createdObjects());
                assertEquals("ON_LOAN", storage.getObject(context, book.type(), book.id()).properties().get("status"));
                assertTrue(app.execute(bundle.actions().get("ReturnBook"), context, principal, Map.of("book", book.id()), "return").success());
                assertEquals(1, events.size());
                assertEquals(1, bundle.assets().permissions().size());
                assertTrue(bundle.assets().permissions().getFirst().dsl().contains("define can_borrow"));
            } finally {
                if (storage instanceof AutoCloseable resource) resource.close();
                if (pool != null) pool.dispose();
            }
        }));
    }
}
