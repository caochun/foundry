package org.openfoundry.foundation.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.h2.jdbcx.JdbcConnectionPool;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.events.CloudEvent;
import org.openfoundry.foundation.pack.DomainPackLoader;
import org.openfoundry.foundation.pack.LoadedPackBundle;
import org.openfoundry.foundation.pack.PackSeeder;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Enabled explicitly with -Popenfga-integration. Requires a real, isolated loopback OpenFGA server. */
class OpenFgaLibraryIT {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> upstreamPermissionsAgainstRealOpenFga() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                check(provider, "original model authorizes borrow return and relationship reads", this::workflow),
                check(provider, "revoking the action target blocks replay despite unchanged token roles", this::targetRevocation),
                check(provider, "participant revocation blocks filtered deletion and its replay", this::participantRevocation),
                check(provider, "tenant-qualified IDs isolate identical local IDs", this::tenantIsolation)));
    }

    private DynamicTest check(String provider, String label, Consumer<Fixture> verify) {
        return DynamicTest.dynamicTest(provider + " " + label, () -> {
            try (var fixture = new Fixture(provider)) { verify.accept(fixture); }
        });
    }

    private void workflow(Fixture f) {
        var borrowed = f.borrow(f.context, f.principal);
        assertEquals("COMPLETED", borrowed.status());
        assertEquals(1, f.events.size());
        assertEquals(Map.of("bookId", f.book.id(), "memberId", f.member.id()), f.events.getFirst().data());
        assertEquals("ada@example.org", f.app.getObject(f.context, f.principal, "Member", f.member.id()).properties().get("email"));
        var viewer = new SecurityPrincipal(f.principal.id(), f.principal.tenantId(), Set.of());
        assertFalse(f.app.getObject(f.context, viewer, "Member", f.member.id()).properties().containsKey("email"));
        assertEquals(1, ((List<?>) f.app.readLinkField(f.context, f.principal, f.member, "books", QueryOptions.defaults())).size());
        assertEquals(0, f.seed(f.context).createdObjects());
        assertEquals(borrowed, f.borrow(f.context, f.principal));
        assertEquals(1, f.events.size());
        assertEquals(f.member.id(), ((ObjectRecord) f.app.readLinkField(f.context, f.principal, f.book, "borrower", QueryOptions.defaults())).id());
        assertFalse(f.app.history(f.context, f.principal, borrowed.affected().getLast()).isEmpty());
        var returned = f.returnBook();
        assertTrue(returned.success());
        assertEquals("AVAILABLE", f.storage.getObject(f.context, "Book", f.book.id()).properties().get("status"));
        assertTrue(f.storage.getLinks(f.context, f.book, "BorrowedBy", StorageProvider.Direction.OUTBOUND, QueryOptions.defaults()).isEmpty());
        assertEquals(returned, f.returnBook());
    }

    private void targetRevocation(Fixture f) {
        f.borrow(f.context, f.principal);
        f.tuple(f.principal, f.book, "librarian", false);
        assertThrows(SecurityException.class, () -> f.borrow(f.context, f.principal));
        assertEquals(1, f.events.size());
        assertEquals(2, f.storage.getObject(f.context, "Book", f.book.id()).version());
        f.tuple(f.principal, f.book, "librarian", true);
        assertTrue(f.borrow(f.context, f.principal).success());
    }

    private void participantRevocation(Fixture f) {
        var result = f.borrow(f.context, f.principal);
        f.tuple(f.principal, f.member, "librarian", false);
        assertThrows(SecurityException.class, f::returnBook);
        assertEquals("ON_LOAN", f.storage.getObject(f.context, "Book", f.book.id()).properties().get("status"));
        assertFalse(f.storage.getLink(f.context, "BorrowedBy", result.affected().getLast().id()).isDeleted());
        f.tuple(f.principal, f.member, "librarian", true);
        assertTrue(f.returnBook().success());
        f.tuple(f.principal, f.member, "librarian", false);
        assertThrows(SecurityException.class, f::returnBook);
        assertEquals(3, f.storage.getObject(f.context, "Book", f.book.id()).version());
    }

    private void tenantIsolation(Fixture f) {
        f.borrow(f.context, f.principal);
        var otherContext = RequestContext.system("other-tenant", "librarian");
        var otherPrincipal = new SecurityPrincipal("librarian", "other-tenant", Set.of("librarian"));
        f.seed(otherContext);
        assertThrows(SecurityException.class, () -> f.borrow(otherContext, otherPrincipal));
        assertEquals("AVAILABLE", f.storage.getObject(otherContext, "Book", f.book.id()).properties().get("status"));
        assertThrows(SecurityException.class, () -> f.borrow(f.context, otherPrincipal));
        f.tuple(otherPrincipal, f.book, "librarian", true);
        f.tuple(otherPrincipal, f.member, "librarian", true);
        assertTrue(f.borrow(otherContext, otherPrincipal).success());
        assertEquals(2, f.events.size());
        assertNotEquals(f.events.getFirst().tenantId(), f.events.getLast().tenantId());
    }

    @Test
    void serverModelCoverageIsValidatedBeforeApplicationStartup() throws Exception {
        try (var f = new Fixture("memory")) {
            var invalid = (ObjectNode) f.model.deepCopy();
            for (var type : invalid.path("type_definitions")) {
                if (type.path("type").asText().equals("book")) {
                    ((ObjectNode) type.path("relations")).remove("can_borrow");
                    if (type.path("metadata").path("relations").isObject()) {
                        ((ObjectNode) type.path("metadata").path("relations")).remove("can_borrow");
                    }
                }
            }
            String incomplete = f.call("POST", "/stores/" + f.store + "/authorization-models", invalid).path("authorization_model_id").asText();
            var missing = assertThrows(IllegalArgumentException.class, () -> OpenFgaHttpAuthorizer.forOntology(
                    f.endpoint, f.store, incomplete, f.library.ontology().schema()));
            assertTrue(missing.getMessage().contains("Book.can_borrow"));
            assertThrows(IllegalStateException.class, () -> OpenFgaHttpAuthorizer.forOntology(f.endpoint, f.store,
                    "01ARZ3NDEKTSV4RRFFQ69G5FAV", f.library.ontology().schema()));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final URI endpoint;
        final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        final String store;
        final String modelId;
        final JsonNode model;
        final LoadedPackBundle library;
        final EntityKey book;
        final EntityKey member;
        final StorageProvider storage;
        final JdbcConnectionPool pool;
        final ApplicationService app;
        final RequestContext context = RequestContext.system("tenant", "librarian");
        final SecurityPrincipal principal = new SecurityPrincipal("librarian", "tenant", Set.of("librarian"));
        final List<CloudEvent> events = new ArrayList<>();
        final Map<String, String> aliases;

        Fixture(String provider) throws Exception {
            String address = System.getenv("FOUNDRY_OPENFGA_URL");
            if (address == null) throw new IllegalStateException("Set FOUNDRY_OPENFGA_URL to an isolated loopback server");
            endpoint = URI.create(address);
            if (!Set.of("localhost", "127.0.0.1", "[::1]").contains(endpoint.getHost())) throw new IllegalArgumentException("Integration store writes require loopback");
            Path root = Path.of(getClass().getResource("/upstream-v0.3.0").toURI());
            library = new DomainPackLoader().loadBundle(List.of(root.resolve("core"), root.resolve("library")));
            var origin = new java.util.Properties();
            try (var resource = getClass().getResourceAsStream("/openfga/library-model.properties")) { origin.load(resource); }
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            assertEquals(origin.getProperty("dsl.sha256"), java.util.HexFormat.of().formatHex(digest.digest(
                    library.assets().permissions().getFirst().dsl().getBytes(StandardCharsets.UTF_8))), "Loaded DSL must match the official model fixture");
            try (var resource = getClass().getResourceAsStream("/openfga/library-model.json")) {
                byte[] bytes = resource.readAllBytes();
                assertEquals(origin.getProperty("json.sha256"), java.util.HexFormat.of().formatHex(digest.digest(bytes)));
                model = JSON.readTree(bytes);
            }
            store = call("POST", "/stores", Map.of("name", "foundry-parity-" + UUID.randomUUID())).path("id").asText();
            modelId = call("POST", "/stores/" + store + "/authorization-models", model).path("authorization_model_id").asText();
            aliases = OpenFgaModelContract.typeNames(library.ontology().schema());
            var authorizer = OpenFgaHttpAuthorizer.forOntology(endpoint, store, modelId, library.ontology().schema());
            if (provider.equals("jdbc")) {
                pool = JdbcConnectionPool.create("jdbc:h2:mem:real_fga_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
                pool.setMaxConnections(1);
                pool.setLoginTimeout(2);
                storage = new JdbcStorageProvider(pool, DatabaseDialect.h2());
            } else {
                pool = null;
                storage = new InMemoryStorageProvider();
            }
            storage.applySchema(context, library.ontology().schema());
            var seeded = seed(context);
            book = seeded.references().get("example.library:book-dune");
            member = seeded.references().get("example.library:member-ada");
            tuple(principal, book, "librarian", true);
            tuple(principal, member, "librarian", true);
            app = ApplicationService.fromBundle(storage, new AuthorizationService(authorizer),
                    new ActionExecutor().withSideEffects(new StandardSideEffectHandler(events::add)), library, AuthorizationMode.ONTOLOGY_TARGETS);
        }

        PackSeeder.SeedResult seed(RequestContext ctx) {
            return new PackSeeder().apply(RequestContext.system(ctx.tenantId(), "bootstrap"), library, storage);
        }

        ActionResult borrow(RequestContext ctx, SecurityPrincipal who) {
            return app.execute(library.actions().get("BorrowBook"), ctx, who, Map.of("book", book.id(), "member", member.id()), "borrow");
        }

        ActionResult returnBook() {
            return app.execute(library.actions().get("ReturnBook"), context, principal, Map.of("book", book.id()), "return");
        }

        void tuple(SecurityPrincipal who, EntityKey resource, String relation, boolean write) {
            var key = Map.of("user", OpenFgaResourceIds.user(who), "relation", relation,
                    "object", OpenFgaResourceIds.resource(who.tenantId(), resource, aliases));
            call("POST", "/stores/" + store + "/write", Map.of("authorization_model_id", modelId,
                    write ? "writes" : "deletes", Map.of("tuple_keys", List.of(key))));
        }

        JsonNode call(String method, String path, Object body) {
            try {
                var request = HttpRequest.newBuilder(URI.create(endpoint.toString() + path)).timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, method + " " + path + ": " + response.statusCode() + " " + response.body());
                return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
            } catch (Exception failed) { throw new AssertionError(failed); }
        }

        @Override
        public void close() throws Exception {
            try { call("DELETE", "/stores/" + store, null); }
            finally {
                if (storage instanceof AutoCloseable resource) resource.close();
                if (pool != null) pool.dispose();
                client.close();
            }
        }
    }
}
