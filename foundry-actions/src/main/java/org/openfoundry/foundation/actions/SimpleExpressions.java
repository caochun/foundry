package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.ObjectRecord;

import java.util.Map;

final class SimpleExpressions {
    private SimpleExpressions() {}

    static boolean evaluate(String expression, Map<String, Object> parameters, ActionActor actor) {
        String value = expression.trim();
        int or = value.indexOf("||");
        if (or >= 0) return evaluate(value.substring(0, or), parameters, actor)
                || evaluate(value.substring(or + 2), parameters, actor);
        int and = value.indexOf("&&");
        if (and >= 0) return evaluate(value.substring(0, and), parameters, actor)
                && evaluate(value.substring(and + 2), parameters, actor);
        if (value.equals("true")) return true;
        if (value.equals("false")) return false;
        if (value.startsWith("actor.hasRole(") && value.endsWith(")")) {
            String role = unquote(value.substring("actor.hasRole(".length(), value.length() - 1).trim());
            return actor.roles().contains(role);
        }
        String operator = value.contains("!=") ? "!=" : value.contains("==") ? "==" : null;
        if (operator == null) throw new IllegalArgumentException("unsupported expression: " + expression);
        String[] parts = value.split(java.util.regex.Pattern.quote(operator), 2);
        Object left = resolve(parts[0].trim(), parameters);
        Object right = unquote(parts[1].trim());
        boolean equal = String.valueOf(left).equals(String.valueOf(right));
        return operator.equals("==") ? equal : !equal;
    }

    private static Object resolve(String reference, Map<String, Object> parameters) {
        String[] parts = reference.split("\\.");
        Object current = parameters.get(parts[0]);
        for (int i = 1; i < parts.length && current != null; i++) {
            if (current instanceof ObjectRecord object && parts[i].equals("id")) current = object.id();
            else if (current instanceof ObjectRecord object) current = object.properties().get(parts[i]);
            else current = null;
        }
        return current;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\"")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
