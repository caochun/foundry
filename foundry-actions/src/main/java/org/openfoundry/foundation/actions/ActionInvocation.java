package org.openfoundry.foundation.actions;

import java.util.Map;

public record ActionInvocation(ActionManifest manifest, ActionActor actor,
                               Map<String, Object> parameters, String idempotencyKey) {
    public ActionInvocation {
        parameters = Map.copyOf(parameters);
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey must not be blank");
    }
}
