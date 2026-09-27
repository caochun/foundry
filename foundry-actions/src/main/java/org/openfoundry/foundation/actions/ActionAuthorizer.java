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

    static ActionAuthorizer denyAll() {
        return (context, actor, definition, parameters) -> false;
    }
}
