package org.openfoundry.foundation.spi;

import java.time.Instant;
import java.util.Objects;

/** Ordered membership mutations/observations. Current members remain first-class link records. */
public record RelationshipAssertion(RelationshipScope scope, long revision, EntityOperation operation, String linkId,
                                     Instant recordedAt, String transactionId, String actorId, MutationSource source) {
    public RelationshipAssertion {
        Objects.requireNonNull(scope);
        if (revision < 1) throw new IllegalArgumentException("Invalid relationship scope revision");
        if (operation == EntityOperation.UPDATED) throw new IllegalArgumentException("Property updates do not change membership authority");
        if ((operation == null) != (linkId == null)) throw new IllegalArgumentException("Membership observations do not name a changed edge");
        Objects.requireNonNull(recordedAt);
        Objects.requireNonNull(transactionId);
        Objects.requireNonNull(source);
    }
}
