package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.EntityKey;

import java.util.List;
import java.util.Map;

/** Server-recorded mutation kind and identity, used to recheck authority without inventing permissions for new resources. */
public record ActionEffectAccess(Kind kind, EntityKey entity) {
    public enum Kind { CREATE_OBJECT, UPDATE_OBJECT, CREATE_LINK, DELETE_LINK }

    public ActionEffectAccess {
        java.util.Objects.requireNonNull(kind);
        java.util.Objects.requireNonNull(entity);
    }

    public Map<String, Object> encode() {
        return Map.of("kind", kind.name(), "type", entity.type(), "id", entity.id());
    }

    public static List<ActionEffectAccess> decode(Object value) {
        if (!(value instanceof List<?> rows)) throw new IllegalStateException("Invalid action access record");
        return rows.stream().map(row -> {
            if (!(row instanceof Map<?, ?> fields) || !(fields.get("kind") instanceof String kind)
                    || !(fields.get("type") instanceof String type) || !(fields.get("id") instanceof String id)) {
                throw new IllegalStateException("Invalid action access entry");
            }
            try { return new ActionEffectAccess(Kind.valueOf(kind), new EntityKey(type, id)); }
            catch (IllegalArgumentException invalid) { throw new IllegalStateException("Unknown action access kind", invalid); }
        }).toList();
    }
}
