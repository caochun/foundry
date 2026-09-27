package org.openfoundry.foundation.events;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** At-least-once delivery with fenced acknowledgement and bounded exponential retry delay. */
public final class OutboxDispatcher {
    private final OutboxStore store;
    private final EventSink sink;
    private final String source;
    private final Clock clock;
    private final Duration lease;
    private final Duration retryDelay;

    public OutboxDispatcher(OutboxStore store, EventSink sink, String source) {
        this(store, sink, source, Clock.systemUTC(), Duration.ofMinutes(1), Duration.ofSeconds(1));
    }

    public OutboxDispatcher(OutboxStore store, EventSink sink, String source, Clock clock, Duration lease, Duration retryDelay) {
        this.store = Objects.requireNonNull(store);
        this.sink = Objects.requireNonNull(sink);
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Event source is required");
        this.source = source;
        this.clock = Objects.requireNonNull(clock);
        DeliveryTimes.deadline(clock.instant(), lease);
        if (retryDelay == null || retryDelay.compareTo(Duration.ofMillis(1)) < 0
                || retryDelay.compareTo(Duration.ofMinutes(5)) > 0) throw new IllegalArgumentException("Invalid retry delay");
        this.lease = lease;
        this.retryDelay = retryDelay;
    }

    public DispatchResult dispatch(String tenantId, int limit) {
        int published = 0;
        int failed = 0;
        for (var claim : store.claim(tenantId, limit, clock.instant(), lease)) {
            try {
                // An earlier callback may have exhausted a later entry's batch lease.
                if (!store.renew(claim, clock.instant(), lease)) {
                    failed++;
                    continue;
                }
                var event = claim.event();
                sink.publish(new CloudEvent("1.0", event.id(), source, event.type(), event.subject(), event.occurredAt(),
                        event.tenantId(), event.transactionId(), event.data()));
                if (store.complete(claim, clock.instant())) published++;
                else failed++;
            } catch (RuntimeException failure) {
                failed++;
                var now = clock.instant();
                Duration delay = retryDelay.multipliedBy(1L << Math.min(claim.attempt() - 1, 10));
                if (delay.compareTo(Duration.ofMinutes(5)) > 0) delay = Duration.ofMinutes(5);
                // If this write also fails, the lease still expires and makes the event recoverable.
                try { store.fail(claim, now, now.plus(delay)); }
                catch (RuntimeException schedulingFailure) { failure.addSuppressed(schedulingFailure); }
            }
        }
        return new DispatchResult(published, failed);
    }

    public record DispatchResult(int published, int failed) {}
}
