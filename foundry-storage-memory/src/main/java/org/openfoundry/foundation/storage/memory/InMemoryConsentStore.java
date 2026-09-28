package org.openfoundry.foundation.storage.memory;

import org.openfoundry.foundation.spi.*;
import java.time.Clock;
import java.util.*;

public final class InMemoryConsentStore implements ConsentStore {
    private final Clock clock;
    private final Map<Key, ConsentSnapshot> subjects = new HashMap<>();
    private final Map<Key, List<ConsentAudit>> audits = new HashMap<>();
    public InMemoryConsentStore() { this(Clock.systemUTC()); }
    public InMemoryConsentStore(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    @Override public synchronized ConsentSnapshot snapshot(RequestContext context, EntityKey subject) {
        return subjects.getOrDefault(new Key(context.tenantId(), subject), ConsentSnapshot.empty());
    }
    @Override public synchronized ConsentRecord record(RequestContext context, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence) {
        requireActor(context);
        var key = new Key(context.tenantId(), subject);
        var previous = snapshot(context, subject);
        var record = new ConsentRecord(subject, purpose, decision, Math.addExact(previous.revision(), 1), clock.instant(), context.actorId(), evidence);
        var records = new ArrayList<>(previous.records());
        records.add(record);
        var detail = new LinkedHashMap<String, Object>(); detail.put("decision", decision.name()); detail.put("evidence", evidence);
        var audit = new ConsentAudit(UUID.randomUUID().toString(), subject, purpose, "RECORD", "SUCCESS", context.actorId(), context.traceId(), record.recordedAt(), detail);
        subjects.put(key, new ConsentSnapshot(record.sequence(), previous.optedOut(), records));
        audits.computeIfAbsent(key, ignored -> new ArrayList<>()).add(audit);
        return record;
    }
    @Override public synchronized void setOptOut(RequestContext context, EntityKey subject, boolean optedOut, String reason) {
        requireActor(context);
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Opt-out reason is required");
        var previous = snapshot(context, subject);
        var key = new Key(context.tenantId(), subject);
        var next = new ConsentSnapshot(Math.addExact(previous.revision(), 1), optedOut, previous.records());
        var entry = new ConsentAudit(UUID.randomUUID().toString(), subject, null, "OPT_OUT", "SUCCESS", context.actorId(), context.traceId(), clock.instant(), Map.of("optedOut", optedOut, "reason", reason));
        subjects.put(key, next);
        audits.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
    }
    @Override public synchronized void audit(RequestContext context, EntityKey subject, String purpose, String operation, String outcome, Map<String, Object> detail) {
        requireActor(context);
        audits.computeIfAbsent(new Key(context.tenantId(), subject), ignored -> new ArrayList<>())
                .add(new ConsentAudit(UUID.randomUUID().toString(), subject, purpose, operation, outcome, context.actorId(), context.traceId(), clock.instant(), detail));
    }
    @Override public synchronized List<ConsentAudit> auditHistory(RequestContext context, EntityKey subject) {
        return List.copyOf(audits.getOrDefault(new Key(context.tenantId(), subject), List.of()));
    }
    private static void requireActor(RequestContext context) {
        if (context.actorId() == null) throw new SecurityException("Consent mutation requires an authenticated recorder");
    }
    private record Key(String tenant, EntityKey subject) {}
}
