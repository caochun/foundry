package org.openfoundry.foundation.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutboxDispatcherTest {
    @Test
    void failedPublishRemainsPendingAndRetries() {
        InMemoryOutboxStore store = new InMemoryOutboxStore();
        store.append(new OutboxEvent("event-1", "tenant", "test.event", "subject",
                Instant.now(), "tx-1", Map.of(), null));
        AtomicInteger attempts = new AtomicInteger();
        OutboxDispatcher dispatcher = new OutboxDispatcher(store, event -> {
            if (attempts.getAndIncrement() == 0) throw new IllegalStateException("temporary failure");
        }, "test");

        assertEquals(new OutboxDispatcher.DispatchResult(0, 1), dispatcher.dispatch("tenant", 10));
        assertEquals(1, store.pending("tenant", 10).size());
        assertEquals(new OutboxDispatcher.DispatchResult(1, 0), dispatcher.dispatch("tenant", 10));
        assertEquals(0, store.pending("tenant", 10).size());
    }
}
