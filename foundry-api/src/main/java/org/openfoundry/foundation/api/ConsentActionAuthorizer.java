package org.openfoundry.foundation.api;

import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;

import java.util.*;
import java.util.stream.Collectors;

/** Consent composes with, and never replaces, the registered action/resource authorization policy. */
final class ConsentActionAuthorizer implements ActionAuthorizer {
    private final ActionAuthorizer delegate;
    private final ConsentService consent;
    private final SecurityPrincipal principal;
    private final Set<String> linkTypes;
    ConsentActionAuthorizer(ActionAuthorizer delegate, ConsentService consent, SecurityPrincipal principal, Set<String> linkTypes) {
        this.delegate = delegate; this.consent = consent; this.principal = principal; this.linkTypes = linkTypes;
    }
    @Override public boolean allowed(RequestContext ctx, ActionActor actor, ActionTypeDefinition type, Map<String, Object> values) {
        if (!delegate.allowed(ctx, actor, type, values)) return false;
        parameters(ctx, values.values(), null);
        return true;
    }
    @Override public boolean allowed(RequestContext ctx, ActionActor actor, ActionTypeDefinition type, Map<String, Object> values, Transaction tx) {
        if (!delegate.allowed(ctx, actor, type, values, tx)) return false;
        parameters(ctx, values.values(), tx);
        return true;
    }
    @Override public boolean allowedChanges(RequestContext ctx, ActionActor actor, ActionTypeDefinition type, Map<String, Object> values, List<EntityKey> affected, Transaction tx) {
        if (!delegate.allowedChanges(ctx, actor, type, values, affected, tx)) return false;
        parameters(ctx, values.values(), tx);
        affected.forEach(key -> target(ctx, key, Set.of(), tx));
        return true;
    }
    @Override public boolean allowedUpdate(RequestContext ctx, ActionActor actor, ActionTypeDefinition type, Map<String, Object> values, EntityKey target, List<ActionEffectAccess> preceding, Transaction tx) {
        if (!delegate.allowedUpdate(ctx, actor, type, values, target, preceding, tx)) return false;
        parameters(ctx, values.values(), tx);
        target(ctx, target, created(preceding), tx);
        return true;
    }
    @Override public boolean allowedSelection(RequestContext ctx, ActionActor actor, ActionTypeDefinition type, Map<String, Object> values, List<ActionEffectAccess> preceding, List<LinkRecord> selected, Transaction tx) {
        if (!delegate.allowedSelection(ctx, actor, type, values, preceding, selected, tx)) return false;
        parameters(ctx, values.values(), tx);
        var created = created(preceding);
        selected.forEach(link -> endpoints(ctx, link, created, tx));
        return true;
    }
    @Override public boolean allowedEffects(RequestContext ctx, ActionActor actor, ActionTypeDefinition type, Map<String, Object> values, List<ActionEffectAccess> effects, Transaction tx) {
        if (!delegate.allowedEffects(ctx, actor, type, values, effects, tx)) return false;
        parameters(ctx, values.values(), tx);
        var created = created(effects);
        effects.forEach(effect -> target(ctx, effect.entity(), created, tx));
        return true;
    }
    @Override public boolean allowedReplay(RequestContext ctx, ActionActor actor, ActionTypeDefinition type, Map<String, Object> values, List<ActionEffectAccess> effects, Transaction tx) {
        if (!delegate.allowedReplay(ctx, actor, type, values, effects, tx)) return false;
        parameters(ctx, values.values(), tx);
        var created = created(effects);
        effects.forEach(effect -> target(ctx, effect.entity(), created, tx));
        return true;
    }
    private void parameters(RequestContext ctx, Iterable<?> values, Transaction tx) {
        for (Object value : values) {
            if (value instanceof ObjectRecord object) consent.guardAction(ctx, principal, object.key(), tx);
            if (value instanceof List<?> list) parameters(ctx, list, tx);
        }
    }
    private void target(RequestContext ctx, EntityKey key, Set<EntityKey> created, Transaction tx) {
        if (created.contains(key)) return;
        if (linkTypes.contains(key.type())) {
            var link = tx.getLink(key.type(), key.id());
            if (link != null) endpoints(ctx, link, created, tx);
        } else consent.guardAction(ctx, principal, key, tx);
    }
    private void endpoints(RequestContext ctx, LinkRecord link, Set<EntityKey> created, Transaction tx) {
        if (!created.contains(link.from())) consent.guardAction(ctx, principal, link.from(), tx);
        if (!created.contains(link.to())) consent.guardAction(ctx, principal, link.to(), tx);
    }
    private static Set<EntityKey> created(List<ActionEffectAccess> effects) {
        return effects.stream().filter(effect -> effect.kind() == ActionEffectAccess.Kind.CREATE_OBJECT).map(ActionEffectAccess::entity).collect(Collectors.toSet());
    }
}
