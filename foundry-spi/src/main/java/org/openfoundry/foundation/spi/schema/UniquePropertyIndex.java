package org.openfoundry.foundation.spi.schema;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Transaction-local current-value index; changes enter the index only after a successful write. */
public final class UniquePropertyIndex {
    private final Map<String, Map<String, Map<String, Set<String>>>> types = new HashMap<>();

    public void check(OntologySchema schema, String kind, String type, List<PropertyDefinition> definitions, String id,
                      Map<String, Object> candidate, Supplier<Map<String, Map<String, Object>>> current) {
        var unique = definitions.stream().filter(property -> property.unique() && !property.primary()).toList();
        if (unique.stream().noneMatch(property -> candidate.get(property.name()) != null)) return;
        String key = kind + ":" + type;
        var index = types.get(key);
        if (index == null) {
            index = new HashMap<>();
            for (var property : unique) index.put(property.name(), new HashMap<>());
            for (var row : current.get().entrySet()) {
                for (var property : unique) {
                    Object value = row.getValue().get(property.name());
                    if (value == null) continue;
                    try {
                        String encoded = PropertyValues.uniqueKey(schema, property, value);
                        var owners = index.get(property.name()).computeIfAbsent(encoded, ignored -> new HashSet<>());
                        owners.add(row.getKey());
                    } catch (PropertyValidationException invalidLegacyValue) {
                        // Invalid old values are not coerced into a valid key. Updating such a row still
                        // requires validating its complete merged state, and must explicitly repair the value.
                    }
                }
            }
            types.put(key, index);
        }
        for (var property : unique) {
            var values = index.get(property.name());
            Object value = candidate.get(property.name());
            if (value == null) continue;
            var owners = values.getOrDefault(PropertyValues.uniqueKey(schema, property, value), Set.of());
            if (owners.stream().anyMatch(owner -> !owner.equals(id))) {
                throw new PropertyValidationException(owners.size() > 1 ? "LEGACY_UNIQUE_CONFLICT" : "UNIQUE_PROPERTY", property.name());
            }
        }
    }

    public void applied(OntologySchema schema, String kind, String type, List<PropertyDefinition> definitions, String id,
                        Map<String, Object> previous, Map<String, Object> next) {
        var index = types.get(kind + ":" + type);
        if (index == null) return;
        for (var property : definitions) {
            if (!property.unique() || property.primary()) continue;
            var values = index.get(property.name());
            if (previous != null && previous.get(property.name()) != null) {
                try {
                    String oldKey = PropertyValues.uniqueKey(schema, property, previous.get(property.name()));
                    var owners = values.get(oldKey);
                    if (owners != null) {
                        owners.remove(id);
                        if (owners.isEmpty()) values.remove(oldKey);
                    }
                } catch (PropertyValidationException invalidLegacyValue) {
                    // Invalid old values were not indexed; never remove another entity's ownership.
                }
            }
            if (next != null && next.get(property.name()) != null) {
                String key = PropertyValues.uniqueKey(schema, property, next.get(property.name()));
                values.computeIfAbsent(key, ignored -> new HashSet<>()).add(id);
            }
        }
    }
}
