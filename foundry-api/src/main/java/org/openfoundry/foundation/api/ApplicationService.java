package org.openfoundry.foundation.api;

import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.security.FieldPolicy;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Authenticated boundary shared by REST and GraphQL. Schema, manifests and policies are trusted startup configuration. */
public final class ApplicationService {
    private final StorageProvider storage;
    private final AuthorizationService authorization;
    private final ActionExecutor actions;
    private final Map<String, ActionTypeDefinition> definitions;
    private final Map<String, ActionManifest> manifests;
    private final Map<String, List<PropertyDefinition>> properties;
    private final Map<String, FieldPolicy> fieldPolicies;

    /** Legacy construction is metadata-only and cannot execute Actions without trusted registration. */
    public ApplicationService(StorageProvider storage, AuthorizationService authorization, ActionExecutor actions) {
        this(storage, authorization, actions, null, Map.of(), Map.of());
    }

    public ApplicationService(StorageProvider storage, AuthorizationService authorization, ActionExecutor actions,
                              OntologySchema schema, Map<String, ActionManifest> manifests, Map<String, FieldPolicy> fieldPolicies) {
        this.storage = Objects.requireNonNull(storage);
        this.authorization = Objects.requireNonNull(authorization);
        this.actions = Objects.requireNonNull(actions);
        this.manifests = Map.copyOf(manifests);
        this.fieldPolicies = Map.copyOf(fieldPolicies);
        this.definitions = schema == null ? Map.of() : schema.actionTypes().stream()
                .collect(Collectors.toUnmodifiableMap(ActionTypeDefinition::name, definition -> definition));
        var propertyMap = new LinkedHashMap<String, List<PropertyDefinition>>();
        if (schema != null) {
            schema.objectTypes().forEach(type -> propertyMap.put(type.name(), type.properties()));
            schema.linkTypes().forEach(type -> propertyMap.put(type.name(), type.properties()));
        }
        this.properties = Map.copyOf(propertyMap);
        this.manifests.forEach((name, manifest) -> {
            var definition = definitions.get(name);
            if (!name.equals(manifest.action()) || definition == null || definition.permission() == null) {
                throw new IllegalArgumentException("Executable Action requires a registered type and explicit permission: " + name);
            }
        });
        if (!properties.keySet().containsAll(fieldPolicies.keySet())) throw new IllegalArgumentException("Policy has an unknown type");
    }

    public ObjectRecord getObject(RequestContext context, SecurityPrincipal principal, String type, String id) {
        requireContext(context, principal);
        if (!authorization.check(context, principal, "viewer", new EntityKey(type, id))) return null;
        var object = storage.getObject(context, type, id);
        return object == null || object.isDeleted() ? null : redact(principal, object);
    }

    public List<ObjectRecord> listObjects(RequestContext context, SecurityPrincipal principal, String type, QueryOptions options) {
        requireContext(context, principal);
        return storage.queryObjects(context, type, options).stream()
                .filter(object -> authorization.check(context, principal, "viewer", object.key()))
                .map(object -> redact(principal, object)).toList();
    }

    public List<HistorySnapshot> history(RequestContext context, SecurityPrincipal principal, EntityKey key) {
        requireContext(context, principal);
        if (!authorization.check(context, principal, "viewer", key)) return List.of();
        return storage.getEntityHistory(context, key).stream().map(history -> new HistorySnapshot(history.key(), history.version(),
                history.operation(), history.validFrom(), history.validTo(), history.recordedAt(), history.transactionId(),
                history.actionId(), history.actorId(), history.sourceSystem(), visible(principal, key.type(), history.state()))).toList();
    }

    public ActionResult execute(ActionManifest manifest, RequestContext context, SecurityPrincipal principal,
                                Map<String, Object> parameters, String idempotencyKey) {
        requireContext(context, principal);
        ActionManifest registered = manifests.get(manifest.action());
        if (registered == null || !registered.equals(manifest)) throw new SecurityException("Unregistered or altered Action manifest");
        ActionTypeDefinition definition = definitions.get(registered.action());
        // Check type-level permission before resolving IDs, then check every resolved object again before replay/write.
        if (!authorization.check(context, principal, definition.permission(), new EntityKey("ActionType", definition.name()))) {
            throw new SecurityException("Action denied");
        }
        Set<String> declared = definition.parameters().stream().map(parameter -> parameter.name()).collect(Collectors.toSet());
        if (!declared.containsAll(parameters.keySet())) throw new IllegalArgumentException("Unknown Action parameter");
        var resolved = new LinkedHashMap<String, Object>();
        for (var parameter : definition.parameters()) {
            Object value = parameters.get(parameter.name());
            resolved.put(parameter.name(), resolve(context, parameter.type(), value));
        }
        return actions.withAuthorization((ctx, actor, type, values) ->
                authorization.check(ctx, principal, type.permission(), new EntityKey("ActionType", type.name()))
                        && permittedReferences(ctx, principal, type.permission(), values.values())
                        && permittedDeletions(ctx, principal, type.permission(), registered, values))
                .execute(registered, definition, context, new ActionActor(principal.id(), principal.roles()),
                        Collections.unmodifiableMap(resolved), idempotencyKey, storage);
    }

    private Object resolve(RequestContext context, String type, Object value) {
        if (value == null) return null;
        if (type.endsWith("!")) type = type.substring(0, type.length() - 1);
        if (type.startsWith("[") && type.endsWith("]")) {
            if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected parameter list");
            String element = type.substring(1, type.length() - 1);
            return list.stream().map(item -> resolve(context, element, item)).toList();
        }
        if (Set.of("ID", "String", "Int", "Float", "Boolean", "Date", "DateTime", "JSON").contains(type)) return value;
        if (!properties.containsKey(type) || !(value instanceof String id) || id.isBlank()) {
            throw new IllegalArgumentException("Object parameter must be an ID of its declared type");
        }
        var object = storage.getObject(context, type, id);
        if (object == null || object.isDeleted()) throw new SecurityException("Object reference is not available");
        return object;
    }

    private boolean permittedReferences(RequestContext context, SecurityPrincipal principal, String permission, Iterable<?> values) {
        for (Object value : values) {
            if (value instanceof ObjectRecord object && !authorization.check(context, principal, permission, object.key())) return false;
            if (value instanceof List<?> list && !permittedReferences(context, principal, permission, list)) return false;
        }
        return true;
    }

    private boolean permittedDeletions(RequestContext context, SecurityPrincipal principal, String permission,
                                       ActionManifest manifest, Map<String, Object> parameters) {
        for (var effect : manifest.effects()) {
            if (effect instanceof ActionManifest.DeleteLink deletion) {
                String reference = deletion.linkId();
                Object value = reference.startsWith("params.") ? parameters.get(reference.substring(7)) : reference;
                if (!(value instanceof String id)) return false;
                var link = storage.getLink(context, deletion.linkType(), id);
                if (link == null
                        || !authorization.check(context, principal, permission, new EntityKey(link.type(), link.id()))
                        || !authorization.check(context, principal, permission, link.from())
                        || !authorization.check(context, principal, permission, link.to())) return false;
            }
        }
        return true;
    }

    private ObjectRecord redact(SecurityPrincipal principal, ObjectRecord object) {
        return new ObjectRecord(object.tenantId(), object.type(), object.id(), object.version(), object.createdAt(), object.updatedAt(),
                object.deletedAt(), object.lastTransactionId(), object.lastActionId(), visible(principal, object.type(), object.properties()));
    }

    private Map<String, Object> visible(SecurityPrincipal principal, String type, Map<String, Object> values) {
        var declared = properties.getOrDefault(type, List.of());
        FieldPolicy policy = fieldPolicies.get(type);
        Set<String> allowed;
        if (policy == null) {
            allowed = declared.stream().filter(property -> !property.sensitive()).map(PropertyDefinition::name).collect(Collectors.toSet());
        } else {
            allowed = new java.util.HashSet<>(policy.alwaysVisible());
            principal.roles().forEach(role -> allowed.addAll(policy.fieldsByRole().getOrDefault(role, Set.of())));
            allowed.retainAll(declared.stream().map(PropertyDefinition::name).collect(Collectors.toSet()));
        }
        var result = new LinkedHashMap<String, Object>();
        for (var field : values.entrySet()) if (allowed.contains(field.getKey())) result.put(field.getKey(), field.getValue());
        return Collections.unmodifiableMap(result);
    }

    private static void requireContext(RequestContext context, SecurityPrincipal principal) {
        if (principal == null || !context.tenantId().equals(principal.tenantId()) || !principal.id().equals(context.actorId())) {
            throw new SecurityException("Authenticated tenant and actor must match the request context");
        }
    }
}
