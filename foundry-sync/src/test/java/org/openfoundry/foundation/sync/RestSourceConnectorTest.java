package org.openfoundry.foundation.sync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class RestSourceConnectorTest {
    static final ManagedConnector.ExtractOptions OPTIONS = new ManagedConnector.ExtractOptions(2, null, Duration.ofSeconds(3));
    static final Map<String, Object> INCREMENTAL = Map.of("sinceParam", "after", "cursorField", "resume", "sequenceField", "seq",
            "eventIdField", "event", "timestampField", "at", "operationField", "operation");

    @Test
    void paginatesShortAndEmptyPagesAndPreservesExactValues() throws Exception {
        try (var server = new Server(exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            if (!query.contains("cursor=")) return "{\"data\":[{\"id\":9007199254740993,\"amount\":1.000000000000000001}],\"next\":\"a +/&?中\"}";
            String cursor = query(query).get("cursor");
            if (cursor.equals("a +/&?中")) return "{\"data\":[],\"next\":\"end\"}";
            return "{\"data\":[{\"id\":2}],\"next\":null}";
        }); var connector = server.connector(Map.of()); var stream = connector.fullExtract(OPTIONS)) {
            assertEquals(0, server.calls.get(), "Lazy stream opening");
            var records = stream.toList();
            assertEquals(3, server.calls.get());
            assertEquals(List.of("9007199254740993", "2"), records.stream().map(SourceRecord::sourceRecordId).toList());
            assertEquals("1.000000000000000001", records.getFirst().data().get("amount").toString());
            assertNull(records.getFirst().position());
        }
    }

    @Test
    void shortCircuitClosesWithoutFetchingAnotherPage() throws Exception {
        try (var server = new Server(exchange -> "{\"data\":[{\"id\":1}],\"next\":\"another\"}");
             var connector = server.connector(Map.of()); var records = connector.fullExtract(OPTIONS)) {
            assertEquals("1", records.findFirst().orElseThrow().sourceRecordId());
            assertEquals(1, server.calls.get());
        }
    }

    @Test
    void resumesAtAnEventCursorRatherThanPageToken() throws Exception {
        var requests = new ArrayList<Map<String, String>>();
        try (var server = new Server(exchange -> {
            var query = query(exchange.getRequestURI().getRawQuery());
            requests.add(query);
            return query.containsKey("after") ? "{\"data\":[" + event(2, "p2", "UPDATE") + "]}" : "{\"data\":[" + event(1, "p1", "INSERT") + "," + event(2, "p2", "UPDATE") + "],\"next\":\"next-page\"}";
        })) {
            SourceRecord first;
            try (var connector = server.connector(INCREMENTAL); var records = connector.incrementalExtract(null, OPTIONS)) {
                first = records.findFirst().orElseThrow();
            }
            try (var connector = server.connector(INCREMENTAL);
                 var records = connector.incrementalExtract(new ManagedConnector.Cursor(first.position().sequence(), first.position().checkpoint()), OPTIONS)) {
                var remaining = records.toList();
                assertEquals(1, remaining.size());
                assertEquals("p2", remaining.getFirst().sourceRecordId());
                assertEquals("UPDATE", remaining.getFirst().operation());
                assertEquals(2, remaining.getFirst().position().sequence());
                assertEquals("r1", requests.getLast().get("after"));
                assertFalse(requests.getLast().containsKey("cursor"));
            }
        }
    }

    @Test
    void rejectsInvalidOrForeignCheckpointsBeforeHttp() throws Exception {
        try (var server = new Server(exchange -> "[" + event(1, "p1", "INSERT") + "]")) {
            SourceRecord first;
            try (var connector = server.connector(INCREMENTAL); var rows = connector.incrementalExtract(null, OPTIONS)) {
                first = rows.findFirst().orElseThrow();
            }
            int before = server.calls.get();
            try (var connector = server.connector(INCREMENTAL)) {
                assertThrows(IllegalArgumentException.class, () -> connector.incrementalExtract(new ManagedConnector.Cursor(2, first.position().checkpoint()), OPTIONS));
            }
            try (var connector = new RestSourceConnector("People", "People", java.net.http.HttpClient.newHttpClient(), Map.of(), "new-account")) {
                connector.initialize(server.config(INCREMENTAL));
                assertThrows(IllegalArgumentException.class, () -> connector.incrementalExtract(new ManagedConnector.Cursor(1, first.position().checkpoint()), OPTIONS));
            }
            assertEquals(before, server.calls.get());
        }
    }

    @TestFactory
    Stream<DynamicTest> malformedResponses() {
        return Stream.of("{\"id\":1,\"id\":2}", "{\"id\":1} trailing", "{\"data\":null}", "{\"data\":\"wrong\"}",
                "[1]", "{\"next\":\"cursor\"}", "{\"id\":null}", "[] []", "[{\"id\":1},{\"id\":2},{\"id\":3}]")
                .map(body -> DynamicTest.dynamicTest(body, () -> {
                    try (var server = new Server(exchange -> body); var connector = server.connector(Map.of()); var records = connector.fullExtract(OPTIONS)) {
                        assertThrows(IllegalArgumentException.class, records::toList);
                    }
                }));
    }

    @Test
    void rejectsCyclesAndHonorsPageBudget() throws Exception {
        try (var server = new Server(exchange -> "{\"data\":[{\"id\":1}],\"next\":\"same\"}"); var connector = server.connector(Map.of()); var rows = connector.fullExtract(OPTIONS)) {
            assertThrows(IllegalStateException.class, rows::toList);
            assertEquals(2, server.calls.get());
        }
        try (var server = new Server(exchange -> "{\"data\":[],\"next\":\"more\"}"); var connector = server.connector(Map.of("maxPages", 1)); var rows = connector.fullExtract(OPTIONS)) {
            assertThrows(IllegalStateException.class, rows::toList);
            assertEquals(1, server.calls.get());
        }
    }

    @Test
    void rejectsNonmonotonicEventsAndMissingTimestamps() throws Exception {
        for (String body : List.of("[" + event(2, "p1", "INSERT") + "," + event(1, "p2", "UPDATE") + "]",
                "[" + event(1, "p1", "INSERT").replace("2026-01-01T00:00:00Z", "invalid") + "]")) {
            try (var server = new Server(exchange -> body); var connector = server.connector(INCREMENTAL); var rows = connector.incrementalExtract(null, OPTIONS)) {
                assertThrows(RuntimeException.class, rows::toList);
            }
        }
    }

    @Test
    void requestDeadlineIncludesSlowBodyAndCloseCancelsIt() throws Exception {
        try (var server = new Server(exchange -> {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('[');
            exchange.getResponseBody().flush();
            Thread.sleep(2000);
            return null;
        }); var connector = server.connector(Map.of()); var rows = connector.fullExtract(new ManagedConnector.ExtractOptions(2, null, Duration.ofMillis(100)))) {
            long start = System.nanoTime();
            assertThrows(IllegalStateException.class, rows::toList);
            assertTrue(System.nanoTime() - start < Duration.ofSeconds(1).toNanos());
        }
        var received = new CountDownLatch(1);
        try (var server = new Server(exchange -> { received.countDown(); Thread.sleep(2000); return "[]"; });
             var connector = server.connector(Map.of()); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = workers.submit(() -> { try (var rows = connector.fullExtract(OPTIONS)) { return rows.toList(); } });
            assertTrue(received.await(2, TimeUnit.SECONDS));
            connector.close();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void limitsResponseBytesAndRejectsRedirectAndInvalidUtf8() throws Exception {
        try (var server = new Server(exchange -> "[{\"id\":1,\"large\":\"" + "x".repeat(1024) + "\"}]"); var connector = server.connector(Map.of("maxResponseBytes", 100)); var rows = connector.fullExtract(OPTIONS)) {
            assertThrows(IllegalStateException.class, rows::toList);
        }
        try (var server = new Server(exchange -> { exchange.getResponseHeaders().set("Location", "/secret"); exchange.sendResponseHeaders(302, -1); return null; });
             var connector = server.connector(Map.of()); var rows = connector.fullExtract(OPTIONS)) {
            assertThrows(IllegalStateException.class, rows::toList);
            assertEquals(1, server.calls.get());
        }
        try (var server = new Server(exchange -> { exchange.sendResponseHeaders(200, 2); exchange.getResponseBody().write(new byte[]{(byte) 0xc3, 0x28}); return null; });
             var connector = server.connector(Map.of()); var rows = connector.fullExtract(OPTIONS)) {
            assertThrows(IllegalArgumentException.class, rows::toList);
        }
    }

    @Test
    void pauseResumeAndRateLimitControlPulls() throws Exception {
        try (var server = new Server(exchange -> "[{\"id\":1},{\"id\":2}]"); var connector = server.connector(Map.of()); var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            connector.pause();
            var result = workers.submit(() -> { try (var rows = connector.fullExtract(new ManagedConnector.ExtractOptions(2, 5, Duration.ofSeconds(3)))) { return rows.count(); } });
            assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
            assertEquals(0, server.calls.get());
            long start = System.nanoTime();
            connector.resume();
            assertEquals(2, result.get(2, TimeUnit.SECONDS));
            assertTrue(System.nanoTime() - start >= Duration.ofMillis(180).toNanos());
        }
    }

    @Test
    void lifecycleAndExplicitCapabilitiesDoNotPretendSnapshotIsIncremental() throws Exception {
        try (var server = new Server(exchange -> "[]"); var connector = new RestSourceConnector("People", "People")) {
            assertFalse(connector.healthCheck().healthy());
            connector.initialize(server.config(Map.of()));
            assertTrue(connector.healthCheck().healthy());
            assertFalse(connector.capabilities().discovery());
            assertFalse(connector.capabilities().incrementalExtract());
            assertThrows(UnsupportedOperationException.class, connector::discoverSchema);
            assertThrows(UnsupportedOperationException.class, () -> connector.incrementalExtract(null, OPTIONS));
            assertThrows(IllegalStateException.class, () -> connector.initialize(server.config(Map.of())));
            connector.close();
            assertFalse(connector.healthCheck().healthy());
            assertThrows(IllegalStateException.class, () -> connector.fullExtract(OPTIONS));
        }
    }

    @Test
    void rejectsInvalidConfigurationBeforeHttp() throws Exception {
        for (Map<String, Object> options : List.<Map<String, Object>>of(Map.of("sinceParam", "after"), Map.of("unexpected", true), Map.of("maxPages", 1.2), Map.of("dataField", "next"))) {
            try (var server = new Server(exchange -> "[]"); var connector = new RestSourceConnector("People", "People")) {
                assertThrows(IllegalArgumentException.class, () -> connector.initialize(server.config(options)));
                assertEquals(0, server.calls.get());
            }
        }
    }

    static String event(long sequence, String id, String operation) {
        return "{\"id\":\"" + id + "\",\"name\":\"Person\",\"seq\":" + sequence + ",\"event\":\"e" + sequence
                + "\",\"resume\":\"r" + sequence + "\",\"at\":\"2026-01-01T00:00:00Z\",\"operation\":\"" + operation + "\"}";
    }
    static Map<String, String> query(String raw) {
        var result = new java.util.LinkedHashMap<String, String>();
        if (raw != null) for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }
    interface Response { String serve(HttpExchange exchange) throws Exception; }
    static final class Server implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Server(Response response) throws Exception {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.setExecutor(executor);
            server.createContext("/people", exchange -> {
                calls.incrementAndGet();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                try {
                    if (exchange.getRequestMethod().equals("HEAD")) { exchange.sendResponseHeaders(200, -1); return; }
                    String body = response.serve(exchange);
                    if (body != null) {
                        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                } catch (Exception ignored) {
                    // Client cancellation and deliberate test deadlines close the response stream.
                } finally { exchange.close(); }
            });
            server.start();
        }
        DatasourceMapping.Connection config(Map<String, Object> properties) {
            return new DatasourceMapping.Connection("http://localhost:" + server.getAddress().getPort() + "/people", "people", properties);
        }
        RestSourceConnector connector(Map<String, Object> properties) {
            var connector = new RestSourceConnector("People", "People");
            connector.initialize(config(properties));
            return connector;
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
