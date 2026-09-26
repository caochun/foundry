package org.openfoundry.foundation.events;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcEventStoreTest {
    @Test
    void storesImmutableAuditAndPendingOutboxEvents() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:events_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcEventStore store = new JdbcEventStore(dataSource, DatabaseDialect.h2());
        store.initialize();
        Instant now = Instant.now();
        store.append(new AuditRecord("audit-1", now, "tenant", "actor", "update", "Person", "p-1", null, "tx-1", "success", Map.of("status", "changed")));
        store.append(new OutboxEvent("event-1", "tenant", "person.updated", "Person/p-1", now, "tx-1", Map.of("id", "p-1"), null));

        assertEquals(1, store.query("tenant", "Person", "p-1", null, null, 10).size());
        assertEquals(1, store.pending("tenant", 10).size());
        store.markPublished("event-1", Instant.now());
        assertEquals(0, store.pending("tenant", 10).size());
    }
}
