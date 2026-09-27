package org.openfoundry.foundation.spi;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable current-state representation of a first-class relationship. */
public record LinkRecord(
        String tenantId,
        String type,
        String id,
        EntityKey from,
        EntityKey to,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        Instant validFrom,
        Instant validTo,
        String lastTransactionId,
        String lastActionId,
        Map<String, Object> properties) {

    public LinkRecord {
        requireText(tenantId, "tenantId");
        requireText(type, "type");
        requireText(id, "id");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("version must be at least 1");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        Objects.requireNonNull(validFrom, "validFrom must not be null");
        if (validTo != null && validTo.isBefore(validFrom)) {
            throw new IllegalArgumentException("validTo must not be before validFrom");
        }
        properties = org.openfoundry.foundation.spi.schema.PropertyValues.immutableMap(Objects.requireNonNull(properties, "properties must not be null"));
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isValidAt(Instant instant) {
        Objects.requireNonNull(instant, "instant must not be null");
        return !instant.isBefore(validFrom) && (validTo == null || instant.isBefore(validTo));
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
