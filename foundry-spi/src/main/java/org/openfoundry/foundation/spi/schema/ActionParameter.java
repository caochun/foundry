package org.openfoundry.foundation.spi.schema;

public record ActionParameter(String name, String type, boolean required) {
    public ActionParameter {
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*") || name.startsWith("__")) {
            throw new IllegalArgumentException("Invalid Action parameter name");
        }
        if (!validType(type)) throw new IllegalArgumentException("Invalid Action parameter type");
    }

    public String baseType() {
        return type.replace("[", "").replace("]", "").replace("!", "");
    }

    private static boolean validType(String type) {
        if (type == null || type.isEmpty()) return false;
        int index = 0, depth = 0;
        while (index < type.length() && type.charAt(index) == '[') { index++; depth++; }
        var name = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*").matcher(type).region(index, type.length());
        if (!name.lookingAt()) return false;
        index = name.end();
        if (depth > 0 && index < type.length() && type.charAt(index) == '!') index++;
        while (depth > 0) {
            if (index >= type.length() || type.charAt(index++) != ']') return false;
            depth--;
            if (depth > 0 && index < type.length() && type.charAt(index) == '!') index++;
        }
        return index == type.length();
    }
}
