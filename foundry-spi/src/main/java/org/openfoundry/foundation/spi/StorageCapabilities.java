package org.openfoundry.foundation.spi;

public record StorageCapabilities(
        boolean transactions,
        boolean temporalQueries,
        boolean fullTextSearch,
        boolean recursiveTraversal,
        boolean bulkMutations,
        boolean jsonProperties,
        boolean replication) {
}
