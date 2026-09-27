package org.openfoundry.foundation.events;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

final class DeliveryTimes {
    private DeliveryTimes() {}

    static Instant instant(Instant value) {
        return Objects.requireNonNull(value, "time").truncatedTo(ChronoUnit.MICROS);
    }

    static Instant deadline(Instant now, Duration lease) {
        if (lease == null || lease.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException("Lease duration must be at least one millisecond");
        }
        return instant(now).plus(lease).truncatedTo(ChronoUnit.MICROS);
    }

    static void selection(String tenantId, int limit) {
        if (tenantId == null || tenantId.isBlank() || limit < 1 || limit > 10000) {
            throw new IllegalArgumentException("A tenant and limit between 1 and 10000 are required");
        }
    }
}
