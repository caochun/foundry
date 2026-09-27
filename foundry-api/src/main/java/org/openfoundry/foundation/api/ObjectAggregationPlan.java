package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evaluates only server-authorized rows; every referenced property is bound before execution. */
final class ObjectAggregationPlan {
    private final AggregateQuery query;
    private final Map<String, PropertyDefinition> properties = new LinkedHashMap<>();
    private final Set<String> visible;
    private final List<PropertyDefinition> groupFields;
    private final List<Measure> measures;
    private final Comparator<AggregateResult.Group> comparator;

    ObjectAggregationPlan(List<PropertyDefinition> definitions, Set<String> visible, AggregateQuery query) {
        definitions.forEach(field -> properties.put(field.name(), field));
        this.visible = visible;
        this.query = query;
        this.groupFields = query.groupBy().stream().map(this::field).toList();
        var resultNames = new java.util.HashSet<String>();
        this.measures = query.fields().stream().map(aggregate -> {
            PropertyDefinition property = aggregate.field().equals("*") ? null : field(aggregate.field());
            if (aggregate.fn() != AggregateQuery.Function.COUNT && !Set.of("Int", "Float").contains(property.type())) {
                throw new IllegalArgumentException("Numeric aggregate requires an Int or Float property");
            }
            if (!resultNames.add(aggregate.resultName()) || query.groupBy().contains(aggregate.resultName())) {
                throw new IllegalArgumentException("Ambiguous or duplicate aggregate result name");
            }
            return new Measure(aggregate, property);
        }).toList();
        Comparator<AggregateResult.Group> ordering = (left, right) -> 0;
        var ordered = new java.util.HashSet<String>();
        for (var order : query.orderBy()) {
            if (!ordered.add(order.field()) || (!resultNames.contains(order.field()) && !query.groupBy().contains(order.field()))) {
                throw new IllegalArgumentException("Ordering must name a unique group field or aggregate alias");
            }
            PropertyDefinition property = query.groupBy().contains(order.field()) ? properties.get(order.field()) : null;
            ordering = ordering.thenComparing((left, right) -> {
                Object a = property == null ? left.values().get(order.field()) : left.keys().get(order.field());
                Object b = property == null ? right.values().get(order.field()) : right.keys().get(order.field());
                return compare(property, a, b, order.direction());
            });
        }
        // A deterministic group-key tie-break keeps equal measures stable across providers/pages.
        for (var field : groupFields) {
            ordering = ordering.thenComparing((left, right) -> compare(field, left.keys().get(field.name()), right.keys().get(field.name()), AggregateQuery.Direction.ASC));
        }
        this.comparator = ordering;
    }

    AggregateResult evaluate(List<ObjectRecord> rows) {
        var groups = new LinkedHashMap<String, Accumulator>();
        if (groupFields.isEmpty()) groups.put(PropertyValues.canonical(Map.of()), new Accumulator(Map.of()));
        for (var object : rows) {
            var keys = new LinkedHashMap<String, Object>();
            for (var field : groupFields) keys.put(field.name(), value(object, field));
            groups.computeIfAbsent(PropertyValues.canonical(keys), ignored -> new Accumulator(keys)).add(object);
        }
        var results = groups.values().stream().map(Accumulator::finish).sorted(comparator)
                .skip(query.offset()).limit(query.limit()).toList();
        return new AggregateResult(results, groups.size());
    }

    private PropertyDefinition field(String name) {
        var property = properties.get(name);
        if (property == null) throw new IllegalArgumentException("Unknown aggregate property: " + name);
        if (!property.primary() && !visible.contains(name)) throw new SecurityException("Aggregate field is not visible");
        return property;
    }

    private static Object value(ObjectRecord object, PropertyDefinition field) {
        return field.primary() ? object.id() : object.properties().get(field.name());
    }

    private static int compare(PropertyDefinition field, Object left, Object right, AggregateQuery.Direction direction) {
        if (left == null || right == null) return left == right ? 0 : left == null ? 1 : -1;
        int comparison;
        if (field == null) comparison = new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
        else if (field.type().startsWith("[") || Set.of("JSON", "GeoPoint").contains(field.type())) {
            comparison = PropertyValues.canonical(left).compareTo(PropertyValues.canonical(right));
        } else comparison = ObjectQueryPlan.compare(field.type(), left, right);
        return direction == AggregateQuery.Direction.DESC ? -Integer.signum(comparison) : comparison;
    }

    private record Measure(AggregateQuery.Field aggregate, PropertyDefinition property) {}

    private final class Accumulator {
        private final Map<String, Object> keys;
        private final List<Numeric> values = new ArrayList<>();

        Accumulator(Map<String, Object> keys) {
            this.keys = keys;
            measures.forEach(ignored -> values.add(new Numeric()));
        }

        void add(ObjectRecord object) {
            for (int index = 0; index < measures.size(); index++) {
                var measure = measures.get(index);
                var value = measure.property() == null ? Boolean.TRUE : value(object, measure.property());
                if (value == null) continue;
                var state = values.get(index);
                state.count++;
                if (measure.aggregate().fn() == AggregateQuery.Function.COUNT) continue;
                var number = new BigDecimal(value.toString());
                state.sum = state.sum.add(number);
                state.min = state.min == null || number.compareTo(state.min) < 0 ? number : state.min;
                state.max = state.max == null || number.compareTo(state.max) > 0 ? number : state.max;
            }
        }

        AggregateResult.Group finish() {
            var result = new LinkedHashMap<String, Number>();
            for (int index = 0; index < measures.size(); index++) {
                var aggregate = measures.get(index).aggregate();
                var state = values.get(index);
                Number value;
                if (aggregate.fn() == AggregateQuery.Function.COUNT) value = state.count;
                else if (state.count == 0) value = null;
                else {
                    BigDecimal decimal = switch (aggregate.fn()) {
                        case SUM -> state.sum;
                        case AVG -> state.sum.divide(BigDecimal.valueOf(state.count), MathContext.DECIMAL128);
                        case MIN -> state.min;
                        case MAX -> state.max;
                        default -> throw new IllegalStateException("Unexpected numeric aggregate");
                    };
                    double number = decimal.doubleValue();
                    if (!Double.isFinite(number)) throw new IllegalArgumentException("Aggregate result exceeds finite numeric range");
                    value = number;
                }
                result.put(aggregate.resultName(), value);
            }
            return new AggregateResult.Group(keys, result);
        }
    }

    private static final class Numeric {
        long count;
        BigDecimal sum = BigDecimal.ZERO;
        BigDecimal min;
        BigDecimal max;
    }
}
