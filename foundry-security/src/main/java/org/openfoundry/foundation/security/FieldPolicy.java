package org.openfoundry.foundation.security;

import java.util.Map;
import java.util.Set;

public record FieldPolicy(Set<String> alwaysVisible, Map<String, Set<String>> fieldsByRole) {
    public FieldPolicy {
        alwaysVisible = Set.copyOf(alwaysVisible);
        fieldsByRole = fieldsByRole.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }
}
