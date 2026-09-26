package org.openfoundry.foundation.spi;

import java.time.Instant;

public record Provenance(String sourceSystem, String sourceRecordId,
                         String sourceVersion, String transformation,
                         Instant producedAt, String producedBy, String valueHash) {
    public Provenance {
        if (sourceSystem == null || sourceSystem.isBlank()) throw new IllegalArgumentException("sourceSystem must not be blank");
        if (sourceRecordId == null || sourceRecordId.isBlank()) throw new IllegalArgumentException("sourceRecordId must not be blank");
        if (producedAt == null) throw new IllegalArgumentException("producedAt must not be null");
    }
}
