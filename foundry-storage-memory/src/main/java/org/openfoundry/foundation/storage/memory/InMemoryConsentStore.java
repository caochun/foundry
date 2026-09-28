package org.openfoundry.foundation.storage.memory;

import org.openfoundry.foundation.spi.*;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

public final class InMemoryConsentStore implements ConsentStore {
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private final ThreadLocal<Mutation> active = new ThreadLocal<>();
    private State state = new State();
    public InMemoryConsentStore() { this(Clock.systemUTC()); }
    public InMemoryConsentStore(Clock clock) { this.clock = Objects.requireNonNull(clock); }

    @Override public ConsentSnapshot snapshot(RequestContext context, EntityKey subject) {
        return locked(() -> snapshot(state, context, subject));
    }
    @Override public ConsentSnapshot snapshot(RequestContext context, EntityKey subject, Transaction transaction) {
        if (transaction != null && !context.equals(transaction.context())) throw new SecurityException("Consent context must match the enclosing transaction");
        if (transaction != null && transaction.resource(this) instanceof Mutation mutation) return snapshot(mutation.working, context, subject);
        return snapshot(context, subject);
    }
    private ConsentSnapshot snapshot(State source, RequestContext context, EntityKey subject) {
        return source.subjects.getOrDefault(new Key(context.tenantId(), subject), ConsentSnapshot.empty());
    }
    @Override public ConsentRecord record(RequestContext context, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence) {
        requireStandalone();
        return locked(() -> append(state, context, subject, purpose, decision, evidence));
    }
    @Override public void prepareTransaction(RequestContext context, EntityKey subject, Transaction transaction) { mutation(context, transaction); }
    @Override public ConsentRecord record(RequestContext context, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence, Transaction transaction) {
        return append(mutation(context, transaction).working, context, subject, purpose, decision, evidence);
    }
    @Override public ConsentRecord restore(RequestContext context, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence, long expectedRevision, Transaction transaction) {
        var mutation = mutation(context, transaction);
        if (snapshot(mutation.working, context, subject).revision() != expectedRevision) throw new IllegalStateException("Consent compensation version conflict");
        return append(mutation.working, context, subject, purpose, decision, evidence);
    }
    private ConsentRecord append(State target, RequestContext context, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence) {
        requireActor(context);
        var previous = snapshot(target, context, subject);
        var record = new ConsentRecord(subject, purpose, decision, Math.addExact(previous.revision(), 1), clock.instant(), context.actorId(), evidence);
        var records = new ArrayList<>(previous.records()); records.add(record);
        var detail = new LinkedHashMap<String, Object>(); detail.put("decision", decision.name()); detail.put("evidence", evidence);
        var audit = new ConsentAudit(UUID.randomUUID().toString(), subject, purpose, "RECORD", "SUCCESS", context.actorId(), context.traceId(), record.recordedAt(), detail);
        target.subjects.put(new Key(context.tenantId(), subject), new ConsentSnapshot(record.sequence(), previous.optedOut(), records));
        target.audits.computeIfAbsent(new Key(context.tenantId(), subject), ignored -> new ArrayList<>()).add(audit);
        return record;
    }
    @Override public void setOptOut(RequestContext context, EntityKey subject, boolean optedOut, String reason) {
        requireStandalone();
        locked(() -> {
            requireActor(context);
            if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Opt-out reason is required");
            var key = new Key(context.tenantId(), subject);
            var previous = snapshot(state, context, subject);
            var next = new ConsentSnapshot(Math.addExact(previous.revision(), 1), optedOut, previous.records());
            var entry = new ConsentAudit(UUID.randomUUID().toString(), subject, null, "OPT_OUT", "SUCCESS", context.actorId(), context.traceId(), clock.instant(), Map.of("optedOut", optedOut, "reason", reason));
            state.subjects.put(key, next);
            state.audits.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entry);
            return null;
        });
    }
    @Override public void audit(RequestContext context, EntityKey subject, String purpose, String operation, String outcome, Map<String, Object> detail) {
        requireStandalone();
        locked(() -> {
            requireActor(context);
            var entry = new ConsentAudit(UUID.randomUUID().toString(), subject, purpose, operation, outcome, context.actorId(), context.traceId(), clock.instant(), detail);
            state.audits.computeIfAbsent(new Key(context.tenantId(), subject), ignored -> new ArrayList<>()).add(entry);
            return null;
        });
    }
    @Override public List<ConsentAudit> auditHistory(RequestContext context, EntityKey subject) {
        return locked(() -> List.copyOf(state.audits.getOrDefault(new Key(context.tenantId(), subject), List.of())));
    }
    private Mutation mutation(RequestContext context, Transaction transaction) {
        if (!context.equals(transaction.context())) throw new SecurityException("Consent context must match the enclosing transaction");
        return transaction.enlist(this, Mutation::new);
    }
    private void requireStandalone() {
        if (active.get() != null) throw new IllegalStateException("Use the enlisted consent transaction; nested standalone writes are not allowed");
    }

    private <T> T locked(Supplier<T> work) {
        lock.lock();
        try { return work.get(); } finally { lock.unlock(); }
    }
    private final class Mutation implements TransactionResource {
        final State before;
        final State working;
        boolean published;
        Mutation() {
            requireStandalone();
            lock.lock();
            try { before = state; working = before.copy(); active.set(this); }
            catch (RuntimeException | Error failure) { lock.unlock(); throw failure; }
        }
        @Override public void prepare() { if (!lock.isHeldByCurrentThread()) throw new IllegalStateException("Consent transaction changed thread"); }
        @Override public void publish() { state = working; published = true; }
        @Override public void rollback() { if (published) state = before; }
        @Override public void close() { active.remove(); lock.unlock(); }
    }
    private static final class State {
        final Map<Key, ConsentSnapshot> subjects = new HashMap<>();
        final Map<Key, List<ConsentAudit>> audits = new HashMap<>();
        State copy() {
            var copy = new State(); copy.subjects.putAll(subjects);
            audits.forEach((key, values) -> copy.audits.put(key, new ArrayList<>(values)));
            return copy;
        }
    }
    private static void requireActor(RequestContext context) { if (context.actorId() == null) throw new SecurityException("Consent mutation requires an authenticated recorder"); }
    private record Key(String tenant, EntityKey subject) {}
}
