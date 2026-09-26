package org.openfoundry.foundation.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.EntityKey;

import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenFgaHttpAuthorizerTest {
    @Test
    void sendsCheckTupleAndReadsAllowed() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/stores/store/authorization-models/model/check", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] response = "{\"allowed\":true}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var authorizer = new OpenFgaHttpAuthorizer(
                    java.net.http.HttpClient.newHttpClient(), new com.fasterxml.jackson.databind.ObjectMapper(),
                    java.net.URI.create("http://localhost:" + server.getAddress().getPort()), "store", "model");
            assertTrue(authorizer.check(new SecurityPrincipal("u-1", "tenant", Set.of()),
                    "viewer", new EntityKey("Person", "p-1")));
            assertEquals(true, requestBody.get().contains("user:u-1"));
            assertEquals(true, requestBody.get().contains("Person:p-1"));
        } finally {
            server.stop(0);
        }
    }
}
