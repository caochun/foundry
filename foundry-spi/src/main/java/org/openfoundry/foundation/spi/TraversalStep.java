package org.openfoundry.foundation.spi;

import java.util.Objects;

/** One typed relationship hop in a temporal traversal. */
public record TraversalStep(String linkType, StorageProvider.Direction direction) {
    public TraversalStep {
        if (linkType == null || linkType.isBlank()) {
            throw new IllegalArgumentException("linkType must not be blank");
        }
        Objects.requireNonNull(direction, "direction must not be null");
    }
}
