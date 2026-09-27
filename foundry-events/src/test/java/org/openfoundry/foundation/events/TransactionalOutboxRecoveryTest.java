package org.openfoundry.foundation.events;

import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.OutboxEntry;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TransactionalOutboxRecoveryTest {
    @Test
    void transactionalProducerRollbackAndFailedAcknowledgementRecoverWithoutRepeatingCompletedConsumer() {
        var pool = JdbcConnectionPool.create("jdbc:h2:mem:producer_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(2);
        try {
            var context = RequestContext.system("tenant", "actor");
            var storage = new JdbcStorageProvider(pool, DatabaseDialect.h2());
            storage.applySchema(context, new OntologySchema("events", "1", List.of(), List.of(), List.of()));
            var store = new JdbcEventStore(pool, DatabaseDialect.h2());
            store.initialize();
            try (var tx = storage.beginTransaction(context)) {
                tx.enqueueOutbox(new OutboxEntry("rolled-back", "tenant", "event", "subject", DeliveryTestClock.START, tx.transactionId(), Map.of()));
            }
            assertTrue(store.pending("tenant", 10).isEmpty());
            try (var tx = storage.beginTransaction(context)) {
                tx.enqueueOutbox(new OutboxEntry("committed", "tenant", "event", "subject", DeliveryTestClock.START, tx.transactionId(), Map.of()));
                tx.commit();
            }
            var clock = new DeliveryTestClock();
            var calls = new AtomicInteger();
            var sink = new JdbcIdempotentEventSink(pool, DatabaseDialect.h2(), "consumer", event -> calls.incrementAndGet(), clock, Duration.ofSeconds(10));
            var failOnce = new AtomicBoolean(true);
            OutboxStore uncertainAcknowledgement = new OutboxStore() {
                @Override public void append(OutboxEvent event) { store.append(event); }
                @Override public List<OutboxEvent> pending(String tenant, int limit) { return store.pending(tenant, limit); }
                @Override public void markPublished(String id, Instant time) { throw new AssertionError("Legacy path used"); }
                @Override public List<OutboxClaim> claim(String tenant, int limit, Instant now, Duration lease) { return store.claim(tenant, limit, now, lease); }
                @Override public boolean renew(OutboxClaim claim, Instant now, Duration lease) { return store.renew(claim, now, lease); }
                @Override public boolean fail(OutboxClaim claim, Instant now, Instant retry) { return store.fail(claim, now, retry); }
                @Override public boolean complete(OutboxClaim claim, Instant now) {
                    if (failOnce.getAndSet(false)) throw new IllegalStateException("Acknowledgement unavailable");
                    return store.complete(claim, now);
                }
            };
            var dispatcher = new OutboxDispatcher(uncertainAcknowledgement, sink, "source", clock, Duration.ofSeconds(10), Duration.ofSeconds(1));
            assertEquals(new OutboxDispatcher.DispatchResult(0, 1), dispatcher.dispatch("tenant", 10));
            assertEquals(1, calls.get());
            assertEquals(1, store.pending("tenant", 10).size());
            clock.advance(1);
            assertEquals(new OutboxDispatcher.DispatchResult(1, 0), dispatcher.dispatch("tenant", 10));
            assertEquals(1, calls.get());
            assertTrue(store.pending("tenant", 10).isEmpty());
        } finally {
            pool.dispose();
        }
    }

    @Test
    void nonAutocommitDataSourceDoesNotLoseAcknowledgementsOrReceipts() {
        var original = new JdbcDataSource();
        original.setURL("jdbc:h2:mem:event_defaults_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        DataSource configured = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            try {
                Object result = method.invoke(original, args);
                if (result instanceof Connection connection) {
                    connection.setAutoCommit(false);
                    connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                }
                return result;
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        var store = new JdbcEventStore(configured, DatabaseDialect.h2());
        store.initialize();
        var event = new OutboxEvent("event", "tenant", "event", "subject", DeliveryTestClock.START, "tx", Map.of(), null);
        store.append(event);
        assertEquals(1, store.pending("tenant", 1).size());
        var clock = new DeliveryTestClock();
        var calls = new AtomicInteger();
        var sink = new JdbcIdempotentEventSink(configured, DatabaseDialect.h2(), "consumer", ignored -> calls.incrementAndGet(), clock, Duration.ofSeconds(10));
        var dispatcher = new OutboxDispatcher(store, sink, "source", clock, Duration.ofSeconds(10), Duration.ofSeconds(1));
        assertEquals(new OutboxDispatcher.DispatchResult(1, 0), dispatcher.dispatch("tenant", 1));
        assertTrue(store.pending("tenant", 1).isEmpty());
        sink.publish(new CloudEvent("1.0", "event", "source", "event", "subject", DeliveryTestClock.START, "tenant", "tx", Map.of()));
        assertEquals(1, calls.get());
    }
}
