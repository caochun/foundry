package org.openfoundry.foundation.spi;

import java.util.Map;

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

    void appendAudit(AuditEntry audit);

    void enqueueOutbox(OutboxEntry event);

    void commit();

    void rollback();

    @Override
    default void close() {
        rollback();
    }
}
