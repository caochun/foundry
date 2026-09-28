package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Simple upstream effect expressions, resolved against one immutable pre-effect context. */
final class ActionValues {
    private final Map<String, Object> roots;
    private final Map<String, Object> created = new LinkedHashMap<>();

    ActionValues(Map<String, Object> parameters, ActionActor actor, Instant now) {
        var context = new LinkedHashMap<String, Object>(parameters);
        context.put("params", Collections.unmodifiableMap(new LinkedHashMap<>(parameters)));
        context.put("actor", Map.of("id", actor.id(), "roles", actor.roles().stream().sorted().toList()));
        context.put("now", now.toString());
        roots = Collections.unmodifiableMap(context);
    }

    Object value(String expression) {
        Object resolved = reference(expression);
        if (resolved instanceof ObjectRecord object) return object.id();
        if (resolved instanceof EntityKey key) return key.id();
        return resolved;
    }

    ObjectRecord object(String expression) {
        Object resolved = reference(expression);
        if (!(resolved instanceof ObjectRecord object)) {
            throw new IllegalArgumentException("Action target must resolve to an object");
        }
        return object;
    }

    EntityKey entity(String expression) {
        Object resolved = reference(expression);
        if (resolved instanceof ObjectRecord object) return object.key();
        if (resolved instanceof EntityKey key) return key;
        throw new IllegalArgumentException("Action endpoint must resolve to an object");
    }

    Map<String, Object> createdBindings() { return Collections.unmodifiableMap(created); }

    EntityKey consentSubject(String expression, String subjectType, String fallbackType) {
        Object value = reference(expression);
        EntityKey key;
        if (value instanceof ObjectRecord object) key = object.key();
        else if (value instanceof EntityKey entity) key = entity;
        else if (value instanceof String id && !id.isBlank()) {
            String type = subjectType == null ? fallbackType : subjectType;
            if (type == null) throw new IllegalArgumentException("Consent subject type is ambiguous");
            key = new EntityKey(type, id);
        } else throw new IllegalArgumentException("Consent subject did not resolve to an identity");
        if (subjectType != null && !subjectType.equals(key.type())) throw new IllegalArgumentException("Consent subject type mismatch");
        return key;
    }

    Map<String, Object> properties(Map<String, String> expressions) {
        var values = new LinkedHashMap<String, Object>();
        expressions.forEach((name, expression) -> values.put(name, value(expression)));
        return Collections.unmodifiableMap(values);
    }

    void created(ObjectRecord object) {
        String name = Character.toLowerCase(object.type().charAt(0)) + object.type().substring(1);
        if (!roots.containsKey(name)) created.putIfAbsent(name, object);
    }

    Map<String, Object> snapshot() {
        var all = new LinkedHashMap<>(roots);
        all.putAll(created);
        return org.openfoundry.foundation.spi.schema.PropertyValues.immutableMap(jsonMap(all));
    }

    private static Map<String, Object> jsonMap(Map<String, Object> source) {
        var result = new LinkedHashMap<String, Object>();
        source.forEach((name, value) -> result.put(name, jsonValue(value)));
        return result;
    }

    private static Object jsonValue(Object value) {
        if (value instanceof ObjectRecord object) {
            var fields = new LinkedHashMap<>(object.properties());
            fields.put("id", object.id());
            fields.put("_id", object.id());
            fields.put("_type", object.type());
            fields.put("_version", object.version());
            return jsonMap(fields);
        }
        if (value instanceof Map<?, ?> map) {
            var fields = new LinkedHashMap<String, Object>();
            map.forEach((name, item) -> fields.put((String) name, jsonValue(item)));
            return fields;
        }
        if (value instanceof java.util.List<?> list) return list.stream().map(ActionValues::jsonValue).toList();
        return value;
    }

    private Object reference(String expression) {
        if (expression.startsWith("'") && expression.endsWith("'") && expression.length() >= 2) {
            return expression.substring(1, expression.length() - 1);
        }
        String[] parts = expression.split("\\.", -1);
        if (!roots.containsKey(parts[0]) && !created.containsKey(parts[0])) return expression;
        Object current = roots.containsKey(parts[0]) ? roots.get(parts[0]) : created.get(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            String field = parts[i];
            if (current instanceof ObjectRecord object) {
                current = switch (field) {
                    case "id", "_id" -> object.id();
                    case "_type" -> object.type();
                    case "_version" -> object.version();
                    default -> object.properties().get(field);
                };
            } else if (current instanceof Map<?, ?> map) {
                current = map.get(field);
            } else {
                return null;
            }
        }
        return current;
    }
}
