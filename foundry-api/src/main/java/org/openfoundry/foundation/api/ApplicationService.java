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
import org.openfoundry.foundation.spi.schema.ComputedFieldDefinition;

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
    private final SchemaBoundStorage storage;
    private final AuthorizationService authorization;
    private final ActionExecutor actions;
    private final Map<String, ActionTypeDefinition> definitions;
    private final Map<String, ActionManifest> manifests;
    private final Map<String, List<PropertyDefinition>> properties;
    private final Map<String, FieldPolicy> fieldPolicies;
    private final Map<String, Map<String, LinkFieldDefinition>> linkFields;
    private final Set<String> relationTypes;
    private final Set<String> objectTypes;
    private final AuthorizationMode authorizationMode;
    private final Map<String, List<ComputedFieldDefinition>> computedFields;
    private final ComputedFieldEvaluator computedEvaluator;
    private final Set<String> enumTypes;
    private final OntologySchema schema;

    public static ApplicationService fromBundle(StorageProvider storage, AuthorizationService authorization, ActionExecutor actions,
                                                 org.openfoundry.foundation.pack.LoadedPackBundle bundle, AuthorizationMode mode) {
        return new ApplicationService(storage, authorization, actions, bundle.ontology().schema(), bundle.actions(), bundle.assets().fieldPolicies(), mode);
    }

    /** Legacy construction is metadata-only and cannot execute Actions without trusted registration. */
    public ApplicationService(StorageProvider storage, AuthorizationService authorization, ActionExecutor actions) {
        this(storage, authorization, actions, null, Map.of(), Map.of());
    }

    public ApplicationService(StorageProvider storage, AuthorizationService authorization, ActionExecutor actions,
                              OntologySchema schema, Map<String, ActionManifest> manifests, Map<String, FieldPolicy> fieldPolicies) {
        this(storage, authorization, actions, schema, manifests, fieldPolicies, AuthorizationMode.STRICT_RESOURCES);
    }

    public ApplicationService(StorageProvider storage, AuthorizationService authorization, ActionExecutor actions,
                              OntologySchema schema, Map<String, ActionManifest> manifests, Map<String, FieldPolicy> fieldPolicies,
                              AuthorizationMode authorizationMode) {
        this.schema = schema;
        this.authorizationMode = Objects.requireNonNull(authorizationMode);
        if (authorizationMode == AuthorizationMode.ONTOLOGY_TARGETS && schema == null) throw new IllegalArgumentException("Ontology authorization requires a schema");
        this.objectTypes = schema == null ? Set.of() : schema.objectTypes().stream()
                .map(org.openfoundry.foundation.spi.schema.ObjectTypeDefinition::name).collect(Collectors.toUnmodifiableSet());
        if (authorizationMode == AuthorizationMode.ONTOLOGY_TARGETS && objectTypes.contains("ActionType")) {
            throw new IllegalArgumentException("ActionType is reserved for object-less action authorization");
        }
        this.storage = SchemaBoundStorage.bind(Objects.requireNonNull(storage), schema);
        this.authorization = Objects.requireNonNull(authorization);
        this.actions = schema == null ? Objects.requireNonNull(actions) : Objects.requireNonNull(actions).withParameterSchema(schema);
        this.enumTypes = schema == null ? Set.of() : Set.copyOf(schema.enums().keySet());
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
        this.computedFields = schema == null ? Map.of() : schema.objectTypes().stream()
                .collect(Collectors.toUnmodifiableMap(type -> type.name(), type -> type.computedFields()));
        this.computedEvaluator = schema == null ? null : new ComputedFieldEvaluator(this.storage, schema);
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

    void requireRenderingSchema(OntologySchema rendered) {
        if (schema != null && !org.openfoundry.foundation.schema.SchemaFingerprint.of(schema).equals(
                org.openfoundry.foundation.schema.SchemaFingerprint.of(rendered))) throw new SchemaVersionMismatchException();
    }

    /** Used by request adapters to discard a read response if activation changed during rendering. */
    public void requireCurrentSchema(RequestContext context, SecurityPrincipal principal) {
        requireContext(context, principal);
        storage.requireCurrent(context);
    }

    public ObjectRecord getObject(RequestContext context, SecurityPrincipal principal, String type, String id) {
        requireContext(context, principal);
        return storage.read(context, () -> {
            if (!authorization.check(context, principal, "viewer", new EntityKey(type, id))) return null;
            var object = storage.getObject(context, type, id);
            return object == null || object.isDeleted() ? null : project(context, principal, object, QueryOptions.defaults());
        });
    }

    public List<ObjectRecord> listObjects(RequestContext context, SecurityPrincipal principal, String type, QueryOptions options) {
        requireContext(context, principal);
        return storage.read(context, () -> {
            if (schema != null) return queryObjects(context, principal, type, ObjectQuery.all(options)).items();
            // Metadata-only legacy callers still paginate after permission filtering.
            var rows = storage.queryObjects(context, type, allRows(options)).stream()
                    .filter(object -> authorization.check(context, principal, "viewer", object.key())).toList();
            return rows.stream().skip(options.offset()).limit(options.limit())
                    .map(object -> project(context, principal, object, options)).toList();
        });
    }

    public ObjectQueryResult queryObjects(RequestContext context, SecurityPrincipal principal, String type, ObjectQuery query) {
        requireContext(context, principal);
        return storage.read(context, () -> {
            var matching = matchingObjects(context, principal, type, query);
            var options = query.options();
            var items = matching.stream().skip(options.offset()).limit(options.limit())
                    .map(object -> project(context, principal, object, options)).toList();
            return new ObjectQueryResult(items, matching.size(), options.offset());
        });
    }

    public ObjectQueryResult.Connection queryConnection(RequestContext context, SecurityPrincipal principal, String type, ObjectConnectionQuery query) {
        requireContext(context, principal);
        return storage.read(context, () -> {
            var source = query.sourceQuery();
            var matching = matchingObjects(context, principal, type, source);
            var window = query.page().window(matching.size());
            var items = matching.subList(window.start(), window.end()).stream()
                    .map(object -> project(context, principal, object, source.options())).toList();
            return new ObjectQueryResult(items, matching.size(), window.start()).connection();
        });
    }

    private List<ObjectRecord> matchingObjects(RequestContext context, SecurityPrincipal principal, String type, ObjectQuery query) {
        requireContext(context, principal);
        if (schema == null || !objectTypes.contains(type)) throw new IllegalArgumentException("Query requires a registered object type");
        var plan = new ObjectQueryPlan(schema, properties.get(type), visibleFields(principal, type));
        var predicate = plan.predicate(query.filter());
        var comparator = plan.comparator(query.orderBy());
        // One storage read supplies both rows and count. SQL pushdown is a separate optimization.
        return storage.queryObjects(context, type, allRows(query.options())).stream()
                .filter(object -> authorization.check(context, principal, "viewer", object.key()))
                .filter(predicate).sorted(comparator).toList();
    }

    public AggregateResult aggregateObjects(RequestContext context, SecurityPrincipal principal, String type, AggregateQuery query) {
        requireContext(context, principal);
        return storage.read(context, () -> {
            if (schema == null || !objectTypes.contains(type)) throw new IllegalArgumentException("Aggregate requires a registered object type");
            var visible = visibleFields(principal, type);
            var aggregation = new ObjectAggregationPlan(properties.get(type), visible, query);
            var predicate = new ObjectQueryPlan(schema, properties.get(type), visible).predicate(query.filter());
            var rows = storage.queryObjects(context, type, query.sourceView()).stream()
                    .filter(object -> authorization.check(context, principal, "viewer", object.key()))
                    .filter(predicate).toList();
            return aggregation.evaluate(rows);
        });
    }

    public SearchResult searchObjects(RequestContext context, SecurityPrincipal principal, String type, SearchQuery query) {
        requireContext(context, principal);
        return storage.read(context, () -> {
            if (schema == null || !objectTypes.contains(type)) throw new IllegalArgumentException("Search requires a registered object type");
            var visible = visibleFields(principal, type);
            var search = new ObjectSearchPlan(schema, properties.get(type), visible, query);
            var predicate = new ObjectQueryPlan(schema, properties.get(type), visible).predicate(query.filter());
            var view = query.sourceView();
            var rows = storage.queryObjects(context, type, view).stream()
                    .filter(object -> authorization.check(context, principal, "viewer", object.key()))
                    .filter(predicate).toList();
            return search.evaluate(rows, object -> project(context, principal, object, view));
        });
    }

    private static QueryOptions allRows(QueryOptions options) {
        return new QueryOptions(Integer.MAX_VALUE, 0, options.asOfValidTime(), options.asOfRecordedTime(), options.includeDeleted());
    }

    public List<HistorySnapshot> history(RequestContext context, SecurityPrincipal principal, EntityKey key) {
        requireContext(context, principal);
        return storage.read(context, () -> {
            if (!canViewEntity(context, principal, key)) return List.of();
            return storage.getEntityHistory(context, key).stream().map(history -> new HistorySnapshot(history.key(), history.version(),
                    history.operation(), history.validFrom(), history.validTo(), history.recordedAt(), history.transactionId(),
                    history.actionId(), history.actorId(), history.sourceSystem(), visible(principal, key.type(), history.state()))).toList();
        });
    }

    /** Resolve a declared relationship field. Pagination counts authorized rows, never hidden edges. */
    public Object readLinkField(RequestContext context, SecurityPrincipal principal, EntityKey source,
                                String fieldName, QueryOptions options) {
        requireContext(context, principal);
        return storage.read(context, () -> {
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
        });
    }

    private Object visibleLinkedValue(RequestContext context, SecurityPrincipal principal,
                                      LinkFieldDefinition field, LinkRecord link) {
        if (authorizationMode == AuthorizationMode.STRICT_RESOURCES
                && !authorization.check(context, principal, "viewer", new EntityKey(link.type(), link.id()))) return null;
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
        return object.isDeleted() ? null : project(context, principal, object, QueryOptions.defaults());
    }

    public Object readComputedField(RequestContext context, SecurityPrincipal principal, EntityKey source, String name) {
        return readComputedField(context, principal, source, name, QueryOptions.defaults());
    }

    public Object readComputedField(RequestContext context, SecurityPrincipal principal, EntityKey source, String name, QueryOptions view) {
        requireContext(context, principal);
        return storage.read(context, () -> {
            var field = computedFields.getOrDefault(source.type(), List.of()).stream().filter(value -> value.name().equals(name))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown computed field"));
            if (!authorization.check(context, principal, "viewer", source) || !visibleComputedField(principal, source.type(), field)) return null;
            if (view.asOfValidTime() != null) {
                if (!TemporalHistory.active(storage.getObjectAtTime(context, source.type(), source.id(), view.asOfValidTime(), view.asOfRecordedTime()))) return null;
            } else {
                var object = storage.getObject(context, source.type(), source.id());
                if (object == null || object.isDeleted()) return null;
            }
            return computedValue(context, principal, source, field, view);
        });
    }

    private Object computedValue(RequestContext context, SecurityPrincipal principal, EntityKey source,
                                 ComputedFieldDefinition field, QueryOptions view) {
        return computedEvaluator.evaluate(context, source, field.name(), view, link -> {
            if (authorizationMode == AuthorizationMode.STRICT_RESOURCES
                    && !authorization.check(context, principal, "viewer", new EntityKey(link.type(), link.id()))) return false;
            var target = field.direction() == StorageProvider.Direction.INBOUND ? link.from() : link.to();
            if (!authorization.check(context, principal, "viewer", target)) return false;
            if (view.asOfValidTime() != null) {
                return TemporalHistory.active(storage.getObjectAtTime(context, target.type(), target.id(), view.asOfValidTime(), view.asOfRecordedTime()));
            }
            var object = storage.getObject(context, target.type(), target.id());
            return object != null && !object.isDeleted();
        });
    }

    private boolean visibleComputedField(SecurityPrincipal principal, String owner, ComputedFieldDefinition field) {
        var policy = fieldPolicies.get(owner);
        if (policy == null || policy.storedFieldsOnly()) return !field.sensitive();
        return policy.alwaysVisible().contains(field.name()) || principal.roles().stream()
                .anyMatch(role -> policy.fieldsByRole().getOrDefault(role, Set.of()).contains(field.name()));
    }

    private ObjectRecord project(RequestContext context, SecurityPrincipal principal, ObjectRecord object, QueryOptions view) {
        var values = new LinkedHashMap<>(visible(principal, object.type(), object.properties()));
        if (!object.isDeleted()) {
            for (var field : computedFields.getOrDefault(object.type(), List.of())) {
                if (visibleComputedField(principal, object.type(), field)) values.put(field.name(), computedValue(context, principal, object.key(), field, view));
            }
        }
        return new ObjectRecord(object.tenantId(), object.type(), object.id(), object.version(), object.createdAt(), object.updatedAt(),
                object.deletedAt(), object.lastTransactionId(), object.lastActionId(), values);
    }

    private boolean visibleLinkField(SecurityPrincipal principal, String owner, LinkFieldDefinition field) {
        var policy = fieldPolicies.get(owner);
        if (policy == null || policy.storedFieldsOnly()) return !field.sensitive();
        return policy.alwaysVisible().contains(field.name()) || principal.roles().stream()
                .anyMatch(role -> policy.fieldsByRole().getOrDefault(role, Set.of()).contains(field.name()));
    }

    private static Map<String, LinkFieldDefinition> indexLinkFields(List<LinkFieldDefinition> fields) {
        return fields.stream().collect(Collectors.toUnmodifiableMap(LinkFieldDefinition::name, field -> field));
    }

    public ActionResult execute(ActionManifest manifest, RequestContext context, SecurityPrincipal principal,
                                Map<String, Object> parameters, String idempotencyKey) {
        requireContext(context, principal);
        storage.requireCurrent(context);
        ActionManifest registered = manifests.get(manifest.action());
        if (registered == null || !registered.equals(manifest)) throw new SecurityException("Unregistered or altered Action manifest");
        ActionTypeDefinition definition = definitions.get(registered.action());
        // Check type-level permission before resolving IDs, then check every resolved object again before replay/write.
        if (authorizationMode == AuthorizationMode.ONTOLOGY_TARGETS
                ? !ontologyPolicy(principal).primaryAllowed(context, definition, parameters)
                : !authorization.check(context, principal, definition.permission(), new EntityKey("ActionType", definition.name()))) {
            throw new SecurityException("Action denied");
        }
        Set<String> declared = definition.parameters().stream().map(parameter -> parameter.name()).collect(Collectors.toSet());
        if (!declared.containsAll(parameters.keySet())) throw new IllegalArgumentException("Unknown Action parameter");
        var resolved = new LinkedHashMap<String, Object>();
        for (var parameter : definition.parameters()) {
            Object value = parameters.get(parameter.name());
            resolved.put(parameter.name(), resolve(context, parameter.type(), value));
        }
        return actions.withAuthorization(actionPolicy(principal)).execute(registered, definition, context,
                new ActionActor(principal.id(), principal.roles()), Collections.unmodifiableMap(resolved), idempotencyKey, storage);
    }

    public ActionResult resume(RequestContext context, SecurityPrincipal principal, String actionName, String actionId) {
        requireContext(context, principal);
        storage.requireCurrent(context);
        var manifest = manifests.get(actionName);
        var definition = definitions.get(actionName);
        if (manifest == null || definition == null) throw new SecurityException("Action continuation denied");
        if (authorizationMode == AuthorizationMode.STRICT_RESOURCES && !authorization.check(context, principal, definition.permission(),
                new EntityKey("ActionType", definition.name()))) throw new SecurityException("Action continuation denied");
        return actions.withAuthorization(actionPolicy(principal)).resume(manifest, definition, actionId, context,
                new ActionActor(principal.id(), principal.roles()), storage);
    }

    private OntologyActionAuthorizer ontologyPolicy(SecurityPrincipal principal) {
        return new OntologyActionAuthorizer(authorization, principal, objectTypes, relationTypes);
    }

    private boolean canViewEntity(RequestContext context, SecurityPrincipal principal, EntityKey key) {
        if (authorizationMode == AuthorizationMode.ONTOLOGY_TARGETS && relationTypes.contains(key.type())) {
            var link = storage.getLink(context, key.type(), key.id());
            return link != null && authorization.check(context, principal, "viewer", link.from())
                    && authorization.check(context, principal, "viewer", link.to());
        }
        return authorization.check(context, principal, "viewer", key);
    }

    private ActionAuthorizer actionPolicy(SecurityPrincipal principal) {
        if (authorizationMode == AuthorizationMode.ONTOLOGY_TARGETS) return ontologyPolicy(principal);
        return new ActionAuthorizer() {
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
    }

    private Object resolve(RequestContext context, String type, Object value) {
        if (value == null) return null;
        if (type.endsWith("!")) type = type.substring(0, type.length() - 1);
        if (type.startsWith("[") && type.endsWith("]")) {
            if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected parameter list");
            String element = type.substring(1, type.length() - 1);
            return list.stream().map(item -> resolve(context, element, item)).toList();
        }
        if (org.openfoundry.foundation.spi.schema.PropertyValues.SCALARS.contains(type) || enumTypes.contains(type)) return value;
        if (!objectTypes.contains(type) || !(value instanceof String id) || id.isBlank()) {
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

    private Map<String, Object> visible(SecurityPrincipal principal, String type, Map<String, Object> values) {
        Set<String> allowed = visibleFields(principal, type);
        var result = new LinkedHashMap<String, Object>();
        for (var field : values.entrySet()) if (allowed.contains(field.getKey())) result.put(field.getKey(), field.getValue());
        return Collections.unmodifiableMap(result);
    }

    private Set<String> visibleFields(SecurityPrincipal principal, String type) {
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
        return allowed;
    }

    private static void requireContext(RequestContext context, SecurityPrincipal principal) {
        if (principal == null || !context.tenantId().equals(principal.tenantId()) || !principal.id().equals(context.actorId())) {
            throw new SecurityException("Authenticated tenant and actor must match the request context");
        }
    }
}
