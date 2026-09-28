package org.openfoundry.foundation.spi;

import org.openfoundry.foundation.spi.schema.PropertyValues;
import java.util.*;

/** User-editable saved-query definition. Identity, ownership and timestamps are server-owned. */
public record ObjectSetSpec(String name, String description, String objectType, Map<String, Object> filter,
                            List<Sort> orderBy, Integer limit, Map<String, Object> aggregation, boolean isPublic) {
    public ObjectSetSpec {
        requireText(name, "name");
        requireText(objectType, "objectType");
        filter = PropertyValues.immutableMap(Objects.requireNonNull(filter));
        orderBy = List.copyOf(orderBy);
        if (orderBy.stream().map(Sort::field).distinct().count() != orderBy.size()) throw new IllegalArgumentException("Duplicate ObjectSet sort field");
        if (limit != null && limit < 0) throw new IllegalArgumentException("ObjectSet limit must not be negative");
        aggregation = aggregation == null ? null : PropertyValues.immutableMap(aggregation);
    }

    public record Sort(String field, String direction) {
        public Sort {
            requireText(field, "sort field");
            if (direction == null || !Set.of("ASC", "DESC").contains(direction.toUpperCase(Locale.ROOT))) throw new IllegalArgumentException("Invalid ObjectSet sort direction");
            direction = direction.toUpperCase(Locale.ROOT);
        }
    }

    public static ObjectSetSpec fromMap(Map<String, Object> input) {
        if (input == null || !Set.of("name", "description", "objectType", "filter", "orderBy", "limit", "aggregation", "isPublic").containsAll(input.keySet())) {
            throw new IllegalArgumentException("Unknown or server-owned ObjectSet field");
        }
        Object rawOrder = input.get("orderBy");
        if (rawOrder != null && !(rawOrder instanceof List<?>)) throw new IllegalArgumentException("ObjectSet orderBy must be a list");
        List<Sort> order = rawOrder == null ? List.of() : ((List<?>) rawOrder).stream().map(value -> {
            Map<String, Object> item = object(value);
            if (!item.keySet().equals(Set.of("field", "direction"))) throw new IllegalArgumentException("Invalid ObjectSet sort fields");
            return new Sort(text(item.get("field")), text(item.get("direction")));
        }).toList();
        Object publicValue = input.get("isPublic") == null ? false : input.get("isPublic");
        if (!(publicValue instanceof Boolean)) throw new IllegalArgumentException("isPublic must be a boolean");
        return new ObjectSetSpec(text(input.get("name")), input.get("description") == null ? null : text(input.get("description")),
                text(input.get("objectType")), input.get("filter") == null ? Map.of() : object(input.get("filter")), order,
                integer(input.get("limit")), input.get("aggregation") == null ? null : object(input.get("aggregation")), (Boolean) publicValue);
    }

    public ObjectSetSpec patched(Map<String, Object> patch) {
        if (patch == null || !Set.of("name", "description", "filter", "orderBy", "limit", "aggregation", "isPublic").containsAll(patch.keySet())) {
            throw new IllegalArgumentException("ObjectSet identity, owner, type and timestamps cannot be updated");
        }
        if (patch.containsKey("isPublic") && patch.get("isPublic") == null) throw new IllegalArgumentException("isPublic cannot be cleared");
        var values = new LinkedHashMap<>(toMap());
        values.putAll(patch);
        return fromMap(values);
    }

    public Map<String, Object> toMap() {
        var result = new LinkedHashMap<String, Object>();
        result.put("name", name);
        result.put("description", description);
        result.put("objectType", objectType);
        result.put("filter", filter);
        result.put("orderBy", orderBy.stream().map(sort -> Map.of("field", sort.field(), "direction", sort.direction().toLowerCase(Locale.ROOT))).toList());
        result.put("limit", limit);
        result.put("aggregation", aggregation);
        result.put("isPublic", isPublic);
        return Collections.unmodifiableMap(result);
    }

    private static Integer integer(Object value) {
        if (value == null) return null;
        if (!(value instanceof Integer number)) throw new IllegalArgumentException("ObjectSet limit must be an integer");
        return number;
    }
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("ObjectSet query value must be an object");
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> result.put(text(key), item));
        return result;
    }
    private static String text(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("ObjectSet field requires text");
        return text;
    }
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 255) throw new IllegalArgumentException("Invalid ObjectSet " + field);
    }
}
