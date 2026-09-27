package org.openfoundry.foundation.api;

import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.security.FieldPolicy;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;
import org.openfoundry.foundation.spi.schema.LinkFieldDefinition;

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
    private final Map<String, Map<String, LinkFieldDefinition>> linkFields;
    private final Set<String> relationTypes;

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
        var navigation = new LinkedHashMap<String, Map<String, LinkFieldDefinition>>();
        if (schema != null) {
            org.openfoundry.foundation.spi.schema.PropertyValues.requireSchema(schema);
            schema.objectTypes().forEach(type -> navigation.put(type.name(), indexLinkFields(type.linkFields())));
            schema.linkTypes().forEach(type -> navigation.put(type.name(), indexLinkFields(type.linkFields())));
        }
        this.linkFields = Map.copyOf(navigation);
        this.relationTypes = schema == null ? Set.of() : schema.linkTypes().stream()
                .map(org.openfoundry.foundation.spi.schema.LinkTypeDefinition::name).collect(Collectors.toUnmodifiableSet());
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

    /** Resolve a declared relationship field. Pagination counts authorized rows, never hidden edges. */
    public Object readLinkField(RequestContext context, SecurityPrincipal principal, EntityKey source,
                                String fieldName, QueryOptions options) {
        requireContext(context, principal);
        var field = linkFields.getOrDefault(source.type(), Map.of()).get(fieldName);
        if (field == null) throw new IllegalArgumentException("Unknown relationship field");
        if (options.asOfValidTime() != null || options.includeDeleted()) {
            throw new IllegalArgumentException("Relationship fields use their declared history policy; point-in-time reads require a temporal query");
        }
        if (getObject(context, principal, source.type(), source.id()) == null
                || !visibleLinkField(principal, source.type(), field)) return null;
        int limit = field.many() ? options.limit() : 2;
        int offset = field.many() ? options.offset() : 0;
        int skipped = 0;
        int rawOffset = 0;
        var visible = new ArrayList<Object>();
        while (visible.size() < limit) {
            var rows = storage.getLinks(context, source, field.linkType(), field.direction(),
                    new QueryOptions(100, rawOffset, null, null, field.history()));
            for (var link : rows) {
                Object value = visibleLinkedValue(context, principal, field, link);
                if (value == null) continue;
                if (skipped < offset) {
                    skipped++;
                    continue;
                }
                visible.add(value);
                if (visible.size() == limit) break;
            }
            if (rows.size() < 100) break;
            rawOffset = Math.addExact(rawOffset, rows.size());
        }
        if (field.many()) return List.copyOf(visible);
        if (visible.size() > 1) throw new IllegalStateException("Relationship cardinality does not match the registered schema");
        return visible.isEmpty() ? null : visible.getFirst();
    }

    private Object visibleLinkedValue(RequestContext context, SecurityPrincipal principal,
                                      LinkFieldDefinition field, LinkRecord link) {
        if (!authorization.check(context, principal, "viewer", new EntityKey(link.type(), link.id()))) return null;
        EntityKey target = field.direction() == StorageProvider.Direction.OUTBOUND ? link.to() : link.from();
        if (!authorization.check(context, principal, "viewer", target)) return null;
        var object = storage.getObject(context, target.type(), target.id());
        if (object == null) return null;
        if (field.targetType().equals(field.linkType())) {
            if (object.isDeleted() && !field.history()) return null;
            return new LinkRecord(link.tenantId(), link.type(), link.id(), link.from(), link.to(), link.version(),
                    link.createdAt(), link.updatedAt(), link.deletedAt(), link.validFrom(), link.validTo(),
                    link.lastTransactionId(), link.lastActionId(), visible(principal, link.type(), link.properties()));
        }
        return object.isDeleted() ? null : redact(principal, object);
    }

    private boolean visibleLinkField(SecurityPrincipal principal, String owner, LinkFieldDefinition field) {
        var policy = fieldPolicies.get(owner);
        if (policy == null) return !field.sensitive();
        return policy.alwaysVisible().contains(field.name()) || principal.roles().stream()
                .anyMatch(role -> policy.fieldsByRole().getOrDefault(role, Set.of()).contains(field.name()));
    }

    private static Map<String, LinkFieldDefinition> indexLinkFields(List<LinkFieldDefinition> fields) {
        return fields.stream().collect(Collectors.toUnmodifiableMap(LinkFieldDefinition::name, field -> field));
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
        ActionAuthorizer policy = new ActionAuthorizer() {
            @Override
            public boolean allowed(RequestContext ctx, ActionActor actor, ActionTypeDefinition type, Map<String, Object> values) {
                return check(ctx, type, values, null);
            }

            @Override
            public boolean allowed(RequestContext ctx, ActionActor actor, ActionTypeDefinition type,
                                   Map<String, Object> values, Transaction transaction) {
                return check(ctx, type, values, transaction);
            }

            @Override
            public boolean allowedChanges(RequestContext ctx, ActionActor actor, ActionTypeDefinition type,
                                          Map<String, Object> values, List<EntityKey> affected, Transaction transaction) {
                if (!check(ctx, type, values, transaction)) return false;
                for (var key : affected) {
                    if (!properties.containsKey(key.type()) || !authorization.check(ctx, principal, type.permission(), key)) return false;
                    if (relationTypes.contains(key.type())) {
                        var link = transaction.getLink(key.type(), key.id());
                        if (link == null || !authorization.check(ctx, principal, type.permission(), link.from())
                                || !authorization.check(ctx, principal, type.permission(), link.to())) return false;
                    } else if (transaction.getObject(key.type(), key.id()) == null) {
                        return false;
                    }
                }
                return true;
            }

            private boolean check(RequestContext ctx, ActionTypeDefinition type, Map<String, Object> values, Transaction transaction) {
                return authorization.check(ctx, principal, type.permission(), new EntityKey("ActionType", type.name()))
                        && permittedReferences(ctx, principal, type.permission(), values.values());
            }
        };
        return actions.withAuthorization(policy).execute(registered, definition, context, new ActionActor(principal.id(), principal.roles()),
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
