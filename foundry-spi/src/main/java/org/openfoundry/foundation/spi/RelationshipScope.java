package org.openfoundry.foundation.spi;

import java.util.List;
import java.util.Objects;

/** A membership authority boundary, independent of the identity of any one edge. */
public record RelationshipScope(EntityKey endpoint, String linkType, StorageProvider.Direction direction) {
    public RelationshipScope {
        Objects.requireNonNull(endpoint);
        Objects.requireNonNull(direction);
        if (linkType == null || linkType.isBlank()) throw new IllegalArgumentException("Relationship type is required");
    }
    public String key() { return LineageValues.hash(true, List.of(endpoint.type(), endpoint.id(), linkType, direction.name())); }
}
