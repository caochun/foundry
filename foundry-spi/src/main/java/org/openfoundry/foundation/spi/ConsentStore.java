package org.openfoundry.foundation.spi;

import java.util.List;
import java.util.Map;

/** Trusted consent metadata store. Public recording permission belongs to the service boundary. */
public interface ConsentStore {
    default void initialize() {}
    ConsentSnapshot snapshot(RequestContext context, EntityKey subject);
    default ConsentSnapshot snapshot(RequestContext context, EntityKey subject, Transaction transaction) { return snapshot(context, subject); }
    ConsentRecord record(RequestContext context, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence);
    void setOptOut(RequestContext context, EntityKey subject, boolean optedOut, String reason);
    void audit(RequestContext context, EntityKey subject, String purpose, String operation, String outcome, Map<String, Object> detail);
    List<ConsentAudit> auditHistory(RequestContext context, EntityKey subject);
}
