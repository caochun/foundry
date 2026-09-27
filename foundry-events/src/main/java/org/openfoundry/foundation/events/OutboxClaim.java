package org.openfoundry.foundation.events;

import java.time.Instant;
import java.util.Objects;

/** A fenced, expiring delivery claim. The token must accompany every state change. */
public record OutboxClaim(OutboxEvent event, String token, int attempt, Instant leaseUntil) {
    public OutboxClaim {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        if (token == null || token.isBlank() || attempt < 1) throw new IllegalArgumentException("Invalid delivery claim");
    }
}
