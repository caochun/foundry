package org.openfoundry.foundation.spi;

import java.util.Map;
import java.time.Instant;

/** Transaction boundary for object and relationship changes. */
public interface Transaction extends AutoCloseable {
    String transactionId();

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

    default CommandReceipt getCommandReceipt(String key) {
        throw new UnsupportedOperationException("Transactional command receipts are not supported");
    }

    default void putCommandReceipt(CommandReceipt receipt) {
        throw new UnsupportedOperationException("Transactional command receipts are not supported");
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
