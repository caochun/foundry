package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Aggregate pagination applies to result groups, never to the input objects. */
public record AggregateQuery(List<Field> fields, List<String> groupBy, Map<String, Object> filter,
                             List<Order> orderBy, int limit, int offset, Instant asOfValidTime,
                             Instant asOfRecordedTime, boolean includeDeleted) {
    public AggregateQuery(List<Field> fields, List<String> groupBy, Map<String, Object> filter, List<Order> orderBy) {
        this(fields, groupBy, filter, orderBy, Integer.MAX_VALUE, 0, null, null, false);
    }

    public AggregateQuery {
        if (fields == null || fields.isEmpty() || fields.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Aggregate fields must not be empty");
        }
        fields = List.copyOf(fields);
        groupBy = strings(groupBy);
        if (orderBy == null || orderBy.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("Invalid aggregate ordering");
        orderBy = List.copyOf(orderBy);
        if (filter == null) throw new IllegalArgumentException("Aggregate filter must not be null");
        filter = PropertyValues.immutableMap(filter);
        if (limit < 0 || offset < 0) throw new IllegalArgumentException("Aggregate limit and offset must not be negative");
        if ((asOfValidTime == null) != (asOfRecordedTime == null)) throw new IllegalArgumentException("Temporal aggregates require both times");
    }

    public QueryOptions sourceView() {
        return new QueryOptions(Integer.MAX_VALUE, 0, asOfValidTime, asOfRecordedTime, includeDeleted);
    }

    public enum Function { COUNT, SUM, AVG, MIN, MAX }
    public enum Direction { ASC, DESC }

    public record Field(String field, Function fn, String alias) {
        public Field {
            if (field == null || field.isBlank() || fn == null) throw new IllegalArgumentException("Invalid aggregate field");
            if (alias != null && !alias.matches("[A-Za-z][A-Za-z0-9_]{0,127}")) throw new IllegalArgumentException("Invalid aggregate alias");
            if (field.equals("*") && fn != Function.COUNT) throw new IllegalArgumentException("Only COUNT accepts *");
        }
        public Field(String field, Function fn) { this(field, fn, null); }
        public String resultName() { return alias == null ? fn.name().toLowerCase(Locale.ROOT) + "_" + field : alias; }
    }

    public record Order(String field, Direction direction) {
        public Order {
            if (field == null || field.isBlank() || direction == null) throw new IllegalArgumentException("Invalid aggregate order");
        }
    }

    public static AggregateQuery fromJson(Map<String, Object> input) {
        if (input == null || !Set.of("fields", "groupBy", "filter", "orderBy", "limit", "offset", "asOfValidTime", "asOfRecordedTime", "includeDeleted").containsAll(input.keySet())) {
            throw new IllegalArgumentException("Unknown aggregate option");
        }
        if (!(input.get("fields") instanceof List<?> rawFields)) throw new IllegalArgumentException("Aggregate fields must be a list");
        var fields = rawFields.stream().map(value -> {
            var field = object(value);
            if (!Set.of("field", "fn", "alias").containsAll(field.keySet())) throw new IllegalArgumentException("Unknown aggregate field option");
            return new Field(text(field.get("field")), Function.valueOf(text(field.get("fn")).toUpperCase(Locale.ROOT)),
                    field.get("alias") == null ? null : text(field.get("alias")));
        }).toList();
        List<String> groupBy = input.get("groupBy") == null ? List.of() : stringList(input.get("groupBy"));
        Object rawOrder = input.get("orderBy");
        if (rawOrder != null && !(rawOrder instanceof List<?>)) throw new IllegalArgumentException("Aggregate orderBy must be a list");
        var order = rawOrder == null ? List.<Order>of() : ((List<?>) rawOrder).stream().map(value -> {
            var item = object(value);
            if (!item.keySet().equals(Set.of("field", "direction"))) throw new IllegalArgumentException("Invalid aggregate order options");
            return new Order(text(item.get("field")), Direction.valueOf(text(item.get("direction")).toUpperCase(Locale.ROOT)));
        }).toList();
        Object deleted = input.getOrDefault("includeDeleted", false);
        if (!(deleted instanceof Boolean)) throw new IllegalArgumentException("includeDeleted requires a boolean");
        return new AggregateQuery(fields, groupBy, input.get("filter") == null ? Map.of() : object(input.get("filter")), order,
                integer(input.get("limit"), Integer.MAX_VALUE), integer(input.get("offset"), 0),
                instant(input.get("asOfValidTime")), instant(input.get("asOfRecordedTime")), (Boolean) deleted);
    }

    private static List<String> strings(List<String> values) {
        if (values == null || values.stream().anyMatch(value -> value == null || value.isBlank())) throw new IllegalArgumentException("Invalid group field");
        if (Set.copyOf(values).size() != values.size()) throw new IllegalArgumentException("Duplicate group field");
        return List.copyOf(values);
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Group fields must be a list");
        return list.stream().map(AggregateQuery::text).toList();
    }

    private static String text(Object value) {
        if (!(value instanceof String text)) throw new IllegalArgumentException("Aggregate option requires text");
        return text;
    }

    private static int integer(Object value, int fallback) {
        if (value == null) return fallback;
        if (!(value instanceof Integer number)) throw new IllegalArgumentException("Aggregate page size and offset must be integers");
        return number;
    }

    private static Instant instant(Object value) {
        if (value == null) return null;
        try { return Instant.parse(text(value)); }
        catch (java.time.format.DateTimeParseException invalid) { throw new IllegalArgumentException("Invalid aggregate time", invalid); }
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Aggregate option requires an object");
        var result = new java.util.LinkedHashMap<String, Object>();
        map.forEach((key, item) -> result.put(text(key), item));
        return result;
    }
}
