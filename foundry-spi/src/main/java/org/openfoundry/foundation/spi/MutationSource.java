package org.openfoundry.foundation.spi;

import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Trusted origin of a transaction's fact writes; never inferred from an actor name or client payload. */
public record MutationSource(Kind kind, String name, String operationId, Instant producedAt, Map<String, Object> details) {
    public enum Kind { DIRECT, ACTION, SYNC, FUNCTION }

    public MutationSource {
        Objects.requireNonNull(kind);
        if (name == null || name.isBlank() || name.length() > 255) throw new IllegalArgumentException("Invalid source name");
        if (operationId == null || operationId.isBlank() || operationId.length() > (kind == Kind.ACTION ? 255 : 512)) throw new IllegalArgumentException("Invalid source operation identity");
        Objects.requireNonNull(producedAt);
        details = PropertyValues.immutableMap(details);
    }

    public static MutationSource action(String name, String id, Instant at, boolean compensation) {
        return new MutationSource(Kind.ACTION, name, id, at, Map.of("phase", compensation ? "COMPENSATION" : "EXECUTION"));
    }

    public static MutationSource direct(String transactionId, Instant at) {
        return new MutationSource(Kind.DIRECT, "direct", transactionId, at, Map.of());
    }

    public Map<String, Object> toMap() {
        return Map.of("kind", kind.name(), "name", name, "operationId", operationId, "producedAt", producedAt.toString(), "details", details);
    }

    public static MutationSource fromMap(Map<String, Object> source) {
        if (!source.keySet().equals(java.util.Set.of("kind", "name", "operationId", "producedAt", "details"))) throw new IllegalStateException("Invalid mutation source fields");
        @SuppressWarnings("unchecked") var details = (Map<String, Object>) source.get("details");
        return new MutationSource(Kind.valueOf((String) source.get("kind")), (String) source.get("name"), (String) source.get("operationId"),
                Instant.parse((String) source.get("producedAt")), details);
    }

    public String actionId() { return kind == Kind.ACTION ? operationId : null; }
    public String sourceSystem() { return kind == Kind.SYNC ? name : null; }
}
