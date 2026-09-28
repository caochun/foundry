package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.schema.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Validates resolved values against trusted schema metadata; object snapshots are supplied only by the application boundary. */
public final class ActionParameterValidator {
    private static final OntologySchema SCALARS = new OntologySchema("parameters", "1", List.of(), List.of(), List.of());
    private final OntologySchema schema;
    private final Set<String> objects;

    public ActionParameterValidator() { this(null); }

    public ActionParameterValidator(OntologySchema schema) {
        this.schema = schema == null ? SCALARS : schema;
        objects = schema == null ? null : schema.objectTypes().stream().map(ObjectTypeDefinition::name).collect(Collectors.toSet());
    }

    public List<String> validate(ActionTypeDefinition definition, Map<String, Object> parameters) {
        var errors = new ArrayList<String>();
        Set<String> known = definition.parameters().stream().map(ActionParameter::name).collect(Collectors.toSet());
        for (String name : parameters.keySet()) if (!known.contains(name)) errors.add("unknown Action parameter: " + name);
        for (var parameter : definition.parameters()) {
            if (Set.of("actor", "params", "now").contains(parameter.name())) errors.add("reserved Action parameter: " + parameter.name());
            String base = parameter.baseType();
            if (objects != null && !this.schema.isScalar(base) && !schema.enums().containsKey(base) && !objects.contains(base)) {
                errors.add("unknown Action parameter type: " + parameter.name());
                continue;
            }
            Object value = parameters.get(parameter.name());
            if (value == null) {
                if (parameter.required()) errors.add("missing required Action parameter: " + parameter.name());
            } else if (!matches(parameter.type(), value, parameter.name())) {
                errors.add("invalid Action parameter type: " + parameter.name());
            }
        }
        return List.copyOf(errors);
    }

    private boolean matches(String type, Object value, String name) {
        if (type.endsWith("!")) return value != null && matches(type.substring(0, type.length() - 1), value, name);
        if (value == null) return true;
        if (type.startsWith("[") && type.endsWith("]")) {
            String element = type.substring(1, type.length() - 1);
            return value instanceof List<?> list && list.stream().allMatch(item -> matches(element, item, name));
        }
        if (schema.isScalar(type) || schema.enums().containsKey(type)) {
            try {
                PropertyValues.immutableValue(value); // Public parameter values must have a JSON wire representation.
                PropertyValues.normalize(schema, type, value, name);
                return true;
            } catch (IllegalArgumentException invalid) { return false; }
        }
        return (objects == null || objects.contains(type)) && value instanceof ObjectRecord record
                && record.type().equals(type) && !record.isDeleted();
    }
}
