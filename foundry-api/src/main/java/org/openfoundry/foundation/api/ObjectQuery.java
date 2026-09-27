package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Public query input. Predicates use the generated GraphQL filter shape. */
public record ObjectQuery(Map<String, Object> filter, Map<String, String> orderBy, QueryOptions options) {
    public ObjectQuery {
        filter = PropertyValues.immutableMap(Objects.requireNonNull(filter));
        orderBy = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(orderBy)));
        Objects.requireNonNull(options);
    }

    public static ObjectQuery fromJson(Map<String, Object> input) {
        if (input == null || !java.util.Set.of("filter", "orderBy", "first", "offset", "after", "asOfValidTime", "asOfRecordedTime", "includeDeleted").containsAll(input.keySet())) {
            throw new IllegalArgumentException("Unknown query option");
        }
        Map<String, Object> filter = object(input.get("filter"));
        var order = new LinkedHashMap<String, String>();
        object(input.get("orderBy")).forEach((field, direction) -> {
            if (!(direction instanceof String text)) throw new IllegalArgumentException("Invalid query ordering");
            order.put(field, text);
        });
        int offset = integer(input, "offset", 0);
        if (input.get("after") != null) {
            if (offset != 0 || !(input.get("after") instanceof String)) throw new IllegalArgumentException("Invalid cursor pagination");
            offset = ObjectQueryResult.offsetAfter((String) input.get("after"));
        }
        Object deleted = input.getOrDefault("includeDeleted", false);
        if (!(deleted instanceof Boolean)) throw new IllegalArgumentException("includeDeleted requires a boolean");
        return new ObjectQuery(filter, order, new QueryOptions(integer(input, "first", 100), offset,
                instant(input.get("asOfValidTime")), instant(input.get("asOfRecordedTime")), (Boolean) deleted));
    }

    private static int integer(Map<String, Object> input, String name, int fallback) {
        Object value = input.get(name);
        if (value == null) return fallback;
        if (!(value instanceof Integer)) throw new IllegalArgumentException("Invalid query page size or offset");
        return (Integer) value;
    }

    private static java.time.Instant instant(Object value) {
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException("Query time requires an ISO instant");
        try { return java.time.Instant.parse(text); }
        catch (java.time.format.DateTimeParseException invalid) { throw new IllegalArgumentException("Invalid query time", invalid); }
    }

    private static Map<String, Object> object(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Query requires an object");
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> {
            if (!(key instanceof String field)) throw new IllegalArgumentException("Invalid query field");
            result.put(field, item);
        });
        return result;
    }

    public static ObjectQuery all(QueryOptions options) {
        return new ObjectQuery(Map.of(), Map.of(), options);
    }
}
