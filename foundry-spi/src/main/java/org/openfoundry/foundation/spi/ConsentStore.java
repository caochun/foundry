package org.openfoundry.foundation.spi;

import java.util.List;
import java.util.Map;

/** Trusted consent metadata store. Public recording permission belongs to the service boundary. */
public interface ConsentStore {
    default void initialize() {}
    ConsentSnapshot snapshot(RequestContext context, EntityKey subject);
    default ConsentSnapshot snapshot(RequestContext context, EntityKey subject, Transaction transaction) { return snapshot(context, subject); }
    ConsentRecord record(RequestContext context, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence);
    default void prepareTransaction(RequestContext context, EntityKey subject, Transaction transaction) {
        throw new UnsupportedOperationException("Transactional consent is not supported");
    }
    default ConsentRecord record(RequestContext context, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence, Transaction transaction) {
        throw new UnsupportedOperationException("Transactional consent recording is not supported");
    }
    default ConsentRecord restore(RequestContext context, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence, long expectedRevision, Transaction transaction) {
        throw new UnsupportedOperationException("Transactional consent restoration is not supported");
    }
    void setOptOut(RequestContext context, EntityKey subject, boolean optedOut, String reason);
    void audit(RequestContext context, EntityKey subject, String purpose, String operation, String outcome, Map<String, Object> detail);
    List<ConsentAudit> auditHistory(RequestContext context, EntityKey subject);
}
