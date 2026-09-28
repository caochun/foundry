package org.openfoundry.foundation.sync;

import java.util.LinkedHashMap;
import java.util.Map;

public record KeyMapping(String source, String target, String transform) {
    public KeyMapping {
        new PropertyMapping(source, transform);
        if (target != null && target.isBlank()) throw new IllegalArgumentException("Mapping key target is empty");
        if (transform != null) transform = transform.trim();
    }
    public Map<String, Object> definition() {
        var result = new LinkedHashMap<>(new PropertyMapping(source, transform).definition());
        if (target != null) result.put("target", target);
        return Map.copyOf(result);
    }
}
