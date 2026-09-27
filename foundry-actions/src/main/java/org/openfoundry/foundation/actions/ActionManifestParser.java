package org.openfoundry.foundation.actions;

import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Strict parser for the portable Action YAML subset. */
public final class ActionManifestParser {
    public ActionManifest parse(String yaml) {
        Object loaded;
        try {
            loaded = new Yaml().load(yaml);
        } catch (RuntimeException exception) {
            throw new ActionParseException("invalid Action YAML: " + exception.getMessage());
        }
        Map<String, Object> root = map(loaded, "Action manifest must be a mapping");
        rejectUnknown(root, java.util.Set.of("action", "version", "reversible", "preconditions", "effects"));
        String action = string(root, "action");
        int version = integer(root, "version");
        boolean reversible = root.getOrDefault("reversible", Boolean.FALSE) instanceof Boolean value && value;

        if (reversible) throw new ActionParseException("Reversible Actions are not supported yet");

        List<ActionManifest.Precondition> preconditions = new ArrayList<>();
        for (Object item : list(root.get("preconditions"))) {
            Map<String, Object> value = map(item, "precondition must be a mapping");
            rejectUnknown(value, java.util.Set.of("expr", "error"));
            preconditions.add(new ActionManifest.Precondition(string(value, "expr"), string(value, "error")));
        }

        List<ActionManifest.ActionEffect> effects = new ArrayList<>();
        for (Object item : list(root.get("effects"))) {
            Map<String, Object> value = map(item, "effect must be a mapping");
            String type = string(value, "type");
            rejectUnknown(value, switch (type) {
                case "updateObject" -> java.util.Set.of("type", "target", "set");
                case "createObject" -> java.util.Set.of("type", "objectType", "target", "properties");
                case "createLink" -> java.util.Set.of("type", "linkType", "from", "to", "properties");
                case "deleteLink" -> java.util.Set.of("type", "linkType", "linkId");
                default -> throw new ActionParseException("unsupported effect type: " + type);
            });
            effects.add(switch (type) {
                case "updateObject" -> new ActionManifest.UpdateObject(string(value, "target"), stringMap(value.get("set")));
                case "createObject" -> new ActionManifest.CreateObject(string(value, "objectType"), string(value, "target"), stringMap(value.get("properties")));
                case "createLink" -> new ActionManifest.CreateLink(string(value, "linkType"), string(value, "from"), string(value, "to"), stringMap(value.get("properties")));
                case "deleteLink" -> new ActionManifest.DeleteLink(string(value, "linkType"), string(value, "linkId"));
                default -> throw new ActionParseException("unsupported effect type: " + type);
            });
        }
        return new ActionManifest(action, version, reversible, preconditions, effects);
    }

    private static void rejectUnknown(Map<String, Object> input, java.util.Set<String> allowed) {
        for (String field : input.keySet()) {
            if (!allowed.contains(field)) throw new ActionParseException("Unsupported Action field: " + field);
        }
    }

    private static Map<String, Object> map(Object value, String message) {
        if (!(value instanceof Map<?, ?> raw)) throw new ActionParseException(message);
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static List<Object> list(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> raw)) throw new ActionParseException("expected a YAML list");
        return new ArrayList<>(raw);
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) throw new ActionParseException("missing string field: " + key);
        return text;
    }

    private static int integer(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw new ActionParseException("missing integer field: " + key);
        return number.intValue();
    }

    private static Map<String, String> stringMap(Object value) {
        if (value == null) return Map.of();
        Map<String, Object> raw = map(value, "effect properties must be a mapping");
        Map<String, String> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> {
            if (!(item instanceof String expression)) throw new ActionParseException("Effect values must be supported string expressions");
            result.put(key, expression);
        });
        return result;
    }
}
