package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned JSON representation; no Java object snapshots are persisted as opaque payloads. */
final class ActionContinuationState {
    private ActionContinuationState() {}

    static Map<String, Object> initial(ActionManifest manifest, ActionTypeDefinition definition, Map<String, Object> parameters,
                                       ActionValues expressions, List<EntityKey> affected, List<Map<String, Object>> journal, Instant now, String transactionId) {
        var state = new LinkedHashMap<String, Object>();
        state.put("format", 1);
        state.put("configuration", ActionFingerprint.hash(List.of(manifest, definition)));
        state.put("parameters", parameters.entrySet().stream().collect(LinkedHashMap::new,
                (map, entry) -> map.put(entry.getKey(), encodeParameter(entry.getValue())), Map::putAll));
        state.put("affected", affected.stream().map(key -> Map.of("type", key.type(), "id", key.id())).toList());
        state.put("journal", journal);
        state.put("occurredAt", now.toString());
        state.put("transactionId", transactionId);
        state.put("cursor", 0);
        state.put("warnings", List.of());
        state.put("tasks", manifest.sideEffects().stream().map(effect -> {
            var task = new LinkedHashMap<String, Object>();
            task.put("name", effect.name());
            task.put("type", effect.type());
            var config = new LinkedHashMap<>(effect.config());
            if (effect.type().equals("event") && config.containsKey("data")) config.put("data", resolve(config.get("data"), expressions));
            if (effect.type().equals("webhook")) {
                config.put("body", config.containsKey("body") ? resolve(config.get("body"), expressions) : expressions.snapshot());
            }
            task.put("config", config);
            task.put("attempts", 0);
            task.put("state", "PENDING");
            return task;
        }).toList());
        return state;
    }

    static Map<String, Object> mutable(ActionExecution execution) {
        var state = new LinkedHashMap<>(execution.state());
        requireFormat(state);
        state.put("tasks", maps(state.get("tasks")).stream().map(LinkedHashMap::new).collect(java.util.stream.Collectors.toCollection(ArrayList::new)));
        state.put("warnings", new ArrayList<>(maps(state.get("warnings"))));
        return state;
    }

    static List<EntityKey> affected(Map<String, Object> state) {
        return maps(state.get("affected")).stream().map(row -> new EntityKey((String) row.get("type"), (String) row.get("id"))).toList();
    }

    static ActionResult result(ActionExecution execution) {
        requireFormat(execution.state());
        if (!java.util.Set.of("PENDING", "RUNNING", "COMPENSATING", "COMPLETED", "COMPLETED_WITH_WARNINGS", "ROLLED_BACK", "COMPENSATION_FAILED")
                .contains(execution.status())) throw new IllegalStateException("Unknown continuation status");
        var errors = maps(execution.state().get("warnings")).stream()
                .map(row -> new ActionResult.Failure((String) row.get("code"), (String) row.get("task"))).toList();
        boolean success = execution.status().equals("COMPLETED") || execution.status().equals("COMPLETED_WITH_WARNINGS");
        return new ActionResult(success, execution.id(), affected(execution.state()), execution.status(), errors);
    }

    static Map<String, Object> parameters(Map<String, Object> state, Transaction transaction) {
        requireFormat(state);
        var result = new LinkedHashMap<String, Object>();
        map(state.get("parameters")).forEach((name, value) -> result.put(name, decodeParameter(map(value), transaction)));
        return Collections.unmodifiableMap(result);
    }

    static Map<String, Object> undo(String kind, EntityKey key, long version, Map<String, Object> before) {
        return Map.of("kind", kind, "type", key.type(), "id", key.id(), "version", version, "before", before);
    }

    private static Map<String, Object> encodeParameter(Object value) {
        if (value instanceof ObjectRecord object) return Map.of("kind", "object", "type", object.type(), "id", object.id());
        if (value instanceof List<?> list) return Map.of("kind", "list", "items", list.stream().map(ActionContinuationState::encodeParameter).toList());
        var literal = new LinkedHashMap<String, Object>();
        literal.put("kind", "literal");
        literal.put("value", value);
        return literal;
    }

    private static Object decodeParameter(Map<String, Object> value, Transaction transaction) {
        return switch ((String) value.get("kind")) {
            case "object" -> {
                var object = transaction.getObject((String) value.get("type"), (String) value.get("id"));
                if (object == null) throw new SecurityException("Continuation object reference is unavailable");
                yield object;
            }
            case "list" -> maps(value.get("items")).stream().map(item -> decodeParameter(item, transaction)).toList();
            case "literal" -> value.get("value");
            default -> throw new IllegalStateException("Unknown continuation parameter encoding");
        };
    }

    private static Object resolve(Object value, ActionValues expressions) {
        if (value instanceof String expression) return expressions.value(expression);
        if (value instanceof Map<?, ?> fields) {
            var result = new LinkedHashMap<String, Object>();
            fields.forEach((name, item) -> result.put((String) name, resolve(item, expressions)));
            return result;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> resolve(item, expressions)).toList();
        return value;
    }

    private static void requireFormat(Map<String, Object> state) {
        if (!(state.get("format") instanceof Integer || state.get("format") instanceof Long)
                || ((Number) state.get("format")).longValue() != 1) throw new IllegalStateException("Unknown continuation format");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?>)) throw new IllegalStateException("Invalid continuation mapping");
        return (Map<String, Object>) value;
    }

    static List<Map<String, Object>> maps(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalStateException("Invalid continuation list");
        return list.stream().map(ActionContinuationState::map).toList();
    }
}
