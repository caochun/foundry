package org.openfoundry.foundation.spi;

import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.util.Map;
import java.util.Objects;

/** Tenant-scoped source event receipt, shared by authorized workers rather than tied to one worker actor. */
public record IngestionReceipt(String key, String requestHash, EntityKey target, Map<String, Object> result) {
    public IngestionReceipt {
        requireHash(key);
        requireHash(requestHash);
        Objects.requireNonNull(target);
        result = PropertyValues.immutableMap(result);
    }

    public static void requireHash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid ingestion key or digest");
    }
}
