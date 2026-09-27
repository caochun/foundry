package org.openfoundry.foundation.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.EntityKey;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenFgaHttpAuthorizerTest {
    @Test
    void usesStandardCheckPathAndModelBodyWithTenantQualifiedTuples() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/stores/store/check", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] response = "{\"allowed\":true}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var authorizer = new OpenFgaHttpAuthorizer(java.net.URI.create("http://localhost:" + server.getAddress().getPort()), "store", "model");
            var principal = new SecurityPrincipal("u-1", "tenant-a", Set.of());
            var object = new EntityKey("Person", "p-1");
            assertTrue(authorizer.check(principal, "viewer", object));
            var first = new ObjectMapper().readTree(requestBody.get());
            assertEquals("model", first.path("authorization_model_id").asText());
            assertEquals(OpenFgaResourceIds.user(principal), first.path("tuple_key").path("user").asText());
            assertEquals(OpenFgaResourceIds.resource("tenant-a", object), first.path("tuple_key").path("object").asText());
            assertTrue(authorizer.check(new SecurityPrincipal("u-1", "tenant-b", Set.of()), "viewer", object));
            var second = new ObjectMapper().readTree(requestBody.get());
            assertNotEquals(first.path("tuple_key").path("user"), second.path("tuple_key").path("user"));
            assertNotEquals(first.path("tuple_key").path("object"), second.path("tuple_key").path("object"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedAndFailedChecksNeverGrantAccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/stores/store/check", exchange -> {
            byte[] body = "{\"error\":\"unavailable\"}".getBytes();
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var authorizer = new OpenFgaHttpAuthorizer(java.net.URI.create("http://localhost:" + server.getAddress().getPort()), "store", "model");
            assertFalse(authorizer.check(new SecurityPrincipal("u", "tenant", Set.of()), "viewer", new EntityKey("Person", "p")));
        } finally {
            server.stop(0);
        }
        assertNotEquals(OpenFgaResourceIds.resource("a/b", new EntityKey("Item", "c")),
                OpenFgaResourceIds.resource("a", new EntityKey("Item", "b/c")));
    }
}
