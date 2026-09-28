package org.openfoundry.foundation.api;

import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.*;

import java.util.*;

/** Saved definitions never carry their creator's object or field permissions into execution. */
public final class ObjectSetService {
    private final ApplicationService application;
    private final ObjectSetStore store;

    public ObjectSetService(ApplicationService application, ObjectSetStore store) {
        this.application = Objects.requireNonNull(application);
        this.store = Objects.requireNonNull(store);
    }

    public ObjectSetDefinition create(RequestContext context, SecurityPrincipal principal, Map<String, Object> input) {
        application.requireCurrentSchema(context, principal);
        var spec = ObjectSetSpec.fromMap(input);
        validate(context, principal, spec);
        return store.create(context, spec);
    }

    public ObjectSetDefinition get(RequestContext context, SecurityPrincipal principal, String id) {
        application.requireCurrentSchema(context, principal);
        var definition = store.get(context, id);
        if (definition != null && !definition.createdBy().equals(principal.id())) validate(context, principal, definition.spec());
        application.requireCurrentSchema(context, principal);
        return definition;
    }

    public ObjectSetDefinition getByName(RequestContext context, SecurityPrincipal principal, String name) {
        return list(context, principal, null).stream().filter(definition -> definition.spec().name().equals(name)).findFirst().orElse(null);
    }

    public List<ObjectSetDefinition> list(RequestContext context, SecurityPrincipal principal, String type) {
        application.requireCurrentSchema(context, principal);
        var visible = new ArrayList<ObjectSetDefinition>();
        for (var definition : store.list(context, type)) {
            // Owners retain their authored definitions so they can repair queries after schema/role changes.
            if (!definition.createdBy().equals(principal.id())) {
                try { validate(context, principal, definition.spec()); }
                catch (SecurityException | IllegalArgumentException inaccessible) { continue; }
            }
            visible.add(definition);
        }
        application.requireCurrentSchema(context, principal);
        return List.copyOf(visible);
    }

    public ObjectSetDefinition update(RequestContext context, SecurityPrincipal principal, String id, Map<String, Object> patch, Long expectedVersion) {
        application.requireCurrentSchema(context, principal);
        var definition = required(store.get(context, id));
        definition.requireOwner(context, expectedVersion);
        var stablePatch = org.openfoundry.foundation.spi.schema.PropertyValues.immutableMap(patch);
        var next = definition.spec().patched(stablePatch);
        if (stablePatch.keySet().stream().anyMatch(Set.of("filter", "orderBy", "limit", "aggregation")::contains)
                || Boolean.TRUE.equals(stablePatch.get("isPublic"))) validate(context, principal, next);
        // Owners may rename, annotate or unshare an obsolete definition without executing its old query.
        return store.update(context, id, stablePatch, definition.version());
    }

    public void delete(RequestContext context, SecurityPrincipal principal, String id, Long expectedVersion) {
        application.requireCurrentSchema(context, principal);
        store.delete(context, id, expectedVersion);
    }

    public ObjectQueryResult.Connection execute(RequestContext context, SecurityPrincipal principal, String id, Integer limit, int offset) {
        application.requireCurrentSchema(context, principal);
        var definition = required(store.get(context, id));
        var spec = definition.spec();
        Integer size = limit == null ? spec.limit() : limit;
        var result = application.queryConnection(context, principal, spec.objectType(), new ObjectConnectionQuery(
                filter(spec.filter(), 0), order(spec), new ConnectionPage(size, null, null, null, offset)));
        unchanged(context, id, definition);
        application.requireCurrentSchema(context, principal);
        return result;
    }

    public AggregateResult aggregate(RequestContext context, SecurityPrincipal principal, String id) {
        application.requireCurrentSchema(context, principal);
        var definition = required(store.get(context, id));
        var query = aggregation(definition.spec());
        if (query == null) throw new IllegalArgumentException("ObjectSet has no saved aggregation");
        var result = application.aggregateObjects(context, principal, definition.spec().objectType(), query);
        unchanged(context, id, definition);
        application.requireCurrentSchema(context, principal);
        return result;
    }

    private void unchanged(RequestContext context, String id, ObjectSetDefinition definition) {
        var current = required(store.get(context, id));
        if (!current.equals(definition)) throw new ObjectSetConflictException();
    }

    private void validate(RequestContext context, SecurityPrincipal principal, ObjectSetSpec spec) {
        application.validateObjectSetQuery(context, principal, spec.objectType(), filter(spec.filter(), 0), order(spec), aggregation(spec));
    }

    private static Map<String, String> order(ObjectSetSpec spec) {
        var result = new LinkedHashMap<String, String>();
        spec.orderBy().forEach(sort -> result.put(sort.field(), sort.direction()));
        return result;
    }

    private static AggregateQuery aggregation(ObjectSetSpec spec) {
        if (spec.aggregation() == null) return null;
        var input = new LinkedHashMap<>(spec.aggregation());
        var inner = input.get("filter") == null ? Map.<String, Object>of() : filter(object(input.get("filter")), 0);
        input.put("filter", Map.of("AND", List.of(filter(spec.filter(), 0), inner)));
        return AggregateQuery.fromJson(input);
    }

    /** Accept upstream SPI filter expressions as well as the public Java typed-filter shape. */
    static Map<String, Object> filter(Map<String, Object> input, int depth) {
        if (depth > 32) throw new IllegalArgumentException("ObjectSet filter is too deep");
        if (input.get("field") instanceof String || input.get("operator") instanceof String) {
            if (!input.keySet().equals(Set.of("field", "operator", "value")) || !(input.get("field") instanceof String name)
                    || !(input.get("operator") instanceof String operator)) throw new IllegalArgumentException("Invalid ObjectSet field predicate");
            String op = operator.equals("neq") ? "ne" : operator;
            return Map.of(name, Collections.singletonMap(op, input.get("value")));
        }
        if (input.size() == 1) {
            for (String logical : List.of("and", "or")) {
                if (input.get(logical) instanceof List<?> children) {
                    return Map.of(logical.toUpperCase(Locale.ROOT), children.stream().map(child -> filter(object(child), depth + 1)).toList());
                }
            }
            if (input.get("not") instanceof Map<?, ?>) return Map.of("NOT", filter(object(input.get("not")), depth + 1));
        }
        return input;
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("ObjectSet filter requires an object");
        var result = new LinkedHashMap<String, Object>();
        map.forEach((key, item) -> {
            if (!(key instanceof String name)) throw new IllegalArgumentException("ObjectSet filter keys must be text");
            result.put(name, item);
        });
        return result;
    }

    public static Long expectedVersion(Object value) {
        if (value == null) return null;
        if (value instanceof Integer number) return number.longValue();
        if (value instanceof Long number) return number;
        if (value instanceof String text && text.matches("[1-9][0-9]*")) return Long.valueOf(text);
        throw new IllegalArgumentException("Expected ObjectSet version must be an integer revision");
    }

    private static ObjectSetDefinition required(ObjectSetDefinition definition) {
        if (definition == null) throw new ObjectSetNotFoundException();
        return definition;
    }
}
