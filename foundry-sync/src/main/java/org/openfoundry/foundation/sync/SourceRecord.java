package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.Provenance;

import java.time.Instant;
import java.util.Map;

public record SourceRecord(String sourceSystem, String sourceRecordId,
                           String operation, Instant observedAt,
                           Map<String, Object> data, Provenance provenance, SourcePosition position) {
    public SourceRecord(String sourceSystem, String sourceRecordId, String operation, Instant observedAt,
                        Map<String, Object> data, Provenance provenance) {
        this(sourceSystem, sourceRecordId, operation, observedAt, data, provenance, null);
    }

    public SourceRecord {
        if (sourceSystem == null || sourceSystem.isBlank()) throw new IllegalArgumentException("sourceSystem must not be blank");
        if (sourceRecordId == null || sourceRecordId.isBlank()) throw new IllegalArgumentException("sourceRecordId must not be blank");
        if (operation == null || operation.isBlank()) throw new IllegalArgumentException("operation must not be blank");
        if (observedAt == null) throw new IllegalArgumentException("observedAt must not be null");
        operation = operation.toUpperCase(java.util.Locale.ROOT);
        if (!java.util.Set.of("UPSERT", "INSERT", "UPDATE", "DELETE").contains(operation)) throw new IllegalArgumentException("Unknown source operation");
        if (provenance != null && (!sourceSystem.equals(provenance.sourceSystem()) || !sourceRecordId.equals(provenance.sourceRecordId()))) {
            throw new IllegalArgumentException("Source record and provenance identities disagree");
        }
        data = org.openfoundry.foundation.spi.schema.PropertyValues.immutableMap(data);
    }
}
