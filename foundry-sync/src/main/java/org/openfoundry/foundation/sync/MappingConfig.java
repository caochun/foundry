package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.LineageValues;

import java.util.*;

/** Immutable mapping declarations. Compiled functions live in RecordMapper, never in durable configuration identity. */
public record MappingConfig(String objectType, KeyMapping primaryKey, Map<String, PropertyMapping> properties, List<LinkMapping> links) {
    public MappingConfig {
        if (objectType == null || objectType.isBlank()) throw new IllegalArgumentException("objectType must not be blank");
        Objects.requireNonNull(primaryKey);
        properties = Map.copyOf(properties);
        links = List.copyOf(links);
        if (properties.keySet().stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("Mapping target is empty");
        if (links.stream().map(LinkMapping::name).distinct().count() != links.size()) throw new IllegalArgumentException("Relationship mapping names must be unique");
    }

    public MappingConfig(String objectType, String primaryKeyField, Map<String, String> sourceToTarget) {
        this(objectType, new KeyMapping(primaryKeyField, null, null), legacyProperties(sourceToTarget), List.of());
    }

    public String primaryKeyField() { return primaryKey.source(); }

    /** Legacy one-to-one rename view. Rich mappings may reuse a source and are exposed through properties(). */
    public Map<String, String> sourceToTarget() {
        var result = new LinkedHashMap<String, String>();
        properties.forEach((target, field) -> {
            if (result.putIfAbsent(field.source(), target) != null) throw new IllegalStateException("This mapping has multiple targets for one source; use properties()");
        });
        return Map.copyOf(result);
    }

    public boolean legacyCompatible() {
        return primaryKey.target() == null && primaryKey.transform() == null && links.isEmpty()
                && properties.values().stream().allMatch(field -> field.transform() == null)
                && properties.values().stream().map(PropertyMapping::source).distinct().count() == properties.size();
    }

    public Map<String, Object> definition() {
        if (legacyCompatible()) return Map.of("type", objectType, "primary", primaryKey.source(), "fields", sourceToTarget());
        var fields = new LinkedHashMap<String, Object>();
        properties.forEach((name, mapping) -> fields.put(name, mapping.definition()));
        return Map.of("format", "mapping-v2", "type", objectType, "primary", primaryKey.definition(), "properties", fields,
                "links", links.stream().map(LinkMapping::definition).toList());
    }

    public String fingerprint() { return LineageValues.hash(true, definition()); }

    private static Map<String, PropertyMapping> legacyProperties(Map<String, String> renames) {
        var fields = new LinkedHashMap<String, PropertyMapping>();
        renames.forEach((source, target) -> {
            if (target == null || target.isBlank() || fields.putIfAbsent(target, new PropertyMapping(source)) != null) {
                throw new IllegalArgumentException("Mapping targets must be unique and nonblank");
            }
        });
        return fields;
    }
}
