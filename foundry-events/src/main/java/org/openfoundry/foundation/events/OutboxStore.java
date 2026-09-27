package org.openfoundry.foundation.events;

import java.time.Instant;
import java.util.List;

public interface OutboxStore {
    void append(OutboxEvent event);

    List<OutboxEvent> pending(String tenantId, int limit);

    /** Legacy administrative completion, rejected once an event has entered leased delivery. */
    @Deprecated
    void markPublished(String eventId, Instant publishedAt);

    default List<OutboxClaim> claim(String tenantId, int limit, Instant now, java.time.Duration lease) {
        throw new UnsupportedOperationException("Leased outbox delivery is not implemented");
    }

    default boolean renew(OutboxClaim claim, Instant now, java.time.Duration lease) {
        throw new UnsupportedOperationException("Leased outbox delivery is not implemented");
    }

    default boolean complete(OutboxClaim claim, Instant now) {
        throw new UnsupportedOperationException("Leased outbox delivery is not implemented");
    }

    default boolean fail(OutboxClaim claim, Instant now, Instant retryAt) {
        throw new UnsupportedOperationException("Leased outbox delivery is not implemented");
    }
}
