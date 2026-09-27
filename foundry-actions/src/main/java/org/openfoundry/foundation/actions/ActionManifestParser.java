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
        rejectUnknown(root, java.util.Set.of("action", "version", "reversible", "preconditions", "effects", "rollback", "sideEffects"));
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
                case "deleteLink" -> java.util.Set.of("type", "linkType", "linkId", "filter", "expect");
                default -> throw new ActionParseException("unsupported effect type: " + type);
            });
            effects.add(switch (type) {
                case "updateObject" -> new ActionManifest.UpdateObject(string(value, "target"), stringMap(value.get("set")));
                case "createObject" -> new ActionManifest.CreateObject(string(value, "objectType"), value.containsKey("target") ? string(value, "target") : null, stringMap(value.get("properties")));
                case "createLink" -> new ActionManifest.CreateLink(string(value, "linkType"), string(value, "from"), string(value, "to"), stringMap(value.get("properties")));
                case "deleteLink" -> deleteLink(value);
                default -> throw new ActionParseException("unsupported effect type: " + type);
            });
        }
        var policy = ActionManifest.RollbackPolicy.LOG_AND_CONTINUE;
        if (root.containsKey("rollback")) {
            var rollback = map(root.get("rollback"), "rollback must be a mapping");
            rejectUnknown(rollback, java.util.Set.of("onSideEffectFailure"));
            try {
                policy = ActionManifest.RollbackPolicy.valueOf(string(rollback, "onSideEffectFailure"));
            } catch (IllegalArgumentException invalid) {
                throw new ActionParseException("Unknown side-effect failure policy");
            }
        }
        var sideEffects = new ArrayList<ActionManifest.SideEffect>();
        for (Object item : list(root.get("sideEffects"))) {
            var effect = map(item, "side-effect must be a mapping");
            rejectUnknown(effect, java.util.Set.of("name", "type", "config", "retries", "retryDelay"));
            int retries = effect.containsKey("retries") ? integer(effect, "retries") : 3;
            String type = string(effect, "type");
            var config = map(effect.get("config"), "side-effect config must be a mapping");
            rejectUnknown(config, switch (type) {
                case "event" -> java.util.Set.of("type", "source", "subject", "data");
                case "webhook" -> java.util.Set.of("url", "method", "body", "headers", "timeoutMs");
                default -> throw new ActionParseException("Unsupported side-effect type: " + type);
            });
            string(config, type.equals("event") ? "type" : "url");
            if (type.equals("event") && config.get("data") != null && !(config.get("data") instanceof Map<?, ?>)) {
                throw new ActionParseException("Event data must be an object");
            }
            try {
                sideEffects.add(new ActionManifest.SideEffect(string(effect, "name"), type, config, retries,
                        effect.containsKey("retryDelay") ? java.time.Duration.parse(string(effect, "retryDelay")) : java.time.Duration.ofMillis(200)));
            } catch (IllegalArgumentException invalid) {
                throw new ActionParseException("Invalid side-effect declaration: " + invalid.getMessage());
            }
        }
        return new ActionManifest(action, version, reversible, preconditions, effects, policy, sideEffects);
    }

    private static ActionManifest.DeleteLink deleteLink(Map<String, Object> value) {
        boolean direct = value.containsKey("linkId");
        if (direct == value.containsKey("filter")) {
            throw new ActionParseException("deleteLink requires exactly one linkId or filter");
        }
        var expectation = ActionManifest.LinkExpectation.ONE;
        if (value.containsKey("expect")) {
            try {
                expectation = ActionManifest.LinkExpectation.valueOf(string(value, "expect"));
            } catch (IllegalArgumentException invalid) {
                throw new ActionParseException("deleteLink expect must be ONE or ALL");
            }
        }
        if (direct) {
            if (expectation != ActionManifest.LinkExpectation.ONE) throw new ActionParseException("linkId requires expect ONE");
            return new ActionManifest.DeleteLink(string(value, "linkType"), string(value, "linkId"));
        }
        var filter = map(value.get("filter"), "deleteLink filter must be a mapping");
        rejectUnknown(filter, java.util.Set.of("from", "to", "active"));
        if (filter.containsKey("active") && !(filter.get("active") instanceof Boolean)) {
            throw new ActionParseException("deleteLink active must be a boolean");
        }
        return new ActionManifest.DeleteLink(string(value, "linkType"), new ActionManifest.LinkFilter(
                filter.containsKey("from") ? string(filter, "from") : null,
                filter.containsKey("to") ? string(filter, "to") : null, (Boolean) filter.get("active")), expectation);
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
        if (!(value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger)) {
            throw new ActionParseException("missing integer field: " + key);
        }
        try {
            return new java.math.BigInteger(value.toString()).intValueExact();
        } catch (ArithmeticException invalid) {
            throw new ActionParseException("integer field out of range: " + key);
        }
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
