package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Resumable side effects and local compensation. No external callback runs inside a storage transaction. */
final class SideEffectRuntime {
    private final SideEffectHandler handler;
    private final ActionAuthorizer authorizer;
    private final Clock clock;
    private final Duration lease;
    private final ConsentEffects consent;

    SideEffectRuntime(SideEffectHandler handler, ActionAuthorizer authorizer, Clock clock, Duration lease, ConsentEffects consent) {
        this.consent = consent;
        this.handler = handler;
        this.authorizer = authorizer;
        this.clock = clock;
        if (lease == null || lease.compareTo(Duration.ofMillis(1)) < 0) throw new IllegalArgumentException("Invalid side-effect lease");
        this.lease = lease;
    }

    ActionResult resume(ActionManifest manifest, ActionTypeDefinition definition, String id,
                        RequestContext context, ActionActor actor, StorageProvider storage) {
        if (!java.util.Objects.equals(context.actorId(), actor.id())) throw new SecurityException("Action actor mismatch");
        for (int step = 0; step < 1000; step++) {
            var claim = claim(manifest, definition, id, context, actor, storage);
            if (claim.run.status().equals("COMPENSATING")) return compensate(manifest, definition, id, context, actor, storage);
            if (claim.invocation == null) return ActionContinuationState.result(claim.run);
            boolean success;
            try {
                handler.execute(claim.invocation);
                success = true;
            } catch (RuntimeException failure) {
                success = false;
            }
            var finished = finish(manifest, claim, success, context, storage);
            if (!success && finished.status().equals("PENDING") && java.util.Objects.equals(finished.state().get("cursor"), claim.run.state().get("cursor"))) {
                return ActionContinuationState.result(finished);
            }
            if (finished.status().equals("RUNNING") || finished.availableAt() == null
                    || finished.availableAt().isAfter(now())) return ActionContinuationState.result(finished);
        }
        try (var tx = storage.beginTransaction(context)) {
            return ActionContinuationState.result(required(tx, id));
        }
    }

    private Claim claim(ActionManifest manifest, ActionTypeDefinition definition, String id, RequestContext context,
                        ActionActor actor, StorageProvider storage) {
        return retry(storage, context, tx -> {
            var run = required(tx, id);
            authorize(manifest, definition, context, actor, tx, run);
            Instant now = now();
            if (run.availableAt() == null || run.availableAt().isAfter(now) || run.status().equals("COMPENSATING")) return new Claim(run, null, null);
            if (handler == null) throw new IllegalStateException("Side-effect handler is required");
            var state = ActionContinuationState.mutable(run);
            int cursor = ((Number) state.get("cursor")).intValue();
            var tasks = ActionContinuationState.maps(state.get("tasks"));
            var task = tasks.get(cursor);
            if (!handler.supports((String) task.get("type"))) throw new IllegalStateException("Side-effect handler is not configured");
            String token = UUID.randomUUID().toString();
            int attempt = Math.addExact(((Number) task.get("attempts")).intValue(), 1);
            task.put("attempts", attempt);
            task.put("state", "RUNNING");
            state.put("token", token);
            var claimed = save(tx, run, "RUNNING", now.plus(lease), state);
            var invocation = new SideEffectHandler.Invocation(run.id(), run.action(), (String) task.get("name"), (String) task.get("type"),
                    ActionContinuationState.map(task.get("config")), run.id() + "/" + task.get("name"), attempt,
                    Instant.parse((String) state.get("occurredAt")), (String) state.get("transactionId"), context);
            return new Claim(claimed, token, invocation);
        });
    }

    private ActionExecution finish(ActionManifest manifest, Claim claim, boolean success, RequestContext context, StorageProvider storage) {
        return retry(storage, context, tx -> {
            var run = required(tx, claim.run.id());
            Instant now = now();
            if (!run.status().equals("RUNNING") || !claim.token.equals(run.state().get("token"))
                    || run.availableAt() == null || !run.availableAt().isAfter(now)) return run;
            var state = ActionContinuationState.mutable(run);
            int cursor = ((Number) state.get("cursor")).intValue();
            var tasks = ActionContinuationState.maps(state.get("tasks"));
            var task = tasks.get(cursor);
            var declaration = manifest.sideEffects().get(cursor);
            state.remove("token");
            if (success) {
                task.put("state", "COMPLETED");
                state.put("cursor", ++cursor);
            } else {
                int attempts = ((Number) task.get("attempts")).intValue();
                if (manifest.onSideEffectFailure() == ActionManifest.RollbackPolicy.RETRY_INDEFINITELY || attempts < declaration.retries()) {
                    task.put("state", "PENDING");
                    Duration delay = declaration.retryDelay().multipliedBy(1L << Math.min(attempts - 1, 8));
                    Duration ceiling = declaration.retryDelay().compareTo(Duration.ofMinutes(5)) > 0 ? declaration.retryDelay() : Duration.ofMinutes(5);
                    if (delay.compareTo(ceiling) > 0) delay = ceiling;
                    return save(tx, run, "PENDING", now.plus(delay), state);
                }
                task.put("state", "FAILED");
                var warnings = new ArrayList<>(ActionContinuationState.maps(state.get("warnings")));
                warnings.add(Map.of("code", "SIDE_EFFECT_FAILURE", "task", task.get("name")));
                state.put("warnings", warnings);
                if (manifest.onSideEffectFailure() == ActionManifest.RollbackPolicy.ROLLBACK_ALL) {
                    return save(tx, run, "COMPENSATING", now, state);
                }
                state.put("cursor", ++cursor);
            }
            if (cursor == tasks.size()) {
                String status = ActionContinuationState.maps(state.get("warnings")).isEmpty() ? "COMPLETED" : "COMPLETED_WITH_WARNINGS";
                return terminal(tx, run, status, state, context);
            }
            return save(tx, run, "PENDING", now, state);
        });
    }

    private ActionResult compensate(ActionManifest manifest, ActionTypeDefinition definition, String id, RequestContext context,
                                     ActionActor actor, StorageProvider storage) {
        long[] attemptedVersion = {-1};
        try {
            var completed = retry(storage, context, tx -> {
                var run = required(tx, id);
                authorize(manifest, definition, context, actor, tx, run);
                if (!run.status().equals("COMPENSATING")) return run;
                attemptedVersion[0] = run.version();
                var state = ActionContinuationState.mutable(run);
                if (ConsentEffects.present(manifest)) consent.compensate(manifest, state.get("consent"), id, context, tx);
                var journal = ActionContinuationState.maps(state.get("journal"));
                var checked = new HashSet<EntityKey>();
                for (int i = journal.size() - 1; i >= 0; i--) {
                    var item = journal.get(i);
                    var key = new EntityKey((String) item.get("type"), (String) item.get("id"));
                    String kind = (String) item.get("kind");
                    boolean link = kind.endsWith("LINK");
                    var edge = link ? tx.getLink(key.type(), key.id()) : null;
                    var object = link ? null : tx.getObject(key.type(), key.id());
                    long currentVersion = link ? (edge == null ? -1 : edge.version()) : (object == null ? -1 : object.version());
                    if (checked.add(key) && currentVersion != ((Number) item.get("version")).longValue()) {
                        throw new IllegalStateException("Compensation version conflict");
                    }
                    switch (kind) {
                        case "UPDATE_OBJECT" -> tx.restoreObjectProperties(key.type(), key.id(), ActionContinuationState.map(item.get("before")), currentVersion);
                        case "CREATE_OBJECT" -> {
                            if (!tx.connectedLinks(key).isEmpty()) throw new IllegalStateException("Compensation object has active relationships");
                            tx.deleteObject(key.type(), key.id(), currentVersion);
                        }
                        case "CREATE_LINK" -> tx.deleteLink(key.type(), key.id(), currentVersion);
                        case "DELETE_LINK" -> tx.restoreLink(key.type(), key.id(), currentVersion);
                        default -> throw new IllegalStateException("Unknown compensation entry");
                    }
                }
                return terminal(tx, run, "ROLLED_BACK", state, context);
            });
            return ActionContinuationState.result(completed);
        } catch (SecurityException | TransactionConflictException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.sql.SQLException) throw failure;
            }
            if (attemptedVersion[0] < 0) throw failure;
            var failed = retry(storage, context, tx -> {
                var run = required(tx, id);
                authorize(manifest, definition, context, actor, tx, run);
                if (!run.status().equals("COMPENSATING") || run.version() != attemptedVersion[0]) return run;
                var state = ActionContinuationState.mutable(run);
                var errors = new ArrayList<>(ActionContinuationState.maps(state.get("warnings")));
                errors.add(Map.of("code", "COMPENSATION_FAILED", "task", ""));
                state.put("warnings", errors);
                return terminal(tx, run, "COMPENSATION_FAILED", state, context);
            });
            return ActionContinuationState.result(failed);
        }
    }

    private void authorize(ActionManifest manifest, ActionTypeDefinition definition, RequestContext context, ActionActor actor,
                           Transaction transaction, ActionExecution run) {
        if (!run.action().equals(manifest.action()) || !ActionFingerprint.hash(List.of(manifest, definition)).equals(run.state().get("configuration"))) {
            throw new IllegalArgumentException("Continuation configuration changed");
        }
        var parameters = ActionContinuationState.parameters(run.state(), transaction);
        if (ConsentEffects.present(manifest)) {
            if (consent == null) throw new IllegalStateException("Consent continuation store is not configured");
            consent.authorizeJournal(manifest, run.state().get("consent"), context, actor, definition, parameters, authorizer, transaction);
        }
        if (!authorizer.allowed(context, actor, definition, parameters, transaction)
                || !authorizer.allowedReplay(context, actor, definition, parameters, ActionEffectAccess.decode(run.state().get("journal")), transaction)) {
            throw new SecurityException("Continuation denied");
        }
    }

    private ActionExecution terminal(Transaction tx, ActionExecution run, String status, Map<String, Object> state, RequestContext context) {
        var saved = save(tx, run, status, null, state);
        var detail = Map.<String, Object>of("action", run.action(), "actionId", run.id(), "status", status, "errors", state.get("warnings"));
        tx.appendAudit(new AuditEntry("audit_final_" + run.id(), now(), context.tenantId(), context.actorId(), "action_completion",
                null, null, run.action(), tx.transactionId(), status, detail));
        tx.enqueueOutbox(new OutboxEntry("event_final_" + run.id(), context.tenantId(), "openfoundry.action." + (status.startsWith("COMPLETED") ? "completed" : "failed"),
                run.action() + "/" + run.id(), now(), tx.transactionId(), detail));
        return saved;
    }

    private static ActionExecution save(Transaction tx, ActionExecution run, String status, Instant availableAt, Map<String, Object> state) {
        var updated = new ActionExecution(run.id(), run.actorId(), run.action(), run.version() + 1, status, availableAt, state);
        tx.putActionExecution(updated, run.version());
        return updated;
    }

    private static ActionExecution required(Transaction transaction, String id) {
        var run = transaction.getActionExecution(id);
        if (run == null) throw new IllegalArgumentException("Action continuation does not exist");
        ActionContinuationState.result(run); // Reject unknown persisted formats/statuses before taking any action.
        boolean terminal = java.util.Set.of("COMPLETED", "COMPLETED_WITH_WARNINGS", "ROLLED_BACK", "COMPENSATION_FAILED").contains(run.status());
        if (terminal != (run.availableAt() == null)) throw new IllegalStateException("Invalid continuation schedule");
        return run;
    }

    private Instant now() { return clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS); }

    private static <T> T retry(StorageProvider storage, RequestContext context, java.util.function.Function<Transaction, T> work) {
        for (int attempt = 0; attempt < 8; attempt++) {
            try (var tx = storage.beginTransaction(context)) {
                tx.acquireWrite();
                T result = work.apply(tx);
                tx.commit();
                return result;
            } catch (TransactionConflictException conflict) {
                if (attempt == 7) throw conflict;
            }
        }
        throw new IllegalStateException("Continuation retry limit reached");
    }

    private record Claim(ActionExecution run, String token, SideEffectHandler.Invocation invocation) {}
}
