package org.openfoundry.foundation.sync;

import java.util.Map;

public record MappingConfig(String objectType, String primaryKeyField,
                            Map<String, String> sourceToTarget) {
    public MappingConfig {
        if (objectType == null || objectType.isBlank()) throw new IllegalArgumentException("objectType must not be blank");
        if (primaryKeyField == null || primaryKeyField.isBlank()) throw new IllegalArgumentException("primaryKeyField must not be blank");
        sourceToTarget = Map.copyOf(sourceToTarget);
    }
}
