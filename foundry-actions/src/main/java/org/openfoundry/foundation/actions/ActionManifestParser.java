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
        String action = string(root, "action");
        int version = integer(root, "version");
        boolean reversible = root.getOrDefault("reversible", Boolean.FALSE) instanceof Boolean value && value;

        List<ActionManifest.Precondition> preconditions = new ArrayList<>();
        for (Object item : list(root.get("preconditions"))) {
            Map<String, Object> value = map(item, "precondition must be a mapping");
            preconditions.add(new ActionManifest.Precondition(string(value, "expr"), string(value, "error")));
        }

        List<ActionManifest.ActionEffect> effects = new ArrayList<>();
        for (Object item : list(root.get("effects"))) {
            Map<String, Object> value = map(item, "effect must be a mapping");
            String type = string(value, "type");
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
        raw.forEach((key, item) -> result.put(key, String.valueOf(item)));
        return result;
    }
}
