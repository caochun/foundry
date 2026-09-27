package org.openfoundry.foundation.spi;

import org.openfoundry.foundation.spi.schema.PropertyValues;
import java.util.Map;

/** Immutable result of a successful command, committed with its business changes. Tenant comes from the transaction. */
public record CommandReceipt(String key, String actorId, String action, String requestHash, Map<String, Object> result) {
    public CommandReceipt {
        if (key == null || !key.matches("[0-9a-f]{64}") || requestHash == null || !requestHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Command key and request hash must be SHA-256 digests");
        }
        if (actorId == null || actorId.isBlank() || action == null || action.isBlank()) {
            throw new IllegalArgumentException("Command receipt requires actor and action");
        }
        result = PropertyValues.immutableMap(result);
    }
}
