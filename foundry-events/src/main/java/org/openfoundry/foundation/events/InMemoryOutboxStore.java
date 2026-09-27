package org.openfoundry.foundation.events;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Process-local outbox with the same ownership and retry semantics as JDBC. */
public final class InMemoryOutboxStore implements OutboxStore {
    private final Map<String, OutboxEvent> events = new LinkedHashMap<>();
    private final Map<String, Delivery> deliveries = new LinkedHashMap<>();

    @Override
    public synchronized void append(OutboxEvent event) {
        if (events.putIfAbsent(event.id(), event) != null) throw new IllegalArgumentException("Duplicate outbox ID");
    }

    @Override
    public synchronized List<OutboxEvent> pending(String tenantId, int limit) {
        DeliveryTimes.selection(tenantId, limit);
        return events.values().stream().filter(event -> event.tenantId().equals(tenantId) && event.publishedAt() == null)
                .sorted(Comparator.comparing(OutboxEvent::occurredAt).thenComparing(OutboxEvent::id)).limit(limit).toList();
    }

    @Override
    @Deprecated
    public synchronized void markPublished(String eventId, Instant publishedAt) {
        if (deliveries.containsKey(eventId)) throw new IllegalStateException("Use a delivery token to acknowledge this event");
        var event = events.get(eventId);
        if (event == null) throw new IllegalArgumentException("Outbox event not found");
        published(event, DeliveryTimes.instant(publishedAt));
    }

    @Override
    public synchronized List<OutboxClaim> claim(String tenantId, int limit, Instant now, Duration lease) {
        DeliveryTimes.selection(tenantId, limit);
        Instant time = DeliveryTimes.instant(now);
        Instant until = DeliveryTimes.deadline(now, lease);
        var result = new ArrayList<OutboxClaim>();
        for (var event : events.values().stream().filter(row -> row.tenantId().equals(tenantId) && row.publishedAt() == null)
                .sorted(Comparator.comparing(OutboxEvent::occurredAt).thenComparing(OutboxEvent::id)).toList()) {
            var previous = deliveries.get(event.id());
            if (previous != null && (previous.until != null && previous.until.isAfter(time)
                    || previous.retryAt != null && previous.retryAt.isAfter(time))) continue;
            var delivery = new Delivery(UUID.randomUUID().toString(), previous == null ? 1 : Math.addExact(previous.attempt, 1), until, null);
            deliveries.put(event.id(), delivery);
            result.add(new OutboxClaim(event, delivery.token, delivery.attempt, until));
            if (result.size() == limit) break;
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized boolean renew(OutboxClaim claim, Instant now, Duration lease) {
        var delivery = owned(claim, now);
        if (delivery == null) return false;
        Instant until = DeliveryTimes.deadline(now, lease);
        if (until.isAfter(delivery.until)) delivery.until = until;
        return true;
    }

    @Override
    public synchronized boolean complete(OutboxClaim claim, Instant now) {
        var delivery = owned(claim, now);
        if (delivery == null) return false;
        published(events.get(claim.event().id()), DeliveryTimes.instant(now));
        delivery.token = null;
        delivery.until = null;
        return true;
    }

    @Override
    public synchronized boolean fail(OutboxClaim claim, Instant now, Instant retryAt) {
        Instant retry = DeliveryTimes.instant(retryAt);
        if (retry.isBefore(DeliveryTimes.instant(now))) throw new IllegalArgumentException("Retry cannot precede failure");
        var delivery = owned(claim, now);
        if (delivery == null) return false;
        delivery.token = null;
        delivery.until = null;
        delivery.retryAt = retry;
        return true;
    }

    private Delivery owned(OutboxClaim claim, Instant now) {
        var event = events.get(claim.event().id());
        var delivery = deliveries.get(claim.event().id());
        return event != null && event.tenantId().equals(claim.event().tenantId()) && event.publishedAt() == null
                && delivery != null && claim.token().equals(delivery.token) && delivery.until != null
                && delivery.until.isAfter(DeliveryTimes.instant(now)) ? delivery : null;
    }

    private void published(OutboxEvent event, Instant at) {
        events.put(event.id(), new OutboxEvent(event.id(), event.tenantId(), event.type(), event.subject(),
                event.occurredAt(), event.transactionId(), event.data(), at));
    }

    private static final class Delivery {
        String token;
        final int attempt;
        Instant until;
        Instant retryAt;

        Delivery(String token, int attempt, Instant until, Instant retryAt) {
            this.token = token;
            this.attempt = attempt;
            this.until = until;
            this.retryAt = retryAt;
        }
    }
}
