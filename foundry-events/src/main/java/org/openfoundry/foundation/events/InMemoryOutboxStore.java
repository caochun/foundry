package org.openfoundry.foundation.events;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class InMemoryOutboxStore implements OutboxStore {
    private final List<OutboxEvent> events = new ArrayList<>();

    @Override
    public synchronized void append(OutboxEvent event) {
        if (events.stream().anyMatch(existing -> existing.id().equals(event.id()))) {
            throw new IllegalArgumentException("duplicate outbox id: " + event.id());
        }
        events.add(event);
    }

    @Override
    public synchronized List<OutboxEvent> pending(String tenantId, int limit) {
        return events.stream().filter(event -> event.tenantId().equals(tenantId))
                .filter(event -> event.publishedAt() == null).limit(limit).toList();
    }

    @Override
    public synchronized void markPublished(String eventId, Instant publishedAt) {
        for (int i = 0; i < events.size(); i++) {
            OutboxEvent event = events.get(i);
            if (event.id().equals(eventId)) {
                events.set(i, new OutboxEvent(event.id(), event.tenantId(), event.type(), event.subject(),
                        event.occurredAt(), event.transactionId(), event.data(), publishedAt));
                return;
            }
        }
        throw new IllegalArgumentException("outbox event not found: " + eventId);
    }
}
