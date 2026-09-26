package org.openfoundry.foundation.sync;

import java.util.Map;

public record SourceQuery(String resource, Map<String, Object> parameters) {
    public SourceQuery {
        if (resource == null || resource.isBlank()) throw new IllegalArgumentException("resource must not be blank");
        parameters = Map.copyOf(parameters);
    }
}
