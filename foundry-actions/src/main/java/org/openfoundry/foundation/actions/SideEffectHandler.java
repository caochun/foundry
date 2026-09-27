package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.time.Instant;
import java.util.Map;

/** Trusted wiring. Completion means the configured destination accepted the operation, not merely local enqueue. */
@FunctionalInterface
public interface SideEffectHandler {
    void execute(Invocation invocation);

    default boolean supports(String type) { return true; }

    record Invocation(String actionId, String action, String name, String type, Map<String, Object> config,
                      String idempotencyKey, int attempt, Instant occurredAt, String transactionId, RequestContext context) {
        public Invocation {
            config = PropertyValues.immutableMap(config);
        }
    }
}
