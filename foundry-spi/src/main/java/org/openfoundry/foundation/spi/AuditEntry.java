package org.openfoundry.foundation.spi;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Transactional audit payload kept independent from the events module. */
public record AuditEntry(String id, Instant timestamp, String tenantId, String actorId,
                         String operationType, String objectType, String objectId,
                         String actionType, String transactionId, String result,
                         Map<String, Object> detail) {
    public AuditEntry {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        if (operationType == null || operationType.isBlank()) throw new IllegalArgumentException("operationType must not be blank");
        if (result == null || result.isBlank()) throw new IllegalArgumentException("result must not be blank");
        detail = Map.copyOf(detail);
    }
}
