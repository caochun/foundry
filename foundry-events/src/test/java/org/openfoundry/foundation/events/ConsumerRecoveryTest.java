package org.openfoundry.foundation.events;

import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ConsumerRecoveryTest {
    @Test
    void memoryFailureCanRetryAndScopeAndContentAreBound() {
        var calls = new AtomicInteger();
        var sink = new IdempotentEventSink(event -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("try again");
        });
        var event = event("tenant", "source", "id", Map.of("value", 1));
        assertThrows(IllegalStateException.class, () -> sink.publish(event));
        assertThrows(IllegalArgumentException.class, () -> sink.publish(event("tenant", "source", "id", Map.of("value", 2))));
        sink.publish(event);
        sink.publish(event);
        sink.publish(event("other", "source", "id", Map.of()));
        sink.publish(event("tenant", "different-source", "id", Map.of()));
        assertEquals(4, calls.get());
    }

    @Test
    void memoryConcurrentDuplicateWaitsForCompletionAndDoesNotAcknowledgeFailedWork() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var event = event("tenant", "source", "id", Map.of());
        var sink = new IdempotentEventSink(ignored -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                throw new IllegalStateException("Failed callback");
            }
        });
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> assertThrows(IllegalStateException.class, () -> sink.publish(event)));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var second = executor.submit(() -> {
                secondStarted.countDown();
                return assertThrows(IllegalStateException.class, () -> sink.publish(event));
            });
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertEquals(1, calls.get());
            sink.publish(event);
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void jdbcFailureRetriesAcrossInstancesAndCompletionIsScopedByConsumerTenantAndSource() {
        var data = data();
        var event = event("tenant", "source", "id", Map.of());
        var attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> new JdbcIdempotentEventSink(data, ignored -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("temporary");
        }).publish(event));
        EventSink callback = ignored -> attempts.incrementAndGet();
        new JdbcIdempotentEventSink(data, callback).publish(event);
        new JdbcIdempotentEventSink(data, callback).publish(event);
        new JdbcIdempotentEventSink(data, "second-consumer", callback).publish(event);
        new JdbcIdempotentEventSink(data, callback).publish(event("other", "source", "id", Map.of()));
        new JdbcIdempotentEventSink(data, callback).publish(event("tenant", "different-source", "id", Map.of()));
        assertEquals(5, attempts.get());
        assertThrows(IllegalArgumentException.class, () -> new JdbcIdempotentEventSink(data, callback)
                .publish(event("tenant", "source", "id", Map.of("changed", true))));
    }

    @Test
    void busyConsumerIsNeverAcknowledgedAndAnExpiredOwnerCannotFinishAnotherAttempt() throws Exception {
        var data = data();
        var clock = new DeliveryTestClock();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var event = event("tenant", "source", "id", Map.of());
        var calls = new AtomicInteger();
        var original = new JdbcIdempotentEventSink(data, DatabaseDialect.h2(), "consumer", ignored -> {
            calls.incrementAndGet();
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        }, clock, Duration.ofSeconds(10));
        var next = new JdbcIdempotentEventSink(data, DatabaseDialect.h2(), "consumer", ignored -> calls.incrementAndGet(), clock, Duration.ofSeconds(10));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> assertThrows(EventDeliveryException.class, () -> original.publish(event)));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var busy = assertThrows(EventDeliveryException.class, () -> next.publish(event));
            assertEquals(EventDeliveryException.Reason.BUSY, busy.reason());
            clock.advance(10);
            next.publish(event);
            release.countDown();
            assertEquals(EventDeliveryException.Reason.LEASE_LOST, first.get(10, TimeUnit.SECONDS).reason());
            next.publish(event);
            assertEquals(2, calls.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void oldAmbiguousReceiptsRequireExplicitEvidenceOrReplayDecision() throws Exception {
        var data = data();
        var calls = new AtomicInteger();
        var sink = new JdbcIdempotentEventSink(data, ignored -> calls.incrementAndGet());
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO of_consumed_events VALUES ('confirmed', CURRENT_TIMESTAMP), ('replay', CURRENT_TIMESTAMP)");
        }
        var confirmed = event("tenant", "source", "confirmed", Map.of());
        var replay = event("tenant", "source", "replay", Map.of());
        assertEquals(EventDeliveryException.Reason.LEGACY_REVIEW_REQUIRED,
                assertThrows(EventDeliveryException.class, () -> sink.publish(confirmed)).reason());
        sink.confirmLegacyCompletion(confirmed);
        sink.publish(confirmed);
        assertEquals(0, calls.get());
        assertThrows(EventDeliveryException.class, () -> sink.publish(replay));
        sink.allowLegacyReplay(replay);
        sink.publish(replay);
        sink.publish(replay);
        assertEquals(1, calls.get());
        assertThrows(IllegalStateException.class, () -> sink.allowLegacyReplay(confirmed));
        assertThrows(EventDeliveryException.class, () -> sink.publish(event("other", "source", "confirmed", Map.of())));
    }

    @Test
    void callbackRunsOutsideTheReceiptTransactionAndSupportsOneConnectionPool() {
        var pool = JdbcConnectionPool.create("jdbc:h2:mem:consumer_pool_" + System.nanoTime(), "sa", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(2);
        try {
            var sink = new JdbcIdempotentEventSink(pool, event -> {
                try (var connection = pool.getConnection(); var statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE IF NOT EXISTS delivered (id VARCHAR(255) PRIMARY KEY)");
                    statement.executeUpdate("INSERT INTO delivered VALUES ('done')");
                } catch (java.sql.SQLException failure) { throw new IllegalStateException(failure); }
            });
            sink.publish(event("tenant", "source", "id", Map.of()));
            sink.publish(event("tenant", "source", "id", Map.of()));
        } finally {
            pool.dispose();
        }
    }

    @Test
    void payloadsAreDeeplyImmutableAndAcceptOptionalNullValues() {
        var nested = new HashMap<String, Object>();
        nested.put("optional", null);
        var payload = new HashMap<String, Object>();
        payload.put("nested", nested);
        var event = event("tenant", "source", "id", payload);
        var outbox = new OutboxEvent("id", "tenant", "event", "subject", DeliveryTestClock.START, "tx", payload, null);
        nested.put("private", "changed");
        assertEquals(1, ((Map<?, ?>) event.data().get("nested")).size());
        assertEquals(event.data(), outbox.data());
        assertThrows(UnsupportedOperationException.class, () -> ((Map<?, ?>) event.data().get("nested")).clear());
        var entry = new org.openfoundry.foundation.spi.OutboxEntry("id", "tenant", "event", "subject", DeliveryTestClock.START, "tx", payload);
        nested.put("new", true);
        assertEquals(2, ((Map<?, ?>) entry.data().get("nested")).size());
    }

    static CloudEvent event(String tenant, String source, String id, Map<String, Object> data) {
        return new CloudEvent("1.0", id, source, "event", "subject", DeliveryTestClock.START, tenant, "tx", data);
    }

    private static JdbcDataSource data() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:consumer_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        return data;
    }
}
