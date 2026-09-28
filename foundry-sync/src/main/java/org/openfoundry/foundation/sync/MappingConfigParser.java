package org.openfoundry.foundation.sync;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.*;

/** Strict parser for the upstream datasource YAML shape. Transform compilation is an explicit separate step. */
public final class MappingConfigParser {
    public DatasourceMapping parse(String source) {
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        options.setCodePointLimit(1_000_000);
        Object value = new Yaml(new SafeConstructor(options)).load(source);
        return parse(map(value));
    }

    public DatasourceMapping parse(Map<String, Object> raw) {
        acyclic(raw, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 0);
        fields(raw, Set.of("datasource", "connector", "connection", "mapping", "sync"));
        var connection = map(raw.get("connection"));
        var extras = new LinkedHashMap<>(connection);
        extras.remove("url");
        extras.remove("table");
        var mapping = map(raw.get("mapping"));
        fields(mapping, Set.of("objectType", "primaryKey", "properties", "links"));
        var links = new ArrayList<LinkMapping>();
        if (mapping.containsKey("links")) {
            if (!(mapping.get("links") instanceof List<?> values)) throw new IllegalArgumentException("links must be a list");
            for (Object value : values) {
                var link = map(value);
                fields(link, Set.of("name", "linkType", "toType", "toKey", "properties"));
                String type = text(link, "linkType");
                links.add(new LinkMapping(link.containsKey("name") ? text(link, "name") : type, type, text(link, "toType"), key(map(link.get("toKey"))),
                        link.containsKey("properties") ? properties(map(link.get("properties"))) : Map.of()));
            }
        }
        var sync = map(raw.get("sync"));
        fields(sync, Set.of("mode", "interval", "conflictResolution", "rateLimit", "cacheStrategy", "cacheTTL", "writeback"));
        Integer rate = null;
        if (sync.containsKey("rateLimit")) {
            var limit = map(sync.get("rateLimit"));
            fields(limit, Set.of("maxRecordsPerSecond"));
            Object value = limit.get("maxRecordsPerSecond");
            if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException("Rate limit must be an integer");
            rate = Math.toIntExact(((Number) value).longValue());
        }
        Boolean writeback = null;
        if (sync.containsKey("writeback")) {
            if (!(sync.get("writeback") instanceof Boolean flag)) throw new IllegalArgumentException("writeback must be boolean");
            writeback = flag;
        }
        var definition = new DatasourceMapping(text(raw, "datasource"), text(raw, "connector"),
                new DatasourceMapping.Connection(text(connection, "url"), text(connection, "table"), extras),
                new MappingConfig(text(mapping, "objectType"), key(map(mapping.get("primaryKey"))), properties(map(mapping.get("properties"))), links),
                new DatasourceMapping.Sync(DatasourceMapping.Mode.valueOf(text(sync, "mode")), optional(sync, "interval"),
                        sync.containsKey("conflictResolution") ? ConflictResolver.Strategy.valueOf(text(sync, "conflictResolution")) : null,
                        rate, optional(sync, "cacheStrategy"), optional(sync, "cacheTTL"), writeback));
        MappingTransforms.validate(definition.mapping().primaryKey().transform());
        definition.mapping().properties().values().forEach(field -> MappingTransforms.validate(field.transform()));
        definition.mapping().links().forEach(link -> {
            MappingTransforms.validate(link.toKey().transform());
            link.properties().values().forEach(field -> MappingTransforms.validate(field.transform()));
        });
        return definition;
    }

    private static void acyclic(Object value, Set<Object> path, int depth) {
        if (!(value instanceof Map<?, ?> || value instanceof List<?>)) return;
        if (depth > 64 || !path.add(value)) throw new IllegalArgumentException("Recursive or excessively nested mapping data");
        if (value instanceof Map<?, ?> map) map.values().forEach(child -> acyclic(child, path, depth + 1));
        else for (Object child : (List<?>) value) acyclic(child, path, depth + 1);
        path.remove(value);
    }

    private static KeyMapping key(Map<String, Object> raw) {
        fields(raw, Set.of("source", "target", "transform"));
        return new KeyMapping(text(raw, "source"), text(raw, "target"), optional(raw, "transform"));
    }
    private static Map<String, PropertyMapping> properties(Map<String, Object> raw) {
        var properties = new LinkedHashMap<String, PropertyMapping>();
        raw.forEach((target, value) -> {
            var field = map(value);
            fields(field, Set.of("source", "transform"));
            properties.put(target, new PropertyMapping(text(field, "source"), optional(field, "transform")));
        });
        return properties;
    }
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Mapping section must be an object");
        var result = new LinkedHashMap<String, Object>();
        raw.forEach((key, item) -> {
            if (!(key instanceof String name)) throw new IllegalArgumentException("Mapping keys must be strings");
            result.put(name, item);
        });
        return result;
    }
    private static void fields(Map<String, Object> value, Set<String> allowed) {
        if (!allowed.containsAll(value.keySet())) throw new IllegalArgumentException("Unknown mapping option");
    }
    private static String text(Map<String, Object> values, String key) {
        if (!(values.get(key) instanceof String value) || value.isBlank()) throw new IllegalArgumentException("Missing string mapping field: " + key);
        return value;
    }
    private static String optional(Map<String, Object> values, String key) {
        return !values.containsKey(key) || values.get(key) == null ? null : text(values, key);
    }
}
