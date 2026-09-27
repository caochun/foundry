package org.openfoundry.foundation.validation;

import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.schema.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executes declaration-level semantics before either storage provider mutates state. */
public final class PropertyValidator {
    private static final Set<String> AUDIT_FIELDS = Set.of("createdAt", "updatedAt", "createdBy", "updatedBy");
    private final ConstraintPrograms programs = new ConstraintPrograms();

    public void validateSchema(OntologySchema schema) {
        PropertyValues.requireSchema(schema);
        for (var type : schema.objectTypes()) validateDefinitions(schema, type.properties(), type.constraints());
        for (var type : schema.linkTypes()) validateDefinitions(schema, type.properties(), type.constraints());
        for (var type : schema.interfaces()) validateDefinitions(schema, type.properties(), type.constraints());
    }

    private void validateDefinitions(OntologySchema schema, List<PropertyDefinition> fields, List<String> constraints) {
        for (var field : fields) {
            if (field.primary() && (field.hasDefault() || field.readOnly())) throw new IllegalArgumentException("Primary property cannot have a default or readonly generator: " + field.name());
            if (field.readOnly() && field.immutable()) throw new IllegalArgumentException("readonly and immutable must not be combined: " + field.name());
            if (field.readOnly() && AUDIT_FIELDS.contains(field.name())) {
                String expected = field.name().endsWith("At") ? "DateTime" : "String";
                if (!field.type().equals(expected) || field.hasDefault()) throw new IllegalArgumentException("Invalid audit property definition: " + field.name());
            } else if (field.readOnly() && field.required() && !field.hasDefault()) {
                throw new IllegalArgumentException("Required readonly property has no engine value: " + field.name());
            }
            if (field.hasDefault()) {
                if (field.defaultValue() == null && field.required()) throw new IllegalArgumentException("Required property cannot default to null: " + field.name());
                if (field.defaultValue() != null) PropertyValues.normalize(schema, field.type(), field.defaultValue(), field.name());
            }
            for (String expression : field.constraints()) programs.validate(expression, true);
        }
        for (String expression : constraints) programs.validate(expression, false);
    }

    public Map<String, Object> validate(OntologySchema schema, List<PropertyDefinition> fields, List<String> constraints,
                                       String id, Map<String, Object> supplied, Map<String, Object> previous,
                                       RequestContext context, Instant recordedAt) {
        var patch = new LinkedHashMap<>(supplied);
        for (var field : fields) {
            if (field.readOnly() && supplied.containsKey(field.name())) throw new PropertyValidationException("READONLY_PROPERTY", field.name());
            if (previous == null && !supplied.containsKey(field.name()) && field.hasDefault()) patch.put(field.name(), field.defaultValue());
            if (!field.readOnly()) continue;
            switch (field.name()) {
                case "createdAt" -> { if (previous == null) patch.put(field.name(), recordedAt.toString()); }
                case "createdBy" -> { if (previous == null) patch.put(field.name(), context.actorId()); }
                case "updatedAt" -> patch.put(field.name(), recordedAt.toString());
                case "updatedBy" -> patch.put(field.name(), context.actorId());
                default -> { /* Optional engine fields remain absent; literal defaults initialize known readonly values. */ }
            }
        }
        var merged = PropertyValues.validate(schema, fields, id, patch, previous);
        var view = new LinkedHashMap<>(merged);
        fields.stream().filter(PropertyDefinition::primary).forEach(field -> view.put(field.name(), id));
        // Defaults, managed fields and all dependent fields are present before constraints are evaluated.
        for (var field : fields) {
            if (previous != null && field.immutable()) continue;
            if (!merged.containsKey(field.name()) && !field.primary()) continue;
            Object value = field.primary() ? id : merged.get(field.name());
            for (String expression : field.constraints()) programs.require(expression, true, field.name(), view, value);
        }
        for (String expression : constraints) programs.require(expression, false, "$type", view, null);
        return merged;
    }
}
