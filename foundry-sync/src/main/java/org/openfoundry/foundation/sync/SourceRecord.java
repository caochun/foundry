package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.Provenance;

import java.time.Instant;
import java.util.Map;

public record SourceRecord(String sourceSystem, String sourceRecordId,
                           String operation, Instant observedAt,
                           Map<String, Object> data, Provenance provenance) {
    public SourceRecord {
        if (sourceSystem == null || sourceSystem.isBlank()) throw new IllegalArgumentException("sourceSystem must not be blank");
        if (sourceRecordId == null || sourceRecordId.isBlank()) throw new IllegalArgumentException("sourceRecordId must not be blank");
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("operation must not be blank");
        if (observedAt == null) throw new IllegalArgumentException("observedAt must not be null");
        data = Map.copyOf(data);
    }
}
