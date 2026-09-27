package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.schema.ActionParameter;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates resolved parameters. Object IDs must be resolved by the authenticated application boundary first. */
public final class ActionParameterValidator {
    public List<String> validate(ActionTypeDefinition definition, Map<String, Object> parameters) {
        List<String> errors = new ArrayList<>();
        Set<String> known = definition.parameters().stream().map(ActionParameter::name).collect(Collectors.toSet());
        for (String name : parameters.keySet()) {
            if (!known.contains(name)) errors.add("unknown Action parameter: " + name);
        }
        for (ActionParameter parameter : definition.parameters()) {
            if (Set.of("actor", "params", "now").contains(parameter.name())) errors.add("reserved Action parameter: " + parameter.name());
            Object value = parameters.get(parameter.name());
            if (value == null) {
                if (parameter.required()) errors.add("missing required Action parameter: " + parameter.name());
            } else if (!matches(parameter.type(), value)) {
                errors.add("invalid Action parameter type: " + parameter.name());
            }
        }
        return List.copyOf(errors);
    }

    private boolean matches(String type, Object value) {
        if (type.startsWith("[") && type.endsWith("]")) {
            String element = type.substring(1, type.length() - 1);
            boolean required = element.endsWith("!");
            String base = required ? element.substring(0, element.length() - 1) : element;
            return value instanceof List<?> list && list.stream().allMatch(item -> item == null ? !required : matches(base, item));
        }
        return switch (type) {
            case "ID", "String" -> value instanceof String;
            case "Int" -> (value instanceof Integer || value instanceof Long)
                    && ((Number) value).longValue() >= Integer.MIN_VALUE && ((Number) value).longValue() <= Integer.MAX_VALUE;
            case "Float" -> value instanceof Number number && Double.isFinite(number.doubleValue());
            case "Boolean" -> value instanceof Boolean;
            case "Date" -> isDate(value);
            case "DateTime" -> isInstant(value);
            case "JSON" -> json(value);
            default -> value instanceof ObjectRecord record && record.type().equals(type) && !record.isDeleted();
        };
    }

    private boolean json(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return true;
        if (value instanceof Number number) return Double.isFinite(number.doubleValue());
        if (value instanceof List<?> list) return list.stream().allMatch(this::json);
        if (value instanceof Map<?, ?> map) return map.keySet().stream().allMatch(String.class::isInstance) && map.values().stream().allMatch(this::json);
        return false;
    }

    private boolean isDate(Object value) {
        try { LocalDate.parse((String) value); return true; }
        catch (RuntimeException invalid) { return false; }
    }

    private boolean isInstant(Object value) {
        try { Instant.parse((String) value); return true; }
        catch (RuntimeException invalid) { return false; }
    }
}
