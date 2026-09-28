package org.openfoundry.foundation.spi;

public record StorageCapabilities(
        boolean transactions,
        boolean temporalQueries,
        boolean fullTextSearch,
        boolean recursiveTraversal,
        boolean bulkMutations,
        boolean jsonProperties,
        boolean replication,
        boolean transactionalCommandReceipts,
        boolean transactionalLineage,
        boolean transactionalIngestion,
        boolean relationshipAssertions) {
    public StorageCapabilities(boolean transactions, boolean temporalQueries, boolean fullTextSearch, boolean recursiveTraversal,
                               boolean bulkMutations, boolean jsonProperties, boolean replication, boolean transactionalCommandReceipts,
                               boolean transactionalLineage, boolean transactionalIngestion) {
        this(transactions, temporalQueries, fullTextSearch, recursiveTraversal, bulkMutations, jsonProperties, replication,
                transactionalCommandReceipts, transactionalLineage, transactionalIngestion, false);
    }

    public StorageCapabilities(boolean transactions, boolean temporalQueries, boolean fullTextSearch, boolean recursiveTraversal,
                               boolean bulkMutations, boolean jsonProperties, boolean replication, boolean transactionalCommandReceipts, boolean transactionalLineage) {
        this(transactions, temporalQueries, fullTextSearch, recursiveTraversal, bulkMutations, jsonProperties, replication, transactionalCommandReceipts, transactionalLineage, false);
    }

    public StorageCapabilities(boolean transactions, boolean temporalQueries, boolean fullTextSearch, boolean recursiveTraversal,
                               boolean bulkMutations, boolean jsonProperties, boolean replication, boolean transactionalCommandReceipts) {
        this(transactions, temporalQueries, fullTextSearch, recursiveTraversal, bulkMutations, jsonProperties, replication, transactionalCommandReceipts, false);
    }

    public StorageCapabilities(boolean transactions, boolean temporalQueries, boolean fullTextSearch,
                               boolean recursiveTraversal, boolean bulkMutations, boolean jsonProperties, boolean replication) {
        this(transactions, temporalQueries, fullTextSearch, recursiveTraversal, bulkMutations, jsonProperties, replication, false);
    }
}
