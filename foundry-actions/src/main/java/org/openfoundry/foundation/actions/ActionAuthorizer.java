package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;

import java.util.Map;

/** Trusted policy extension point. An absent policy never grants permission. */
@FunctionalInterface
public interface ActionAuthorizer {
    boolean allowed(RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                    Map<String, Object> parameters);

    default boolean allowed(RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                            Map<String, Object> parameters, org.openfoundry.foundation.spi.Transaction transaction) {
        return allowed(context, actor, definition, parameters);
    }

    /** Validate concrete effects and stored receipt targets, including targets absent from the input parameters. */
    default boolean allowedChanges(RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                                   Map<String, Object> parameters, java.util.List<org.openfoundry.foundation.spi.EntityKey> affected,
                                   org.openfoundry.foundation.spi.Transaction transaction) {
        return allowed(context, actor, definition, parameters, transaction);
    }

    default boolean allowedUpdate(RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                                  Map<String, Object> parameters, org.openfoundry.foundation.spi.EntityKey target,
                                  java.util.List<ActionEffectAccess> preceding, org.openfoundry.foundation.spi.Transaction transaction) {
        return allowed(context, actor, definition, parameters, transaction);
    }

    default boolean allowedSelection(RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                                     Map<String, Object> parameters, java.util.List<ActionEffectAccess> preceding,
                                     java.util.List<org.openfoundry.foundation.spi.LinkRecord> selected,
                                     org.openfoundry.foundation.spi.Transaction transaction) {
        return allowedChanges(context, actor, definition, parameters, selected.stream()
                .map(link -> new org.openfoundry.foundation.spi.EntityKey(link.type(), link.id())).toList(), transaction);
    }

    /** Called after effects but before commit. A denial rolls back every write. */
    default boolean allowedEffects(RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                                   Map<String, Object> parameters, java.util.List<ActionEffectAccess> effects,
                                   org.openfoundry.foundation.spi.Transaction transaction) {
        return allowed(context, actor, definition, parameters, transaction);
    }

    default boolean allowedReplay(RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                                  Map<String, Object> parameters, java.util.List<ActionEffectAccess> effects,
                                  org.openfoundry.foundation.spi.Transaction transaction) {
        return allowedChanges(context, actor, definition, parameters, effects.stream().map(ActionEffectAccess::entity).toList(), transaction);
    }

    static ActionAuthorizer denyAll() {
        return (context, actor, definition, parameters) -> false;
    }
}
