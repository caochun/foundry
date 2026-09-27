package org.openfoundry.foundation.actions;

import java.util.Map;

public record ActionInvocation(ActionManifest manifest, ActionActor actor,
                               Map<String, Object> parameters, String idempotencyKey, org.openfoundry.foundation.spi.schema.ActionTypeDefinition definition) {
    public ActionInvocation(ActionManifest manifest, ActionActor actor, Map<String, Object> parameters, String idempotencyKey) {
        this(manifest, actor, parameters, idempotencyKey, null);
    }

    public ActionInvocation {
        parameters = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(parameters));
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey must not be blank");
    }
}
