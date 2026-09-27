package org.openfoundry.foundation.events;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

public record OutboxEvent(String id, String tenantId, String type, String subject,
                          Instant occurredAt, String transactionId,
                          Map<String, Object> data, Instant publishedAt) {
    public OutboxEvent {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        if (type == null || type.isBlank()) throw new IllegalArgumentException("type must not be blank");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        data = org.openfoundry.foundation.spi.schema.PropertyValues.immutableMap(data);
    }
}
