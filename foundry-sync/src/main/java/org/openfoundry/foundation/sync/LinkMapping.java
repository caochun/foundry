package org.openfoundry.foundation.sync;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record LinkMapping(String name, String linkType, String toType, KeyMapping toKey, Map<String, PropertyMapping> properties) {
    public LinkMapping {
        if (name == null || name.isBlank() || linkType == null || linkType.isBlank() || toType == null || toType.isBlank()) {
            throw new IllegalArgumentException("Relationship mapping names and types are required");
        }
        Objects.requireNonNull(toKey);
        properties = Map.copyOf(properties);
        if (properties.keySet().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Relationship property target is empty");
    }
    public LinkMapping(String linkType, String toType, KeyMapping toKey, Map<String, PropertyMapping> properties) {
        this(linkType, linkType, toType, toKey, properties);
    }
    public Map<String, Object> definition() {
        var fields = new LinkedHashMap<String, Object>();
        properties.forEach((name, mapping) -> fields.put(name, mapping.definition()));
        return Map.of("name", name, "linkType", linkType, "toType", toType, "toKey", toKey.definition(), "properties", fields);
    }
}
