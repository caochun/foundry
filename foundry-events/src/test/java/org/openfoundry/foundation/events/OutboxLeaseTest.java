package org.openfoundry.foundation.events;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OutboxLeaseTest {
    private static final Duration LEASE = Duration.ofSeconds(10);

    @TestFactory
    Stream<DynamicTest> leasesAcrossStores() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "claim excludes leased and future retries", this::retry),
                test(provider, "takeover fences late acknowledgement failure and renewal", this::takeover),
                test(provider, "renewal and tenant isolation", this::renewal),
                test(provider, "two dispatchers cannot publish one active claim", this::concurrency),
                test(provider, "slow batch skips expired claims", this::slowBatch)));
    }

    private DynamicTest test(String provider, String label, Consumer<OutboxStore> verify) {
        return DynamicTest.dynamicTest(provider + " " + label, () -> {
            OutboxStore store;
            if (provider.equals("memory")) store = new InMemoryOutboxStore();
            else {
                var data = new JdbcDataSource();
                data.setURL("jdbc:h2:mem:outbox_leases_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
                var jdbc = new JdbcEventStore(data, DatabaseDialect.h2());
                jdbc.initialize();
                store = jdbc;
            }
            store.append(event("a", "tenant"));
            verify.accept(store);
        });
    }

    private void retry(OutboxStore store) {
        var now = DeliveryTestClock.START;
        var first = store.claim("tenant", 10, now, LEASE).getFirst();
        assertEquals(1, first.attempt());
        assertTrue(store.claim("tenant", 10, now.plusSeconds(1), LEASE).isEmpty());
        assertTrue(store.fail(first, now.plusSeconds(1), now.plusSeconds(5)));
        assertTrue(store.claim("tenant", 10, now.plusSeconds(4), LEASE).isEmpty());
        var retry = store.claim("tenant", 10, now.plusSeconds(5), LEASE).getFirst();
        assertEquals(2, retry.attempt());
        assertNotEquals(first.token(), retry.token());
        assertThrows(IllegalStateException.class, () -> store.markPublished("a", now));
        assertTrue(store.complete(retry, now.plusSeconds(6)));
        assertTrue(store.pending("tenant", 10).isEmpty());
    }

    private void takeover(OutboxStore store) {
        var now = DeliveryTestClock.START;
        var stale = store.claim("tenant", 1, now, LEASE).getFirst();
        var current = store.claim("tenant", 1, now.plusSeconds(10), LEASE).getFirst();
        assertEquals(2, current.attempt());
        assertFalse(store.complete(stale, now.plusSeconds(11)));
        assertFalse(store.fail(stale, now.plusSeconds(11), now.plusSeconds(15)));
        assertFalse(store.renew(stale, now.plusSeconds(11), LEASE));
        assertTrue(store.complete(current, now.plusSeconds(11)));
        assertFalse(store.complete(current, now.plusSeconds(12)));
    }

    private void renewal(OutboxStore store) {
        var now = DeliveryTestClock.START;
        store.append(event("other", "other"));
        var first = store.claim("tenant", 1, now, LEASE).getFirst();
        assertTrue(store.renew(first, now.plusSeconds(9), LEASE));
        assertTrue(store.claim("tenant", 10, now.plusSeconds(10), LEASE).isEmpty());
        var forged = new OutboxClaim(event("a", "other"), first.token(), first.attempt(), first.leaseUntil());
        assertFalse(store.complete(forged, now.plusSeconds(10)));
        assertTrue(store.complete(first, now.plusSeconds(18)));
        assertEquals(List.of("other"), store.pending("other", 10).stream().map(OutboxEvent::id).toList());
    }

    private void concurrency(OutboxStore store) {
        var clock = new DeliveryTestClock();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        EventSink sink = event -> {
            calls.incrementAndGet();
            entered.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException failure) { throw new AssertionError(failure); }
        };
        var a = new OutboxDispatcher(store, sink, "source", clock, LEASE, Duration.ofSeconds(1));
        var b = new OutboxDispatcher(store, sink, "source", clock, LEASE, Duration.ofSeconds(1));
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var running = executor.submit(() -> a.dispatch("tenant", 10));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertEquals(new OutboxDispatcher.DispatchResult(0, 0), b.dispatch("tenant", 10));
            release.countDown();
            assertEquals(new OutboxDispatcher.DispatchResult(1, 0), running.get(10, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } catch (Exception failure) {
            throw new AssertionError(failure);
        } finally {
            release.countDown();
        }
    }

    private void slowBatch(OutboxStore store) {
        store.append(event("b", "tenant"));
        var clock = new DeliveryTestClock();
        var calls = new AtomicInteger();
        var dispatcher = new OutboxDispatcher(store, event -> {
            calls.incrementAndGet();
            clock.advance(11);
        }, "source", clock, LEASE, Duration.ofSeconds(1));
        assertEquals(new OutboxDispatcher.DispatchResult(0, 2), dispatcher.dispatch("tenant", 2));
        assertEquals(1, calls.get());
        assertEquals(2, store.pending("tenant", 10).size());
        var retry = new OutboxDispatcher(store, event -> calls.incrementAndGet(), "source", clock, LEASE, Duration.ofSeconds(1));
        assertEquals(new OutboxDispatcher.DispatchResult(2, 0), retry.dispatch("tenant", 2));
        assertEquals(3, calls.get()); // At-least-once: the callback that outlived its lease is delivered again.
    }

    private static OutboxEvent event(String id, String tenant) {
        return new OutboxEvent(id, tenant, "event", "subject", DeliveryTestClock.START, "tx", Map.of(), null);
    }
}
