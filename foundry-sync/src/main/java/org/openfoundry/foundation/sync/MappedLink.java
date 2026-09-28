package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.util.Map;

/** Absent source keys omit the entry; null target is an explicit source clear. */
public record MappedLink(String name, String linkType, String toType, EntityKey target, Map<String, Object> properties) {
    public MappedLink {
        if (name == null || name.isBlank() || linkType == null || linkType.isBlank() || toType == null || toType.isBlank()) throw new IllegalArgumentException("Invalid mapped relationship");
        if (target != null && !target.type().equals(toType)) throw new IllegalArgumentException("Mapped target type mismatch");
        properties = PropertyValues.immutableMap(properties);
    }
    public boolean clear() { return target == null; }
}
