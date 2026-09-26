package org.openfoundry.foundation.spi;

import java.util.List;
import java.util.Objects;

/** Result of a traversal evaluated at one temporal point. */
public record TraversalResult(List<EntityKey> nodes, List<HistorySnapshot> edges) {
    public TraversalResult {
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes must not be null"));
        edges = List.copyOf(Objects.requireNonNull(edges, "edges must not be null"));
    }
}
