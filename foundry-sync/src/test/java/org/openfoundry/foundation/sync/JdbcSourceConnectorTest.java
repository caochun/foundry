package org.openfoundry.foundation.sync;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class JdbcSourceConnectorTest {
    static final ManagedConnector.ExtractOptions OPTIONS = new ManagedConnector.ExtractOptions(2, null, Duration.ofSeconds(5));

    @Test
    void discoversSchemaAndReadsBoundedPagesWithoutHoldingConnectionsWhileConsuming() throws Exception {
        var source = source();
        var open = new AtomicInteger();
        var opens = new AtomicInteger();
        try (var connector = new JdbcSourceConnector("people", "hr", tracked(source, open, opens))) {
            assertFalse(connector.healthCheck().healthy());
            assertThrows(IllegalStateException.class, () -> connector.fullExtract(OPTIONS));
            connector.initialize(config());
            assertTrue(connector.healthCheck().healthy());
            var table = connector.discoverSchema().tables().stream().filter(t -> t.name().equals("people")).findFirst().orElseThrow();
            assertEquals(List.of("id"), table.primaryKey());
            assertFalse(table.columns().stream().filter(c -> c.name().equals("updated_at")).findFirst().orElseThrow().nullable());
            int initial = opens.get();
            try (var rows = connector.fullExtract(OPTIONS)) {
                assertEquals(initial, opens.get(), "Opening a stream does not issue a query");
                var ids = new ArrayList<String>();
                rows.forEach(record -> {
                    assertEquals(0, open.get(), "No source connection is held while the consumer writes its transaction");
                    assertNull(record.position(), "Full scans do not pretend to be resumable source events");
                    ids.add(record.sourceRecordId());
                });
                assertEquals(List.of("1", "2", "10"), ids);
            }
            assertEquals(initial + 2, opens.get());
            assertThrows(IllegalStateException.class, () -> connector.initialize(config()));
        }
        assertEquals(0, open.get());
    }

    @Test
    void resumesWithinOneTimestampAndPicksUpLaterUpdates() throws Exception {
        var source = source();
        SourceRecord first;
        try (var connector = ready(source); var rows = connector.incrementalExtract(null, OPTIONS)) {
            first = rows.findFirst().orElseThrow();
            assertEquals("1", first.sourceRecordId());
            assertEquals("2026-01-01T00:00:00Z", first.provenance().producedAt().toString());
        }
        var cursor = cursor(first);
        List<SourceRecord> rest;
        try (var connector = ready(source); var rows = connector.incrementalExtract(cursor, OPTIONS)) {
            rest = rows.toList();
            assertEquals(List.of("2", "10"), rest.stream().map(SourceRecord::sourceRecordId).toList());
            assertEquals(List.of(2L, 3L), rest.stream().map(r -> r.position().sequence()).toList());
        }
        cursor = cursor(rest.getLast());
        try (var connector = ready(source); var rows = connector.incrementalExtract(cursor, OPTIONS)) {
            assertEquals(0, rows.count());
        }
        execute(source, "UPDATE people SET name='Changed', updated_at=TIMESTAMP '2026-01-02 00:00:00' WHERE id=1");
        try (var connector = ready(source); var rows = connector.incrementalExtract(cursor, OPTIONS)) {
            var changed = rows.toList();
            assertEquals(1, changed.size());
            assertEquals("Changed", changed.getFirst().data().get("name"));
            assertEquals(4, changed.getFirst().position().sequence());
            assertNotEquals(first.position().eventId(), changed.getFirst().position().eventId());
        }
    }

    @Test
    void rejectsForeignAndMalformedCursors() throws Exception {
        var source = source();
        try (var connector = ready(source)) {
            SourceRecord first;
            try (var rows = connector.incrementalExtract(null, OPTIONS)) {
                first = rows.findFirst().orElseThrow();
            }
            assertThrows(IllegalArgumentException.class, () -> connector.incrementalExtract(new ManagedConnector.Cursor(99, first.position().checkpoint()), OPTIONS));
            var wrong = new java.util.LinkedHashMap<>((Map<String, Object>) first.position().checkpoint());
            wrong.put("signature", "foreign");
            assertThrows(IllegalArgumentException.class, () -> connector.incrementalExtract(new ManagedConnector.Cursor(1, wrong), OPTIONS));
            wrong.put("signature", ((Map<?, ?>) first.position().checkpoint()).get("signature"));
            wrong.put("key", "wrong numeric type");
            assertThrows(IllegalArgumentException.class, () -> connector.incrementalExtract(new ManagedConnector.Cursor(1, wrong), OPTIONS));
        }
    }

    @Test
    void closesShortStreamsAndRejectsNewWorkAfterShutdown() throws Exception {
        var source = source();
        var open = new AtomicInteger();
        var connector = new JdbcSourceConnector("people", "hr", tracked(source, open, new AtomicInteger()));
        connector.initialize(config());
        try (var rows = connector.fullExtract(OPTIONS)) {
            assertTrue(rows.findFirst().isPresent());
        }
        connector.close();
        connector.close();
        assertEquals(0, open.get());
        assertFalse(connector.healthCheck().healthy());
        assertThrows(IllegalStateException.class, () -> connector.fullExtract(OPTIONS));
        assertThrows(IllegalStateException.class, connector::resume);
    }

    @Test
    void pauseBlocksConsumptionAndResumeWakesIt() throws Exception {
        try (var connector = ready(source()); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            connector.pause();
            var entered = new CountDownLatch(1);
            var result = executor.submit(() -> {
                try (var rows = connector.fullExtract(OPTIONS)) {
                    entered.countDown();
                    return rows.count();
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
            connector.resume();
            assertEquals(3L, result.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void shutdownWakesPausedExtractionAndInterruptRestoresFlag() throws Exception {
        try (var connector = ready(source()); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            connector.pause();
            var entered = new CountDownLatch(1);
            var result = executor.submit(() -> {
                try (var rows = connector.fullExtract(OPTIONS)) {
                    entered.countDown();
                    return rows.count();
                }
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> result.get(100, TimeUnit.MILLISECONDS));
            connector.close();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        }
        try (var connector = ready(source())) {
            connector.pause();
            var interrupted = new AtomicBoolean();
            var started = new CountDownLatch(1);
            Thread thread = Thread.ofVirtual().start(() -> {
                try (var rows = connector.fullExtract(OPTIONS)) {
                    started.countDown();
                    rows.count();
                } catch (IllegalStateException expected) {
                    interrupted.set(Thread.currentThread().isInterrupted());
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            thread.interrupt();
            thread.join(2000);
            assertFalse(thread.isAlive());
            assertTrue(interrupted.get());
        }
    }

    @Test
    void rateLimitDelaysSubsequentRecords() throws Exception {
        try (var connector = ready(source()); var rows = connector.fullExtract(new ManagedConnector.ExtractOptions(2, 5, Duration.ofSeconds(5)))) {
            long start = System.nanoTime();
            assertEquals(3, rows.count());
            assertTrue(System.nanoTime() - start >= Duration.ofMillis(350).toNanos());
        }
    }

    @Test
    void checksIdentityAndWatermarkMetadataInsteadOfGuessingTheFirstColumn() throws Exception {
        var source = source();
        execute(source, "CREATE TABLE invalid (name VARCHAR, id INTEGER)");
        try (var connector = new JdbcSourceConnector("x", "hr", source)) {
            assertThrows(IllegalArgumentException.class, () -> connector.initialize(new DatasourceMapping.Connection("source", "invalid", Map.of())));
            assertFalse(connector.healthCheck().healthy());
        }
        execute(source, "CREATE TABLE composite (a INTEGER, b INTEGER, PRIMARY KEY(a,b))");
        try (var connector = new JdbcSourceConnector("x", "hr", source)) {
            assertThrows(IllegalArgumentException.class, () -> connector.initialize(new DatasourceMapping.Connection("source", "composite", Map.of())));
        }
        execute(source, "CREATE TABLE bad_time (id INTEGER PRIMARY KEY, updated_at TIMESTAMP)");
        try (var connector = new JdbcSourceConnector("x", "hr", source)) {
            assertThrows(IllegalArgumentException.class, () -> connector.initialize(new DatasourceMapping.Connection("source", "bad_time", Map.of())));
        }
        try (var connector = new JdbcSourceConnector("x", "hr", source)) {
            assertThrows(IllegalArgumentException.class, () -> connector.initialize(new DatasourceMapping.Connection("source", "people;DROP TABLE people", Map.of())));
        }
    }

    @Test
    void tablesWithoutWatermarksStillSupportFullExtraction() throws Exception {
        var source = source();
        execute(source, "CREATE TABLE codes (code VARCHAR PRIMARY KEY, label VARCHAR)");
        execute(source, "INSERT INTO codes VALUES ('a','A'),('b','B'),('c','C')");
        try (var connector = new JdbcSourceConnector("codes", "hr", source)) {
            connector.initialize(new DatasourceMapping.Connection("source", "codes", Map.of()));
            assertFalse(connector.capabilities().incrementalExtract());
            try (var rows = connector.fullExtract(OPTIONS)) {
                assertEquals(List.of("a", "b", "c"), rows.map(SourceRecord::sourceRecordId).toList());
            }
            assertThrows(IllegalStateException.class, () -> connector.incrementalExtract(null, OPTIONS));
        }
    }

    @Test
    void preservesTimezoneAndExactValues() throws Exception {
        var source = source();
        execute(source, "CREATE TABLE precise (id DECIMAL(25,0) PRIMARY KEY, amount DECIMAL(28,12), updated_at TIMESTAMP WITH TIME ZONE NOT NULL)");
        execute(source, "INSERT INTO precise VALUES (9007199254740993, 123456789012.123456789012, TIMESTAMP WITH TIME ZONE '2026-01-01 08:00:00+08:00')");
        try (var connector = new JdbcSourceConnector("precise", "hr", source)) {
            connector.initialize(new DatasourceMapping.Connection("source", "precise", Map.of()));
            try (var rows = connector.incrementalExtract(null, OPTIONS)) {
                var row = rows.findFirst().orElseThrow();
                assertEquals("9007199254740993", row.sourceRecordId());
                assertEquals("123456789012.123456789012", row.data().get("amount").toString());
                assertEquals("2026-01-01T00:00:00Z", row.data().get("updated_at"));
            }
        }
    }

    @Test
    void sourceQueryFailureReleasesResources() throws Exception {
        var source = source();
        var open = new AtomicInteger();
        try (var connector = new JdbcSourceConnector("people", "hr", tracked(source, open, new AtomicInteger()))) {
            connector.initialize(config());
            execute(source, "DROP TABLE people");
            try (var rows = connector.fullExtract(OPTIONS)) {
                assertThrows(IllegalStateException.class, rows::count);
            }
            assertEquals(0, open.get());
        }
    }

    static ManagedConnector.Cursor cursor(SourceRecord record) {
        return new ManagedConnector.Cursor(record.position().sequence(), record.position().checkpoint());
    }

    static JdbcSourceConnector ready(DataSource source) {
        var connector = new JdbcSourceConnector("people", "hr", source);
        connector.initialize(config());
        return connector;
    }

    static DatasourceMapping.Connection config() {
        return new DatasourceMapping.Connection("source", "people", Map.of());
    }

    static JdbcDataSource source() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:source_" + java.util.UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        execute(source, "CREATE TABLE people (name VARCHAR, id BIGINT PRIMARY KEY, updated_at TIMESTAMP NOT NULL)");
        execute(source, "INSERT INTO people VALUES ('First',1,TIMESTAMP '2026-01-01 00:00:00'),('Second',2,TIMESTAMP '2026-01-01 00:00:00'),('Tenth',10,TIMESTAMP '2026-01-01 00:00:00')");
        return source;
    }

    static void execute(DataSource source, String sql) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static DataSource tracked(DataSource delegate, AtomicInteger open, AtomicInteger opens) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, arguments) -> {
            try {
                Object result = method.invoke(delegate, arguments);
                if (!method.getName().equals("getConnection")) {
                    return result;
                }
                var connection = (Connection) result;
                opens.incrementAndGet();
                if (open.incrementAndGet() > 1) {
                    open.decrementAndGet();
                    connection.close();
                    throw new IllegalStateException("Source pool exhausted");
                }
                var closed = new AtomicBoolean();
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (p, m, a) -> {
                    try {
                        if (m.getName().equals("close") && closed.compareAndSet(false, true)) {
                            open.decrementAndGet();
                        }
                        return m.invoke(connection, a);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
    }
}
