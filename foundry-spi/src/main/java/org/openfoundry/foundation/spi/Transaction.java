package org.openfoundry.foundation.spi;

import java.util.Map;
import java.time.Instant;

/** Transaction boundary for object and relationship changes. */
public interface Transaction extends AutoCloseable {
    String transactionId();

    default RequestContext context() { throw new UnsupportedOperationException("Transaction identity is not exposed"); }

    default TransactionResource resource(Object key) { return null; }

    default <T extends TransactionResource> T enlist(Object key, java.util.function.Supplier<T> factory) {
        throw new UnsupportedOperationException("This transaction does not support in-memory commit participants");
    }

    /** Must be set before the first fact write; absent explicit source is recorded as DIRECT by supporting providers. */
    default void mutationSource(MutationSource source) {
        throw new UnsupportedOperationException("Transactional lineage is not supported");
    }

    default java.util.Map<String, FieldProvenance> latestLineage(EntityKey key) {
        throw new UnsupportedOperationException("Transactional lineage reads are not supported");
    }

    /** Record a newly accepted source observation of current values without inventing a new fact version. */
    default void recordProvenance(EntityKey key, long expectedVersion, java.util.Set<String> fields) {
        throw new UnsupportedOperationException("Transactional provenance observations are not supported");
    }

    default RelationshipAssertion relationshipAssertion(RelationshipScope scope) {
        throw new UnsupportedOperationException("Relationship membership provenance is not supported");
    }

    default void observeRelationships(RelationshipScope scope, long expectedRevision) {
        throw new UnsupportedOperationException("Relationship membership observations are not supported");
    }

    default IngestionReceipt getIngestionReceipt(String key) {
        throw new UnsupportedOperationException("Transactional ingestion receipts are not supported");
    }

    default void putIngestionReceipt(IngestionReceipt receipt) {
        throw new UnsupportedOperationException("Transactional ingestion receipts are not supported");
    }

    default IngestionCheckpoint getIngestionCheckpoint(String key) {
        throw new UnsupportedOperationException("Transactional ingestion checkpoints are not supported");
    }

    default void putIngestionCheckpoint(IngestionCheckpoint checkpoint, long expectedVersion) {
        throw new UnsupportedOperationException("Transactional ingestion checkpoints are not supported");
    }

    default ObjectRecord restoreObject(String type, String id, Map<String, Object> properties, long expectedVersion) {
        throw new UnsupportedOperationException("Object restoration is not supported");
    }

    ObjectRecord createObject(String type, String id, Map<String, Object> properties);

    ObjectRecord updateObject(String type, String id, Map<String, Object> properties,
                              long expectedVersion);

    void deleteObject(String type, String id, long expectedVersion);

    LinkRecord createLink(String type, String id, EntityKey from, EntityKey to,
                          Map<String, Object> properties);

    LinkRecord updateLink(String type, String id, Map<String, Object> properties,
                          long expectedVersion);

    void deleteLink(String type, String id, long expectedVersion);

    default ObjectRecord createObject(String type, String id, Map<String, Object> properties, Instant effectiveAt) {
        throw new UnsupportedOperationException("Explicit effective time is not supported by this provider");
    }

    default ObjectRecord updateObject(String type, String id, Map<String, Object> properties, long expectedVersion, Instant effectiveAt) {
        throw new UnsupportedOperationException("Explicit effective time is not supported by this provider");
    }

    default void deleteObject(String type, String id, long expectedVersion, Instant effectiveAt) {
        throw new UnsupportedOperationException("Explicit effective time is not supported by this provider");
    }

    default LinkRecord createLink(String type, String id, EntityKey from, EntityKey to, Map<String, Object> properties, Instant effectiveAt) {
        throw new UnsupportedOperationException("Explicit effective time is not supported by this provider");
    }

    default LinkRecord updateLink(String type, String id, Map<String, Object> properties, long expectedVersion, Instant effectiveAt) {
        throw new UnsupportedOperationException("Explicit effective time is not supported by this provider");
    }

    default void deleteLink(String type, String id, long expectedVersion, Instant effectiveAt) {
        throw new UnsupportedOperationException("Explicit effective time is not supported by this provider");
    }

    /** Establish the provider's write consistency boundary before evaluating a governed command. */
    default void acquireWrite() {
        throw new UnsupportedOperationException("Transactional command execution is not supported");
    }

    default ObjectRecord getObject(String type, String id) {
        throw new UnsupportedOperationException("Transactional reads are not supported");
    }

    default LinkRecord getLink(String type, String id) {
        throw new UnsupportedOperationException("Transactional reads are not supported");
    }

    /** Active links in this transaction, including preceding writes. At least one endpoint is required. */
    default java.util.List<LinkRecord> findLinks(String type, EntityKey from, EntityKey to) {
        throw new UnsupportedOperationException("Transactional relationship selection is not supported");
    }

    /** Current relationship assertions, optionally including terminated identities; not a temporal snapshot query. */
    default java.util.List<LinkRecord> findLinks(String type, EntityKey from, EntityKey to, boolean includeDeleted) {
        if (includeDeleted) throw new UnsupportedOperationException("Transactional relationship history selection is not supported");
        return findLinks(type, from, to);
    }

    default CommandReceipt getCommandReceipt(String key) {
        throw new UnsupportedOperationException("Transactional command receipts are not supported");
    }

    default void putCommandReceipt(CommandReceipt receipt) {
        throw new UnsupportedOperationException("Transactional command receipts are not supported");
    }

    default java.util.List<LinkRecord> connectedLinks(EntityKey endpoint) {
        throw new UnsupportedOperationException("Transactional incident relationship queries are not supported");
    }

    default ActionExecution getActionExecution(String id) {
        throw new UnsupportedOperationException("Durable action continuations are not supported");
    }

    default void putActionExecution(ActionExecution execution, long expectedVersion) {
        throw new UnsupportedOperationException("Durable action continuations are not supported");
    }

    /** Engine compensation: replace mutable values without backdating or bypassing schema constraints. */
    default ObjectRecord restoreObjectProperties(String type, String id, Map<String, Object> properties, long expectedVersion) {
        throw new UnsupportedOperationException("Compensation is not supported");
    }

    /** Engine compensation: append a new active assertion for the same previously terminated relationship. */
    default LinkRecord restoreLink(String type, String id, long expectedVersion) {
        throw new UnsupportedOperationException("Compensation is not supported");
    }

    void appendAudit(AuditEntry audit);

    void enqueueOutbox(OutboxEntry event);

    void commit();

    void rollback();

    @Override
    default void close() {
        rollback();
    }
}
