package org.openfoundry.foundation.spi.schema;

import org.openfoundry.foundation.spi.StorageProvider.Direction;

import java.util.Map;
import java.util.Objects;

/** A virtual field declaration. It is evaluated on read and is never a stored attribute. */
public record ComputedFieldDefinition(String name, String type, boolean required, String function,
                                      Map<String, Object> arguments, Cache cache, String ttl, boolean sensitive) {
    public enum Cache { LAZY, EAGER, TTL }

    public ComputedFieldDefinition {
        if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*") || type == null || type.isBlank()
                || function == null || function.isBlank()) throw new IllegalArgumentException("Invalid computed field declaration");
        arguments = PropertyValues.immutableMap(arguments);
        Objects.requireNonNull(cache, "cache");
    }

    public Direction direction() {
        Object raw = arguments.getOrDefault("direction", "INBOUND");
        if (!(raw instanceof String value)) throw new IllegalArgumentException("Computed direction must be text");
        return Direction.valueOf(value.toUpperCase(java.util.Locale.ROOT));
    }

    public String linkType() {
        if (!(arguments.get("type") instanceof String value) || value.isBlank()) throw new IllegalArgumentException("countLinks requires args.type");
        return value;
    }
}
