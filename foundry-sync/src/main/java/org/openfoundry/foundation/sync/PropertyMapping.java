package org.openfoundry.foundation.sync;

import java.util.LinkedHashMap;
import java.util.Map;

public record PropertyMapping(String source, String transform) {
    public PropertyMapping {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("Mapping source is required");
        if (transform != null) {
            transform = transform.trim();
            if (transform.isEmpty()) throw new IllegalArgumentException("Transform expression is empty");
        }
    }
    public PropertyMapping(String source) { this(source, null); }
    public Map<String, Object> definition() {
        var result = new LinkedHashMap<String, Object>();
        result.put("source", source);
        if (transform != null) result.put("transform", transform);
        return Map.copyOf(result);
    }
}
