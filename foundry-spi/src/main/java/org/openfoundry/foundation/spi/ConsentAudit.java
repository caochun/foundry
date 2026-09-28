package org.openfoundry.foundation.spi;

import java.time.Instant;
import java.util.Map;

public record ConsentAudit(String id, EntityKey subject, String purpose, String operation, String outcome,
                           String actorId, String traceId, Instant recordedAt, Map<String, Object> detail) {
    public ConsentAudit { detail = org.openfoundry.foundation.spi.schema.PropertyValues.immutableMap(detail); }
}
