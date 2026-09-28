package org.openfoundry.foundation.spi;

import java.time.Instant;
import java.util.Objects;

/** Append-only evidence, ordered by entity-local sequence, without retaining a second copy of sensitive values. */
public record FieldProvenance(String tenantId, EntityKey entity, String field, long sequence, long entityVersion,
                              boolean valuePresent, String valueHash, Instant recordedAt, String transactionId,
                              String actorId, MutationSource source) {
    public FieldProvenance {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("Tenant is required");
        Objects.requireNonNull(entity);
        if (field == null || !field.equals("_entity") && !field.matches("[A-Za-z][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid provenance field");
        if (sequence < 1 || entityVersion < 0 || entityVersion == 0 && (!field.equals("_entity") || valuePresent)) {
            throw new IllegalArgumentException("Invalid provenance version");
        }
        if (entity.id().length() > 512 || entity.type().length() > 255 || tenantId.length() > 255 || field.length() > 512) {
            throw new IllegalArgumentException("Provenance identity exceeds storage limits");
        }
        if (valueHash == null || !valueHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid provenance value digest");
        Objects.requireNonNull(recordedAt);
        Objects.requireNonNull(transactionId);
        Objects.requireNonNull(source);
    }
}
