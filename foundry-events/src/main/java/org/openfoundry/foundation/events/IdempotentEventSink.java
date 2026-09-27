package org.openfoundry.foundation.events;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

/** Process-local completion deduplication; failed work is never remembered as success. */
public final class IdempotentEventSink implements EventSink {
    private final EventSink delegate;
    private final String consumer;
    private final ConcurrentHashMap<Key, Work> work = new ConcurrentHashMap<>();

    public IdempotentEventSink(EventSink delegate) {
        this("default", delegate);
    }

    public IdempotentEventSink(String consumer, EventSink delegate) {
        if (consumer == null || consumer.isBlank()) throw new IllegalArgumentException("Consumer ID is required");
        this.consumer = consumer;
        this.delegate = Objects.requireNonNull(delegate);
    }

    @Override
    public void publish(CloudEvent event) {
        var identity = EventIdentity.of(consumer, event);
        var key = new Key(identity.tenant(), identity.key());
        var proposed = new Work(identity.fingerprint(), Thread.currentThread().threadId(), new CompletableFuture<>());
        while (true) {
            var existing = work.putIfAbsent(key, proposed);
            if (existing == null) break;
            if (!existing.fingerprint.equals(identity.fingerprint())) throw new IllegalArgumentException("Event ID reused with different content");
            if (existing.done.isCompletedExceptionally()) {
                if (work.replace(key, existing, proposed)) break;
                continue;
            }
            if (existing.owner == Thread.currentThread().threadId() && !existing.done.isDone()) {
                throw new EventDeliveryException(EventDeliveryException.Reason.BUSY);
            }
            try {
                existing.done.join();
            } catch (CompletionException failed) {
                if (failed.getCause() instanceof RuntimeException cause) throw cause;
                if (failed.getCause() instanceof Error cause) throw cause;
                throw failed;
            }
            return;
        }
        try {
            delegate.publish(event);
            proposed.done.complete(null);
        } catch (RuntimeException | Error failed) {
            proposed.done.completeExceptionally(failed);
            throw failed;
        }
    }

    private record Key(String tenant, String key) {}
    private record Work(String fingerprint, long owner, CompletableFuture<Void> done) {}
}
