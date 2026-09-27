package org.openfoundry.foundation.events;

import java.time.Instant;
import java.util.Map;

public record CloudEvent(String specVersion, String id, String source, String type,
                         String subject, Instant time, String tenantId,
                         String transactionId, Map<String, Object> data) {
    public CloudEvent {
        if (!"1.0".equals(specVersion)) throw new IllegalArgumentException("only CloudEvents 1.0 are supported");
        if (id == null || id.isBlank() || source == null || source.isBlank() || type == null || type.isBlank()
                || tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("CloudEvent identity and tenant are required");
        data = org.openfoundry.foundation.spi.schema.PropertyValues.immutableMap(data);
    }
}
