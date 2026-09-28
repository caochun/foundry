package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Compiles and validates every branch before reading data or evaluating any predicate. */
final class ObjectQueryPlan {
    private final OntologySchema schema;
    private final Map<String, PropertyDefinition> fields = new LinkedHashMap<>();
    private final Set<String> visible;
    private int nodes;

    ObjectQueryPlan(OntologySchema schema, List<PropertyDefinition> properties, Set<String> visible) {
        this.schema = schema;
        properties.forEach(field -> fields.put(field.name(), field));
        this.visible = visible;
    }

    static List<String> operators(String type, OntologySchema schema) {
        if (type.startsWith("[") || Set.of("JSON", "GeoPoint").contains(type)) return List.of();
        if (type.equals("Boolean")) return List.of("eq", "ne", "exists");
        if (type.equals("ID") || schema.enums().containsKey(type)) return List.of("eq", "ne", "in", "exists");
        if (Set.of("Int", "Float", "Date", "DateTime", "Duration").contains(type)) {
            return List.of("eq", "ne", "in", "gt", "gte", "lt", "lte", "exists");
        }
        return List.of("eq", "ne", "in", "contains", "startsWith", "exists");
    }

    static boolean orderable(String type, OntologySchema schema) {
        return !type.equals("Boolean") && !operators(type, schema).isEmpty();
    }

    Predicate<ObjectRecord> predicate(Map<String, Object> filter) {
        return predicate(filter, 0);
    }

    private Predicate<ObjectRecord> predicate(Map<String, Object> filter, int depth) {
        if (depth > 32 || ++nodes > 1000) throw new IllegalArgumentException("Query filter is too complex");
        var predicates = new ArrayList<Predicate<ObjectRecord>>();
        for (var entry : filter.entrySet()) {
            String name = entry.getKey();
            Object raw = entry.getValue();
            if (Set.of("AND", "OR").contains(name)) {
                if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("Logical filter requires a list");
                var children = list.stream().map(child -> predicate(map(child), depth + 1)).toList();
                predicates.add(name.equals("AND") ? row -> children.stream().allMatch(p -> p.test(row))
                        : row -> children.stream().anyMatch(p -> p.test(row)));
            } else if (name.equals("NOT")) {
                predicates.add(predicate(map(raw), depth + 1).negate());
            } else {
                var field = field(name);
                for (var operation : map(raw).entrySet()) {
                    if (++nodes > 1000) throw new IllegalArgumentException("Query filter is too complex");
                    if (!operators(field.type(), schema).contains(operation.getKey())) {
                        throw new IllegalArgumentException("Unsupported query operator: " + operation.getKey());
                    }
                    predicates.add(comparison(field, operation.getKey(), operation.getValue()));
                }
            }
        }
        return row -> predicates.stream().allMatch(p -> p.test(row));
    }

    Comparator<ObjectRecord> comparator(Map<String, String> orderBy) {
        Comparator<ObjectRecord> comparator = (left, right) -> 0;
        for (var entry : orderBy.entrySet()) {
            var field = field(entry.getKey());
            if (!orderable(field.type(), schema) || (entry.getValue() == null || !Set.of("ASC", "DESC").contains(entry.getValue()))) {
                throw new IllegalArgumentException("Invalid query ordering");
            }
            boolean descending = entry.getValue().equals("DESC");
            comparator = comparator.thenComparing((left, right) -> {
                Object a = value(left, field), b = value(right, field);
                if (a == null || b == null) return a == b ? 0 : a == null ? 1 : -1;
                int compared = compare(field.type(), a, b);
                return descending ? -compared : compared;
            });
        }
        return comparator.thenComparing(ObjectRecord::id);
    }

    private PropertyDefinition field(String name) {
        var field = name.equals("_id") ? fields.values().stream().filter(PropertyDefinition::primary).findFirst().orElse(null) : fields.get(name);
        if (field == null || operators(field.type(), schema).isEmpty()) throw new IllegalArgumentException("Unsupported query field: " + name);
        // Primary keys are already exposed independently of field masks by every entity read.
        if (!field.primary() && !visible.contains(name)) throw new SecurityException("Query field is not visible");
        return field;
    }

    private Predicate<ObjectRecord> comparison(PropertyDefinition field, String operator, Object raw) {
        if (operator.equals("exists")) {
            if (!(raw instanceof Boolean exists)) throw new IllegalArgumentException("exists requires a boolean");
            return row -> (value(row, field) != null) == exists;
        }
        if (operator.equals("in")) {
            if (!(raw instanceof List<?> list) || list.size() > 1000) throw new IllegalArgumentException("in requires at most 1000 values");
            var expected = list.stream().map(item -> normalize(field, nonNullOperand(item))).toList();
            return row -> expected.stream().anyMatch(item -> equal(field.type(), value(row, field), item));
        }
        if (raw == null && !Set.of("eq", "ne").contains(operator)) throw new IllegalArgumentException("Query operand must not be null");
        Object expected = raw == null ? null : normalize(field, raw);
        return row -> {
            Object actual = value(row, field);
            return switch (operator) {
                case "eq" -> equal(field.type(), actual, expected);
                case "ne" -> !equal(field.type(), actual, expected);
                case "contains" -> actual != null && ((String) actual).contains((String) expected);
                case "startsWith" -> actual != null && ((String) actual).startsWith((String) expected);
                case "gt" -> actual != null && compare(field.type(), actual, expected) > 0;
                case "gte" -> actual != null && compare(field.type(), actual, expected) >= 0;
                case "lt" -> actual != null && compare(field.type(), actual, expected) < 0;
                case "lte" -> actual != null && compare(field.type(), actual, expected) <= 0;
                default -> throw new IllegalStateException("Uncompiled query operator");
            };
        };
    }

    private static Object nonNullOperand(Object value) {
        if (value == null) throw new IllegalArgumentException("in cannot contain null");
        return value;
    }

    private Object normalize(PropertyDefinition field, Object value) {
        return PropertyValues.normalize(schema, field.type(), value, field.name());
    }

    private static Object value(ObjectRecord object, PropertyDefinition field) {
        return field.primary() ? object.id() : object.properties().get(field.name());
    }

    private static boolean equal(String type, Object left, Object right) {
        return left == null || right == null ? left == right : compare(type, left, right) == 0;
    }

    static int compare(String type, Object left, Object right) {
        return switch (type) {
            case "Int", "Float" -> new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
            case "Date" -> java.time.LocalDate.parse((String) left).compareTo(java.time.LocalDate.parse((String) right));
            case "DateTime" -> Instant.parse((String) left).compareTo(Instant.parse((String) right));
            case "Duration" -> Duration.parse((String) left).compareTo(Duration.parse((String) right));
            case "Boolean" -> Boolean.compare((Boolean) left, (Boolean) right);
            default -> ((String) left).compareTo((String) right);
        };
    }

    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Query filter requires an object");
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> {
            if (!(key instanceof String name)) throw new IllegalArgumentException("Invalid filter key");
            result.put(name, item);
        });
        return result;
    }
}
