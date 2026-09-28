package org.openfoundry.foundation.security;

import org.openfoundry.foundation.spi.*;
import java.util.*;

/** Purpose-neutral consent policy; exemptions require an explicit deployment configuration. */
public final class ConsentService {
    private final ConsentStore store;
    private final AuthorizationService authorization;
    private final ConsentConfiguration configuration;

    public ConsentService(ConsentStore store, AuthorizationService authorization, ConsentConfiguration configuration) {
        this.store = Objects.requireNonNull(store);
        this.authorization = Objects.requireNonNull(authorization);
        this.configuration = Objects.requireNonNull(configuration);
        store.initialize();
    }
    public ConsentConfiguration configuration() { return configuration; }
    public boolean applies(EntityKey subject) { return configuration.subjectTypes().contains(subject.type()); }

    public ConsentDecision check(RequestContext context, SecurityPrincipal principal, EntityKey subject, String purpose) {
        return check(context, principal, subject, purpose, null);
    }
    public ConsentDecision check(RequestContext context, SecurityPrincipal principal, EntityKey subject, String purpose, Transaction transaction) {
        identity(context, principal);
        subject(subject);
        ConsentConfiguration.text(purpose);
        var snapshot = transaction == null ? store.snapshot(context, subject) : store.snapshot(context, subject, transaction);
        var exemption = configuration.exemption();
        if (exemption != null && purpose.equals(exemption.purpose()) && !snapshot.optedOut()
                && authorization.check(context, principal, exemption.relation(), subject)) {
            return new ConsentDecision(true, purpose, ConsentDecision.Basis.LEGITIMATE_INTEREST, snapshot.revision());
        }
        for (int index = snapshot.records().size() - 1; index >= 0; index--) {
            var record = snapshot.records().get(index);
            if (record.purpose().equals(purpose)) return new ConsentDecision(record.decision() == ConsentRecord.Decision.GRANT,
                    purpose, ConsentDecision.Basis.EXPLICIT_CONSENT, snapshot.revision());
        }
        return new ConsentDecision(false, purpose, ConsentDecision.Basis.EXPLICIT_CONSENT, snapshot.revision());
    }
    public Map<EntityKey, ConsentDecision> checkBatch(RequestContext context, SecurityPrincipal principal, List<EntityKey> subjects, String purpose) {
        var decisions = new LinkedHashMap<EntityKey, ConsentDecision>();
        for (var subject : new LinkedHashSet<>(subjects)) decisions.put(subject, check(context, principal, subject, purpose));
        return Collections.unmodifiableMap(decisions);
    }
    public boolean allowed(RequestContext context, SecurityPrincipal principal, EntityKey subject) {
        return !applies(subject) || check(context, principal, subject, configuration.purpose()).allowed();
    }
    public void guardAction(RequestContext context, SecurityPrincipal principal, EntityKey subject, Transaction transaction) {
        if (applies(subject) && !check(context, principal, subject, configuration.purpose(), transaction).allowed()) {
            throw new ConsentDeniedException(subject, configuration.purpose());
        }
    }
    public void auditActionDenial(RequestContext context, ConsentDeniedException denial, String action) {
        store.audit(context, denial.subject(), denial.purpose(), "ACTION", "DENIED", Map.of("action", action, "code", "CONSENT_DENIED"));
    }

    public ConsentRecord record(RequestContext context, SecurityPrincipal principal, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence) {
        identity(context, principal); subject(subject); purpose(purpose);
        requireRecorder(context, principal, subject, purpose, "RECORD");
        return store.record(context, subject, purpose, decision, evidence);
    }
    public ConsentRecord revoke(RequestContext context, SecurityPrincipal principal, EntityKey subject, String purpose, String reason) {
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Revocation reason is required");
        return record(context, principal, subject, purpose, ConsentRecord.Decision.DENY, reason);
    }
    public void setOptOut(RequestContext context, SecurityPrincipal principal, EntityKey subject, boolean optedOut, String reason) {
        identity(context, principal); subject(subject);
        requireRecorder(context, principal, subject, null, "OPT_OUT");
        store.setOptOut(context, subject, optedOut, reason);
    }
    public ConsentSnapshot records(RequestContext context, SecurityPrincipal principal, EntityKey subject) {
        identity(context, principal); subject(subject);
        requireRecorder(context, principal, subject, null, "READ_RECORDS");
        return store.snapshot(context, subject);
    }
    public List<ConsentAudit> auditHistory(RequestContext context, SecurityPrincipal principal, EntityKey subject) {
        identity(context, principal); subject(subject);
        requireRecorder(context, principal, subject, null, "READ_AUDIT");
        return store.auditHistory(context, subject);
    }
    private void requireRecorder(RequestContext context, SecurityPrincipal principal, EntityKey subject, String purpose, String operation) {
        if (principal.roles().stream().noneMatch(configuration.recorderRoles()::contains)) {
            store.audit(context, subject, purpose, operation, "DENIED", Map.of("reason", "Recorder role required"));
            throw new SecurityException("A consent recorder role is required");
        }
    }
    private void purpose(String purpose) {
        ConsentConfiguration.text(purpose);
        if (!configuration.recordablePurposes().contains(purpose)) throw new IllegalArgumentException("Consent purpose is not configured");
    }
    private void subject(EntityKey subject) {
        if (subject == null || !applies(subject)) throw new IllegalArgumentException("Consent subject type is not configured");
    }
    private static void identity(RequestContext context, SecurityPrincipal principal) {
        if (principal == null || !principal.id().equals(context.actorId()) || !principal.tenantId().equals(context.tenantId())) {
            throw new SecurityException("Authenticated identity must match consent context");
        }
    }
}
