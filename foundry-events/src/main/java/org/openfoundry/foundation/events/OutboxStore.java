package org.openfoundry.foundation.events;

import java.time.Instant;
import java.util.List;

public interface OutboxStore {
    void append(OutboxEvent event);

    List<OutboxEvent> pending(String tenantId, int limit);

    void markPublished(String eventId, Instant publishedAt);
}
