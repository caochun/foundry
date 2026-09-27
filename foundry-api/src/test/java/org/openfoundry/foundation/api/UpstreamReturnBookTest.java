package org.openfoundry.foundation.api;

import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.actions.ActionManifest;
import org.openfoundry.foundation.actions.ActionManifestParser;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.schema.SchemaCompiler;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class UpstreamReturnBookTest {
    @TestFactory
    Stream<DynamicTest> unmodifiedReturnBookRunsWithMemoryAndOneJdbcConnection() {
        return Stream.of("memory", "jdbc-one-connection").map(provider -> DynamicTest.dynamicTest(provider, () -> {
            var source = new StringBuilder();
            for (String name : List.of("enums", "book", "member", "links", "actions")) {
                source.append(resource("schema/" + name + ".odl")).append('\n');
            }
            var schema = new SchemaCompiler().compile(new OdlParser().parse(source.toString())).schema();
            var manifest = new ActionManifestParser().parse(resource("return-book.yaml"));
            assertEquals(ActionManifest.RollbackPolicy.ROLLBACK_ALL, manifest.onSideEffectFailure());
            JdbcConnectionPool pool = null;
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider();
            else {
                pool = JdbcConnectionPool.create("jdbc:h2:mem:upstream_return_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
                pool.setMaxConnections(1);
                pool.setLoginTimeout(2);
                storage = new JdbcStorageProvider(pool, DatabaseDialect.h2());
            }
            try {
                var context = RequestContext.system("tenant", "librarian");
                storage.applySchema(context, schema);
                var bookKey = new EntityKey("Book", "book");
                try (var tx = storage.beginTransaction(context)) {
                    tx.createObject("Book", "book", Map.of("title", "Reference", "author", "Author", "status", "ON_LOAN"));
                    tx.createObject("Member", "member", Map.of("name", "Member"));
                    tx.createLink("BorrowedBy", "loan", bookKey, new EntityKey("Member", "member"),
                            Map.of("borrowedAt", "2026-01-01T00:00:00Z"));
                    tx.commit();
                }
                var app = new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> true),
                        new ActionExecutor(), schema, Map.of("ReturnBook", manifest), Map.of());
                assertFalse(app.execute(manifest, context, new SecurityPrincipal("librarian", "tenant", Set.of()),
                        Map.of("book", "book"), "return").success());
                assertEquals("ON_LOAN", storage.getObject(context, "Book", "book").properties().get("status"));
                var principal = new SecurityPrincipal("librarian", "tenant", Set.of("librarian"));
                var result = app.execute(manifest, context, principal, Map.of("book", "book"), "return");
                assertTrue(result.success());
                assertEquals(List.of(bookKey, new EntityKey("BorrowedBy", "loan")), result.affected());
                assertEquals("AVAILABLE", storage.getObject(context, "Book", "book").properties().get("status"));
                assertTrue(storage.getLink(context, "BorrowedBy", "loan").isDeleted());
                assertEquals(2, storage.getEntityHistory(context, bookKey).size());
                assertEquals(2, storage.getEntityHistory(context, new EntityKey("BorrowedBy", "loan")).size());
                assertEquals(result, app.execute(manifest, context, principal, Map.of("book", "book"), "return"));
            } finally {
                if (storage instanceof AutoCloseable resource) resource.close();
                if (pool != null) pool.dispose();
            }
        }));
    }

    private String resource(String name) throws IOException {
        try (var stream = getClass().getResourceAsStream("/upstream-v0.3.0/library/" + name)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
