package org.openfoundry.foundation.events;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcIdempotentEventSinkTest {
    @Test
    void persistsDeduplicationAcrossSinkInstances() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:dedupe_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        new JdbcEventStore(dataSource, DatabaseDialect.h2()).initialize();
        AtomicInteger calls = new AtomicInteger();
        CloudEvent event = new CloudEvent("1.0", "event-1", "test", "type", "subject", Instant.now(), "tenant", "tx", Map.of());
        new JdbcIdempotentEventSink(dataSource, ignored -> calls.incrementAndGet()).publish(event);
        new JdbcIdempotentEventSink(dataSource, ignored -> calls.incrementAndGet()).publish(event);
        assertEquals(1, calls.get());
    }
}
