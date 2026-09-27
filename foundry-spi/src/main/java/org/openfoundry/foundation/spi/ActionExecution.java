package org.openfoundry.foundation.spi;

import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.time.Instant;
import java.util.Map;

/** Durable action continuation, committed alongside the original business effects. */
public record ActionExecution(String id, String actorId, String action, long version, String status,
                              Instant availableAt, Map<String, Object> state) {
    public ActionExecution {
        if (id == null || id.isBlank() || actorId == null || actorId.isBlank() || action == null || action.isBlank()
                || status == null || status.isBlank() || version < 1) throw new IllegalArgumentException("Invalid action execution");
        state = PropertyValues.immutableMap(state);
    }
}
