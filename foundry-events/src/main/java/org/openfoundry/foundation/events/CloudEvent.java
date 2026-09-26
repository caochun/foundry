package org.openfoundry.foundation.events;

import java.time.Instant;
import java.util.Map;

public record CloudEvent(String specVersion, String id, String source, String type,
                         String subject, Instant time, String tenantId,
                         String transactionId, Map<String, Object> data) {
    public CloudEvent {
        if (!"1.0".equals(specVersion)) throw new IllegalArgumentException("only CloudEvents 1.0 are supported");
        data = Map.copyOf(data);
    }
}
