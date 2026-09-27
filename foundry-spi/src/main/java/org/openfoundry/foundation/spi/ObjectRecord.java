package org.openfoundry.foundation.spi;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable current-state representation of an ontology object. */
public record ObjectRecord(
        String tenantId,
        String type,
        String id,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Instant deletedAt,
        String lastTransactionId,
        String lastActionId,
        Map<String, Object> properties) {

    public ObjectRecord {
        requireText(tenantId, "tenantId");
        requireText(type, "type");
        requireText(id, "id");
        if (version < 1) {
            throw new IllegalArgumentException("version must be at least 1");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        properties = immutableProperties(properties);
    }

    public EntityKey key() {
        return new EntityKey(type, id);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static Map<String, Object> immutableProperties(Map<String, Object> properties) {
        return org.openfoundry.foundation.spi.schema.PropertyValues.immutableMap(Objects.requireNonNull(properties, "properties must not be null"));
    }
}
