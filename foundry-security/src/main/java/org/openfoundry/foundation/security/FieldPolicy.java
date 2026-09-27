package org.openfoundry.foundation.security;

import java.util.Map;
import java.util.Set;

public record FieldPolicy(Set<String> alwaysVisible, Map<String, Set<String>> fieldsByRole, boolean storedFieldsOnly) {
    public FieldPolicy(Set<String> alwaysVisible, Map<String, Set<String>> fieldsByRole) {
        this(alwaysVisible, fieldsByRole, false);
    }

    public static FieldPolicy storedFields(Set<String> alwaysVisible, Map<String, Set<String>> fieldsByRole) {
        return new FieldPolicy(alwaysVisible, fieldsByRole, true);
    }

    public FieldPolicy {
        alwaysVisible = Set.copyOf(alwaysVisible);
        fieldsByRole = fieldsByRole.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }
}
