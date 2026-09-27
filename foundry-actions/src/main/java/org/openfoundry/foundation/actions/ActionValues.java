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

    Map<String, Object> properties(Map<String, String> expressions) {
        var values = new LinkedHashMap<String, Object>();
        expressions.forEach((name, expression) -> values.put(name, value(expression)));
        return Collections.unmodifiableMap(values);
    }

    private Object reference(String expression) {
        if (expression.startsWith("'") && expression.endsWith("'") && expression.length() >= 2) {
            return expression.substring(1, expression.length() - 1);
        }
        String[] parts = expression.split("\\.", -1);
        if (!roots.containsKey(parts[0])) return expression;
        Object current = roots.get(parts[0]);
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
