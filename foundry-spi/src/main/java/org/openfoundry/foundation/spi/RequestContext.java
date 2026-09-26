package org.openfoundry.foundation.spi;

import java.util.Objects;

/** Request-scoped identity and tenant context passed to every SPI operation. */
public record RequestContext(String tenantId, String actorId, String traceId) {
    public RequestContext {
        requireText(tenantId, "tenantId");
        actorId = normalize(actorId);
        traceId = normalize(traceId);
    }

    public static RequestContext system(String tenantId, String actorId) {
        return new RequestContext(tenantId, actorId, null);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
