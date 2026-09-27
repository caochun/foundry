package org.openfoundry.foundation.spi;

public record StorageCapabilities(
        boolean transactions,
        boolean temporalQueries,
        boolean fullTextSearch,
        boolean recursiveTraversal,
        boolean bulkMutations,
        boolean jsonProperties,
        boolean replication,
        boolean transactionalCommandReceipts) {
    public StorageCapabilities(boolean transactions, boolean temporalQueries, boolean fullTextSearch,
                               boolean recursiveTraversal, boolean bulkMutations, boolean jsonProperties, boolean replication) {
        this(transactions, temporalQueries, fullTextSearch, recursiveTraversal, bulkMutations, jsonProperties, replication, false);
    }
}
