package org.openfoundry.foundation.spi;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable historical state of an object or relationship. */
public record HistorySnapshot(
        EntityKey key,
        long version,
        EntityOperation operation,
        Instant validFrom,
        Instant validTo,
        Instant recordedAt,
        String transactionId,
        String actionId,
        String actorId,
        String sourceSystem,
        Map<String, Object> state) {

    public HistorySnapshot {
        Objects.requireNonNull(key, "key must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be at least 1");
        }
        Objects.requireNonNull(operation, "operation must not be null");
        Objects.requireNonNull(validFrom, "validFrom must not be null");
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        state = org.openfoundry.foundation.spi.schema.PropertyValues.immutableMap(Objects.requireNonNull(state, "state must not be null"));
    }
}
