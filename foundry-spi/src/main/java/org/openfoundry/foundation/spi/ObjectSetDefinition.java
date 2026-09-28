package org.openfoundry.foundation.spi;

import java.time.Instant;
import java.util.*;

public record ObjectSetDefinition(String id, String tenantId, String createdBy, Instant createdAt,
                                  Instant updatedAt, long version, ObjectSetSpec spec) {
    public ObjectSetDefinition {
        if (id == null || id.isBlank() || tenantId == null || tenantId.isBlank() || createdBy == null || createdBy.isBlank() || version < 1) {
            throw new IllegalArgumentException("Invalid ObjectSet identity or revision");
        }
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(updatedAt);
        Objects.requireNonNull(spec);
        if (updatedAt.isBefore(createdAt)) throw new IllegalArgumentException("ObjectSet update predates creation");
    }

    public boolean visibleTo(RequestContext context) {
        return tenantId.equals(context.tenantId()) && (spec.isPublic() || createdBy.equals(context.actorId()));
    }

    public void requireOwner(RequestContext context, Long expectedVersion) {
        if (!tenantId.equals(context.tenantId())) throw new ObjectSetNotFoundException();
        if (!createdBy.equals(context.actorId())) throw new SecurityException("Only the ObjectSet creator may change it");
        if (expectedVersion != null && expectedVersion != version) throw new ObjectSetConflictException();
    }

    public ObjectSetDefinition updated(ObjectSetSpec next, Instant now) {
        return new ObjectSetDefinition(id, tenantId, createdBy, createdAt, now.isBefore(updatedAt) ? updatedAt : now, Math.addExact(version, 1), next);
    }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<>(spec.toMap());
        result.put("id", id);
        result.put("tenantId", tenantId);
        result.put("createdBy", createdBy);
        result.put("createdAt", createdAt.toString());
        result.put("updatedAt", updatedAt.toString());
        result.put("version", version);
        return Collections.unmodifiableMap(result);
    }
}
