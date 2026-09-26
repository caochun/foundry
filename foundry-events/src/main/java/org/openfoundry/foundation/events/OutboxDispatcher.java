package org.openfoundry.foundation.events;

import java.time.Instant;

/** Publishes pending outbox rows; failed rows remain pending for the next poll. */
public final class OutboxDispatcher {
    private final OutboxStore store;
    private final EventSink sink;
    private final String source;

    public OutboxDispatcher(OutboxStore store, EventSink sink, String source) {
        this.store = store;
        this.sink = sink;
        this.source = source;
    }

    public DispatchResult dispatch(String tenantId, int limit) {
        int published = 0;
        int failed = 0;
        for (OutboxEvent event : store.pending(tenantId, limit)) {
            CloudEvent cloudEvent = new CloudEvent("1.0", event.id(), source, event.type(),
                    event.subject(), event.occurredAt(), event.tenantId(), event.transactionId(), event.data());
            try {
                sink.publish(cloudEvent);
                store.markPublished(event.id(), Instant.now());
                published++;
            } catch (RuntimeException exception) {
                failed++;
            }
        }
        return new DispatchResult(published, failed);
    }

    public record DispatchResult(int published, int failed) {}
}
