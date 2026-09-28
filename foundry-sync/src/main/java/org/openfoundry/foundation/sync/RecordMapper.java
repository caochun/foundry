package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.LineageValues;

import java.util.*;

/** Immutable compiled mapping; the complete source record is available to every declared transform. */
public final class RecordMapper {
    private final MappingConfig config;
    private final Map<String, TransformRegistry.Compiled> expressions;
    private final String fingerprint;

    public RecordMapper(MappingConfig config) { this(config, new TransformRegistry()); }

    public RecordMapper(MappingConfig config, TransformRegistry registry) {
        this.config = Objects.requireNonNull(config);
        var compiled = new LinkedHashMap<String, TransformRegistry.Compiled>();
        compile(config.primaryKey().transform(), registry, compiled);
        config.properties().values().forEach(field -> compile(field.transform(), registry, compiled));
        config.links().forEach(link -> {
            compile(link.toKey().transform(), registry, compiled);
            link.properties().values().forEach(field -> compile(field.transform(), registry, compiled));
        });
        expressions = Map.copyOf(compiled);
        var versions = new TreeMap<String, String>();
        compiled.values().forEach(transform -> versions.putAll(transform.customVersions()));
        fingerprint = versions.isEmpty() ? config.fingerprint() : LineageValues.hash(true, Map.of("mapping", config.definition(), "customVersions", versions));
    }

    public String fingerprint() { return fingerprint; }

    public List<MappedRecord> mapRecords(List<SourceRecord> records) { return records.stream().map(this::map).toList(); }

    public MappedRecord map(SourceRecord source) {
        Object id = evaluate(config.primaryKey().source(), config.primaryKey().transform(), source.data());
        var key = new EntityKey(config.objectType(), canonicalId(id));
        if (source.operation().equals("DELETE")) return new MappedRecord(key, Map.of(), source.provenance(), source.operation());
        var properties = properties(config.properties(), source.data());
        var links = new ArrayList<MappedLink>();
        for (var link : config.links()) {
            boolean supplied = source.data().containsKey(link.toKey().source());
            if (!supplied && link.toKey().transform() == null) continue;
            Object target = evaluate(link.toKey().source(), link.toKey().transform(), source.data());
            if (target == null && !supplied) continue;
            if (target == null && source.data().get(link.toKey().source()) != null) throw new IllegalArgumentException("Relationship transform did not produce a target identity");
            links.add(new MappedLink(link.name(), link.linkType(), link.toType(), target == null ? null : new EntityKey(link.toType(), canonicalId(target)),
                    target == null ? Map.of() : properties(link.properties(), source.data())));
        }
        return new MappedRecord(key, properties, source.provenance(), source.operation(), links);
    }

    private Map<String, Object> properties(Map<String, PropertyMapping> definitions, Map<String, Object> source) {
        var result = new LinkedHashMap<String, Object>();
        definitions.forEach((name, field) -> {
            if (field.transform() != null || source.containsKey(field.source())) result.put(name, evaluate(field.source(), field.transform(), source));
        });
        return result;
    }

    private Object evaluate(String field, String expression, Map<String, Object> record) {
        Object value = record.get(field);
        return expression == null ? value : expressions.get(expression).apply(value, record);
    }

    private static void compile(String expression, TransformRegistry registry, Map<String, TransformRegistry.Compiled> compiled) {
        if (expression != null) compiled.computeIfAbsent(expression, registry::compile);
    }

    static String canonicalId(Object rawId) {
        if (rawId instanceof String text && !text.isBlank()) return text;
        if (rawId instanceof Number number) return new java.math.BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        throw new IllegalArgumentException("Source primary key must be a nonblank string or number");
    }
}
