package org.openfoundry.foundation.spi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Transactional outbox payload kept independent from the events module. */
public record OutboxEntry(String id, String tenantId, String type, String subject,
                          Instant occurredAt, String transactionId,
                          Map<String, Object> data) {
    public OutboxEntry {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        data = Map.copyOf(data);
    }
}
