package org.openfoundry.foundation.actions;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.events.CloudEvent;
import org.openfoundry.foundation.spi.RequestContext;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StandardSideEffectHandlerTest {
    @Test
    void eventDeliveryUsesStableIdentityOriginalTimeAndTransaction() {
        var events = new ArrayList<CloudEvent>();
        var handler = new StandardSideEffectHandler(events::add);
        handler.execute(invocation("event", Map.of("type", "example.changed", "data", Map.of("id", "item"))));
        var event = events.getFirst();
        assertEquals("action/task", event.id());
        assertEquals("example.changed", event.type());
        assertEquals("tenant", event.tenantId());
        assertEquals("original-transaction", event.transactionId());
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), event.time());
        assertEquals(Map.of("id", "item"), event.data());
    }

    @Test
    void webhookPostsJsonAndStableIdempotencyKeyOnlyToPermittedEndpoint() throws Exception {
        var body = new AtomicReference<String>();
        var key = new AtomicReference<String>();
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            calls.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            key.set(exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/hook");
            var handler = new StandardSideEffectHandler(null, client, endpoint::equals);
            var config = Map.<String, Object>of("url", endpoint.toString(), "body", Map.of("id", "item"));
            handler.execute(invocation("webhook", config));
            assertEquals("{\"id\":\"item\"}", body.get());
            assertEquals("action/task", key.get());
            assertThrows(SecurityException.class, () -> handler.execute(invocation("webhook", Map.of("url", endpoint + "/other"))));
            assertThrows(IllegalArgumentException.class, () -> handler.execute(invocation("webhook", Map.of(
                    "url", endpoint.toString(), "headers", Map.of("Idempotency-Key", "forged")))));
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void redirectsAndNonSuccessResponsesAreFailuresAndCannotBypassEndpointPolicy() throws Exception {
        var forbidden = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().add("Location", "/forbidden");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.createContext("/forbidden", exchange -> {
            forbidden.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try (var client = HttpClient.newHttpClient(); var unsafe = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()) {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/redirect");
            var handler = new StandardSideEffectHandler(null, client, endpoint::equals);
            assertThrows(IllegalStateException.class, () -> handler.execute(invocation("webhook", Map.of("url", endpoint.toString()))));
            assertEquals(0, forbidden.get());
            assertThrows(IllegalArgumentException.class, () -> new StandardSideEffectHandler(null, unsafe, endpoint::equals));
        } finally {
            server.stop(0);
        }
    }

    private static SideEffectHandler.Invocation invocation(String type, Map<String, Object> config) {
        return new SideEffectHandler.Invocation("action", "Work", "task", type, config, "action/task", 1,
                Instant.parse("2030-01-01T00:00:00Z"), "original-transaction", RequestContext.system("tenant", "actor"));
    }
}
