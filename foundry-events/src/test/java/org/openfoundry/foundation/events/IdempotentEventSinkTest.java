package org.openfoundry.foundation.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IdempotentEventSinkTest {
    @Test
    void suppressesDuplicateEventIds() {
        AtomicInteger calls = new AtomicInteger();
        IdempotentEventSink sink = new IdempotentEventSink(event -> calls.incrementAndGet());
        CloudEvent event = new CloudEvent("1.0", "event-1", "test", "type", "subject",
                Instant.now(), "tenant", "tx", Map.of());
        sink.publish(event);
        sink.publish(event);
        assertEquals(1, calls.get());
    }
}
