package org.openfoundry.foundation.spi;

import java.time.Instant;
import java.util.Objects;

/** Ordered consent evidence; sequence, rather than wall-clock order, determines the latest decision. */
public record ConsentRecord(EntityKey subject, String purpose, Decision decision, long sequence,
                            Instant recordedAt, String recordedBy, String evidence) {
    public enum Decision { GRANT, DENY }
    public ConsentRecord {
        Objects.requireNonNull(subject);
        if (purpose == null || purpose.isBlank() || purpose.length() > 255 || sequence < 1) throw new IllegalArgumentException("Invalid consent record");
        Objects.requireNonNull(decision);
        Objects.requireNonNull(recordedAt);
        if (recordedBy == null || recordedBy.isBlank()) throw new IllegalArgumentException("Consent recorder is required");
    }
}
