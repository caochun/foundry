package org.openfoundry.foundation.api;

import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.ActionParameter;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Permission relations are checked on the object types where the upstream model actually declares them. */
final class OntologyActionAuthorizer implements ActionAuthorizer {
    private final AuthorizationService authorization;
    private final SecurityPrincipal principal;
    private final Set<String> objectTypes;
    private final Set<String> linkTypes;

    OntologyActionAuthorizer(AuthorizationService authorization, SecurityPrincipal principal, Set<String> objectTypes, Set<String> linkTypes) {
        this.authorization = authorization;
        this.principal = principal;
        this.objectTypes = objectTypes;
        this.linkTypes = linkTypes;
    }

    boolean primaryAllowed(RequestContext context, ActionTypeDefinition definition, Map<String, Object> values) {
        var target = target(definition);
        if (target == null) {
            // Unlike upstream's permissive role-only fallback, object-less commands still require an explicit grant.
            return permission(context, definition.permission(), new EntityKey("ActionType", definition.name()));
        }
        var keys = keys(values.get(target.name()), base(target.type()));
        return !keys.isEmpty() && keys.stream().allMatch(key -> permission(context, definition.permission(), key));
    }

    @Override
    public boolean allowed(RequestContext context, ActionActor actor, ActionTypeDefinition definition, Map<String, Object> values) {
        if (!primaryAllowed(context, definition, values)) return false;
        var primary = primaryKeys(definition, values);
        return visibleReferences(context, values.values(), primary);
    }

    @Override
    public boolean allowedChanges(RequestContext context, ActionActor actor, ActionTypeDefinition definition, Map<String, Object> values,
                                  List<EntityKey> affected, Transaction transaction) {
        if (!allowed(context, actor, definition, values)) return false;
        var primary = primaryKeys(definition, values);
        for (var key : affected) {
            if (linkTypes.contains(key.type())) {
                var link = transaction.getLink(key.type(), key.id());
                if (link == null || !visibleEndpoint(context, link.from(), primary, Set.of())
                        || !visibleEndpoint(context, link.to(), primary, Set.of())) return false;
            } else if (!objectTypes.contains(key.type()) || transaction.getObject(key.type(), key.id()) == null
                    || !primary.contains(key) && !permission(context, "editor", key)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean allowedUpdate(RequestContext context, ActionActor actor, ActionTypeDefinition definition, Map<String, Object> values,
                                 EntityKey target, List<ActionEffectAccess> preceding, Transaction transaction) {
        return allowed(context, actor, definition, values) && objectTypes.contains(target.type())
                && (primaryKeys(definition, values).contains(target) || createdObjects(preceding).contains(target)
                || permission(context, "editor", target));
    }

    @Override
    public boolean allowedSelection(RequestContext context, ActionActor actor, ActionTypeDefinition definition, Map<String, Object> values,
                                    List<ActionEffectAccess> preceding, List<LinkRecord> selected, Transaction transaction) {
        if (!allowed(context, actor, definition, values)) return false;
        var primary = primaryKeys(definition, values);
        var created = createdObjects(preceding);
        return selected.stream().allMatch(link -> linkTypes.contains(link.type())
                && visibleEndpoint(context, link.from(), primary, created) && visibleEndpoint(context, link.to(), primary, created));
    }

    @Override
    public boolean allowedEffects(RequestContext context, ActionActor actor, ActionTypeDefinition definition, Map<String, Object> values,
                                  List<ActionEffectAccess> effects, Transaction transaction) {
        if (!allowed(context, actor, definition, values)) return false;
        var primary = primaryKeys(definition, values);
        var created = createdObjects(effects);
        for (var effect : effects) {
            var key = effect.entity();
            switch (effect.kind()) {
                case CREATE_OBJECT -> {
                    if (!objectTypes.contains(key.type()) || transaction.getObject(key.type(), key.id()) == null) return false;
                }
                case UPDATE_OBJECT -> {
                    if (!objectTypes.contains(key.type()) || transaction.getObject(key.type(), key.id()) == null
                            || !created.contains(key) && !primary.contains(key) && !permission(context, "editor", key)) return false;
                }
                case CREATE_LINK, DELETE_LINK -> {
                    if (!linkTypes.contains(key.type())) return false;
                    var link = transaction.getLink(key.type(), key.id());
                    if (link == null || !visibleEndpoint(context, link.from(), primary, created)
                            || !visibleEndpoint(context, link.to(), primary, created)) return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean allowedReplay(RequestContext context, ActionActor actor, ActionTypeDefinition definition, Map<String, Object> values,
                                 List<ActionEffectAccess> effects, Transaction transaction) {
        return allowedEffects(context, actor, definition, values, effects, transaction);
    }

    private boolean visibleReferences(RequestContext context, Iterable<?> values, Set<EntityKey> primary) {
        for (var value : values) {
            if (value instanceof ObjectRecord object && !primary.contains(object.key()) && !permission(context, "viewer", object.key())) return false;
            if (value instanceof List<?> list && !visibleReferences(context, list, primary)) return false;
        }
        return true;
    }

    private boolean visibleEndpoint(RequestContext context, EntityKey key, Set<EntityKey> primary, Set<EntityKey> created) {
        return objectTypes.contains(key.type()) && (primary.contains(key) || created.contains(key) || permission(context, "viewer", key));
    }

    private boolean permission(RequestContext context, String relation, EntityKey key) {
        return relation != null && authorization.check(context, principal, relation, key);
    }

    private Set<EntityKey> primaryKeys(ActionTypeDefinition definition, Map<String, Object> values) {
        var target = target(definition);
        return target == null ? Set.of() : new HashSet<>(keys(values.get(target.name()), base(target.type())));
    }

    private ActionParameter target(ActionTypeDefinition definition) {
        return definition.parameters().stream().filter(parameter -> objectTypes.contains(base(parameter.type()))).findFirst().orElse(null);
    }

    private static Set<EntityKey> createdObjects(List<ActionEffectAccess> effects) {
        return effects.stream().filter(effect -> effect.kind() == ActionEffectAccess.Kind.CREATE_OBJECT)
                .map(ActionEffectAccess::entity).collect(java.util.stream.Collectors.toSet());
    }

    private static List<EntityKey> keys(Object value, String type) {
        if (value == null) return List.of();
        if (value instanceof String id && !id.isBlank()) return List.of(new EntityKey(type, id));
        if (value instanceof ObjectRecord object && object.type().equals(type)) return List.of(object.key());
        if (value instanceof List<?> list) {
            var result = new ArrayList<EntityKey>();
            list.forEach(item -> result.addAll(keys(item, type)));
            return List.copyOf(result);
        }
        throw new IllegalArgumentException("Action target must contain IDs of its declared object type");
    }

    private static String base(String type) { return type.replace("[", "").replace("]", "").replace("!", ""); }
}
