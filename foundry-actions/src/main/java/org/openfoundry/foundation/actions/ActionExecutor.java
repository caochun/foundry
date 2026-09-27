package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.AuditEntry;
import org.openfoundry.foundation.spi.LinkRecord;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.OutboxEntry;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.Transaction;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Executes constrained Action effects in one Storage SPI transaction. */
public final class ActionExecutor {
    private final ExpressionEvaluator evaluator;
    private final IdempotencyStore idempotencyStore;
    private final ActionAuthorizer authorizer;
    private final SideEffectHandler sideEffects;
    private final java.time.Clock clock;
    private final java.time.Duration lease;
    private final org.openfoundry.foundation.spi.schema.OntologySchema parameterSchema;

    public ActionExecutor() {
        this(new CelExpressionEvaluator(), null);
    }

    public ActionExecutor(ExpressionEvaluator evaluator) {
        this(evaluator, null);
    }

    public ActionExecutor(ExpressionEvaluator evaluator, IdempotencyStore idempotencyStore) {
        this(evaluator, idempotencyStore, ActionAuthorizer.denyAll());
    }

    public ActionExecutor(ExpressionEvaluator evaluator, IdempotencyStore idempotencyStore, ActionAuthorizer authorizer) {
        this(evaluator, idempotencyStore, authorizer, null, java.time.Clock.systemUTC(), java.time.Duration.ofMinutes(1), null);
    }

    private ActionExecutor(ExpressionEvaluator evaluator, IdempotencyStore idempotencyStore, ActionAuthorizer authorizer,
                           SideEffectHandler sideEffects, java.time.Clock clock, java.time.Duration lease,
                           org.openfoundry.foundation.spi.schema.OntologySchema parameterSchema) {
        this.parameterSchema = parameterSchema;
        this.sideEffects = sideEffects;
        this.clock = java.util.Objects.requireNonNull(clock);
        this.lease = java.util.Objects.requireNonNull(lease);
        if (lease.compareTo(java.time.Duration.ofMillis(1)) < 0) throw new IllegalArgumentException("Invalid side-effect lease");
        this.evaluator = java.util.Objects.requireNonNull(evaluator);
        this.idempotencyStore = idempotencyStore;
        this.authorizer = java.util.Objects.requireNonNull(authorizer);
    }

    /** Only trusted application wiring may supply policies; no HTTP request can provide one. */
    public ActionExecutor withAuthorization(ActionAuthorizer policy) {
        return new ActionExecutor(evaluator, idempotencyStore, policy, sideEffects, clock, lease, parameterSchema);
    }

    public ActionExecutor withParameterSchema(org.openfoundry.foundation.spi.schema.OntologySchema schema) {
        org.openfoundry.foundation.spi.schema.PropertyValues.requireSchema(schema);
        return new ActionExecutor(evaluator, idempotencyStore, authorizer, sideEffects, clock, lease, schema);
    }

    public ActionExecutor withSideEffects(SideEffectHandler handler) {
        return withSideEffects(handler, clock, lease);
    }

    public ActionExecutor withSideEffects(SideEffectHandler handler, java.time.Clock clock, java.time.Duration lease) {
        return new ActionExecutor(evaluator, idempotencyStore, authorizer, java.util.Objects.requireNonNull(handler), clock, lease, parameterSchema);
    }

    public ActionResult resume(ActionManifest manifest, ActionTypeDefinition definition, String actionId,
                               RequestContext context, ActionActor actor, StorageProvider storage) {
        return new SideEffectRuntime(sideEffects, authorizer, clock, lease).resume(manifest, definition, actionId, context, actor, storage);
    }

    public List<ActionResult> resumePending(Map<String, ActionManifest> manifests, Map<String, ActionTypeDefinition> definitions,
                                            RequestContext context, ActionActor actor, int limit, StorageProvider storage) {
        if (!java.util.Objects.equals(context.actorId(), actor.id())) throw new SecurityException("Action actor mismatch");
        var results = new ArrayList<ActionResult>();
        for (var execution : storage.pendingActions(context, clock.instant(), limit)) {
            var manifest = manifests.get(execution.action());
            var definition = definitions.get(execution.action());
            if (manifest == null || definition == null) throw new IllegalStateException("Original continuation definition is not registered");
            results.add(resume(manifest, definition, execution.id(), context, actor, storage));
        }
        return List.copyOf(results);
    }

    public ActionResult execute(ActionManifest manifest, RequestContext context,
                                ActionActor actor, Map<String, Object> parameters,
                                StorageProvider storage) {
        return execute(manifest, null, context, actor, parameters, null, storage);
    }

    public ActionResult execute(ActionManifest manifest, ActionTypeDefinition definition,
                                RequestContext context, ActionActor actor,
                                Map<String, Object> parameters, StorageProvider storage) {
        return execute(manifest, definition, context, actor, parameters, null, storage);
    }

    public ActionResult execute(ActionManifest manifest, ActionTypeDefinition definition,
                                RequestContext context, ActionActor actor,
                                Map<String, Object> parameters, String idempotencyKey,
                                StorageProvider storage) {
        if (manifest.reversible()) throw new IllegalArgumentException("Reversible Actions are not supported yet");
        if (definition == null || definition.permission() == null || !manifest.action().equals(definition.name())
                || context.actorId() == null || !context.actorId().equals(actor.id())) {
            throw new SecurityException("Action requires a declared permission and matching authenticated actor");
        }
        if (!new ActionParameterValidator(parameterSchema).validate(definition, parameters).isEmpty()) {
            throw new IllegalArgumentException("Action parameters do not match the registered schema");
        }
        if (!storage.capabilities().transactionalCommandReceipts() && manifest.effects().stream()
                .anyMatch(effect -> effect instanceof ActionManifest.DeleteLink deletion && deletion.filter() != null)) {
            throw new UnsupportedOperationException("Filtered relationship deletion requires transactional command execution");
        }
        if (!manifest.sideEffects().isEmpty()) {
            if (!storage.capabilities().transactionalCommandReceipts()) throw new UnsupportedOperationException("Side effects require durable action continuations");
            if (sideEffects == null || manifest.sideEffects().stream().anyMatch(effect -> !sideEffects.supports(effect.type()))) {
                throw new IllegalStateException("No configured handler for this side effect");
            }
        }
        validateTenantReferences(context, parameters.values());
        if (!authorizer.allowed(context, actor, definition, parameters)) throw new SecurityException("Action denied");
        if (idempotencyKey != null) {
            if (idempotencyKey.isBlank()) throw new IllegalArgumentException("Idempotency key must not be blank");
            if (idempotencyKey.length() > 512) throw new IllegalArgumentException("Idempotency key too long");
            String scoped = ActionFingerprint.hash(List.of(context.tenantId(), actor.id(), manifest.action(), idempotencyKey));
            String fingerprint = ActionFingerprint.hash(List.of(manifest, definition, parameters));
            if (storage.capabilities().transactionalCommandReceipts()) {
                return executeTransactional(manifest, definition, context, actor, parameters, storage, scoped, fingerprint);
            }
            if (idempotencyStore == null) throw new IllegalStateException("An idempotency store is required for keyed execution");
            return idempotencyStore.execute(scoped, fingerprint, () -> executeEffects(manifest, definition, context, actor, parameters, storage));
        }
        if (storage.capabilities().transactionalCommandReceipts()) {
            return executeTransactional(manifest, definition, context, actor, parameters, storage, null, null);
        }
        return executeEffects(manifest, definition, context, actor, parameters, storage);
    }

    private ActionResult executeTransactional(ActionManifest manifest, ActionTypeDefinition definition, RequestContext context,
                                               ActionActor actor, Map<String, Object> parameters, StorageProvider storage,
                                               String key, String fingerprint) {
        for (int attempt = 0; attempt < 8; attempt++) {
            ActionResult committed;
            try (Transaction transaction = storage.beginTransaction(context)) {
                transaction.acquireWrite();
                var current = new java.util.LinkedHashMap<String, Object>();
                parameters.forEach((name, value) -> current.put(name, refresh(transaction, value)));
                var resolved = java.util.Collections.unmodifiableMap(current);
                if (!authorizer.allowed(context, actor, definition, resolved, transaction)) throw new SecurityException("Action denied");
                var receipt = key == null ? null : transaction.getCommandReceipt(key);
                if (receipt != null) {
                    if (!receipt.action().equals(manifest.action()) || !receipt.requestHash().equals(fingerprint)) {
                        throw new IllegalArgumentException("Idempotency key belongs to a different request or configuration");
                    }
                    Object format = receipt.result().get("format");
                    List<ActionEffectAccess> access = null;
                    if ((format instanceof Integer || format instanceof Long) && ((Number) format).longValue() == 2) {
                        var execution = transaction.getActionExecution((String) receipt.result().get("actionId"));
                        if (execution == null) throw new IllegalStateException("Committed continuation is missing");
                        committed = ActionContinuationState.result(execution);
                        access = ActionEffectAccess.decode(execution.state().get("journal"));
                    } else {
                        committed = decodeResult(receipt.result());
                        if (receipt.result().containsKey("access")) access = ActionEffectAccess.decode(receipt.result().get("access"));
                    }
                    if (access == null) requireChanges(context, actor, definition, resolved, committed.affected(), transaction);
                    else if (!authorizer.allowedReplay(context, actor, definition, resolved, access, transaction)) throw new SecurityException("Action replay denied");
                } else {
                    parameters.forEach((name, value) -> checkReferenceVersions(value, resolved.get(name)));
                    var access = new ArrayList<ActionEffectAccess>();
                    committed = applyEffects(manifest, definition, context, actor, resolved, storage, transaction, true, access);
                    if (!committed.success()) return committed;
                    if (key != null) {
                        Map<String, Object> result = manifest.sideEffects().isEmpty()
                                ? Map.of("format", 1, "success", true, "actionId", committed.actionId(), "affected", committed.affected().stream()
                                        .map(entity -> Map.of("type", entity.type(), "id", entity.id())).toList(),
                                        "access", access.stream().map(ActionEffectAccess::encode).toList())
                                : Map.of("format", 2, "actionId", committed.actionId());
                        transaction.putCommandReceipt(new org.openfoundry.foundation.spi.CommandReceipt(key, actor.id(), manifest.action(), fingerprint, result));
                    }
                    transaction.commit();
                }
            } catch (org.openfoundry.foundation.spi.TransactionConflictException conflict) {
                if (attempt == 7) throw conflict;
                continue;
            }
            // The original transaction and connection must be closed before any external callback.
            return manifest.sideEffects().isEmpty() ? committed : resume(manifest, definition, committed.actionId(), context, actor, storage);
        }
        throw new IllegalStateException("Transaction retry limit reached");
    }

    private static Object refresh(Transaction transaction, Object value) {
        if (value instanceof ObjectRecord original) {
            var current = transaction.getObject(original.type(), original.id());
            if (current == null || current.isDeleted()) throw new SecurityException("Object reference is no longer available");
            return current;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> refresh(transaction, item)).toList();
        return value;
    }

    private static void checkReferenceVersions(Object original, Object current) {
        if (original instanceof ObjectRecord before && current instanceof ObjectRecord after && before.version() != after.version()) {
            throw new IllegalStateException("Action reference version changed; resolve the request again");
        }
        if (original instanceof List<?> before && current instanceof List<?> after) {
            for (int i = 0; i < before.size(); i++) checkReferenceVersions(before.get(i), after.get(i));
        }
    }

    private static ActionResult decodeResult(Map<String, Object> stored) {
        if (!(stored.get("format") instanceof Integer || stored.get("format") instanceof Long)
                || ((Number) stored.get("format")).longValue() != 1
                || !Boolean.TRUE.equals(stored.get("success")) || !(stored.get("actionId") instanceof String actionId) || actionId.isBlank()
                || !(stored.get("affected") instanceof List<?> rows)) throw new IllegalStateException("Unsupported command receipt result");
        var affected = new ArrayList<EntityKey>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> fields) || !(fields.get("type") instanceof String type) || !(fields.get("id") instanceof String id)) {
                throw new IllegalStateException("Invalid command receipt entity reference");
            }
            affected.add(new EntityKey(type, id));
        }
        var changes = stored.containsKey("access") ? ActionResult.changes(affected, ActionEffectAccess.decode(stored.get("access")))
                : affected.stream().map(key -> new ActionResult.Change(key.type(), key.id(), ActionResult.ChangeType.UNKNOWN)).toList();
        return new ActionResult(true, actionId, affected, "COMPLETED", List.of(), changes);
    }

    private ActionResult executeEffects(ActionManifest manifest, ActionTypeDefinition definition, RequestContext context, ActionActor actor,
                                        Map<String, Object> parameters, StorageProvider storage) {
        try (Transaction transaction = storage.beginTransaction(context)) {
            ActionResult result = applyEffects(manifest, definition, context, actor, parameters, storage, transaction, false, new ArrayList<>());
            if (result.success()) transaction.commit();
            return result;
        }
    }

    private ActionResult applyEffects(ActionManifest manifest, ActionTypeDefinition definition, RequestContext context, ActionActor actor,
                                      Map<String, Object> parameters, StorageProvider storage, Transaction transaction,
                                      boolean transactional, List<ActionEffectAccess> access) {
        String actionId = "act_" + UUID.randomUUID();
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        var expressions = new ActionValues(parameters, actor, now);
        for (ActionManifest.Precondition precondition : manifest.preconditions()) {
            if (!evaluator.evaluate(precondition.expression(), parameters, actor, now)) {
                return new ActionResult(false, actionId, List.of(), "REJECTED", List.of(
                        new ActionResult.Failure("PRECONDITION_FAILED", "", precondition.error(), null)));
            }
        }

        List<EntityKey> affected = new ArrayList<>();
        var journal = new ArrayList<Map<String, Object>>();
        for (ActionManifest.ActionEffect effect : manifest.effects()) {
            if (effect instanceof ActionManifest.UpdateObject update) {
                ObjectRecord target = expressions.object(update.target());
                if (transactional) target = transaction.getObject(target.type(), target.id());
                if (!authorizer.allowedUpdate(context, actor, definition, parameters, target.key(), ActionEffectAccess.decode(journal), transaction)) {
                    throw new SecurityException("Action update target denied");
                }
                Map<String, Object> values = expressions.properties(update.set());
                var updated = transaction.updateObject(target.type(), target.id(), values, target.version());
                journal.add(ActionContinuationState.undo("UPDATE_OBJECT", target.key(), updated.version(), target.properties()));
                affected.add(target.key());
            } else if (effect instanceof ActionManifest.CreateObject create) {
                Map<String, Object> values = expressions.properties(create.properties());
                String id = create.target() == null ? create.objectType() + "-" + UUID.randomUUID() : resolveId(create.target(), parameters);
                ObjectRecord created = transaction.createObject(create.objectType(), id, values);
                expressions.created(created);
                journal.add(ActionContinuationState.undo("CREATE_OBJECT", created.key(), created.version(), Map.of()));
                affected.add(created.key());
            } else if (effect instanceof ActionManifest.CreateLink create) {
                EntityKey from = expressions.entity(create.from());
                EntityKey to = expressions.entity(create.to());
                LinkRecord link = transaction.createLink(create.linkType(), resolveId(create.linkType(), parameters), from, to,
                        expressions.properties(create.properties()));
                journal.add(ActionContinuationState.undo("CREATE_LINK", new EntityKey(link.type(), link.id()), link.version(), Map.of()));
                affected.add(new EntityKey(link.type(), link.id()));
            } else if (effect instanceof ActionManifest.DeleteLink delete) {
                List<LinkRecord> selected = selectLinks(delete, expressions, context, storage, transaction, transactional);
                if (!authorizer.allowedSelection(context, actor, definition, parameters, ActionEffectAccess.decode(journal), selected, transaction)) {
                    throw new SecurityException("Action relationship selection denied");
                }
                if (delete.expect() == ActionManifest.LinkExpectation.ONE && selected.size() != 1) {
                    throw new LinkResolutionException(selected.isEmpty() ? "LINK_NOT_FOUND" : "LINK_RESOLUTION_AMBIGUOUS");
                }
                for (var link : selected) {
                    transaction.deleteLink(link.type(), link.id(), link.version());
                    journal.add(ActionContinuationState.undo("DELETE_LINK", new EntityKey(link.type(), link.id()), link.version() + 1, Map.of()));
                    affected.add(new EntityKey(link.type(), link.id()));
                }
            }
        }

        access.addAll(ActionEffectAccess.decode(journal));
        if (!authorizer.allowedEffects(context, actor, definition, parameters, access, transaction)) {
            throw new SecurityException("Action effects denied");
        }
        boolean continued = !manifest.sideEffects().isEmpty();
        if (continued) {
            transaction.putActionExecution(new org.openfoundry.foundation.spi.ActionExecution(actionId, actor.id(), manifest.action(), 1,
                    "PENDING", now, ActionContinuationState.initial(manifest, definition, parameters, expressions, affected, journal, now, transaction.transactionId())), 0);
        }
        Map<String, Object> detail = Map.of(
                "action", manifest.action(),
                "affected", affected.stream().map(key -> key.type() + "/" + key.id()).toList());
        transaction.appendAudit(new AuditEntry(
                "audit_" + actionId, now, context.tenantId(), actor.id(),
                "action", null, null, manifest.action(), transaction.transactionId(),
                continued ? "effects_committed" : "success", detail));
        transaction.enqueueOutbox(new OutboxEntry(
                "event_" + actionId, context.tenantId(), continued ? "openfoundry.action.effects_committed" : "openfoundry.action.completed",
                manifest.action() + "/" + actionId, now, transaction.transactionId(), detail));
        return new ActionResult(true, actionId, affected, "COMPLETED", List.of(), ActionResult.changes(access));
    }

    public ActionBatchResult executeBatch(List<ActionInvocation> invocations,
                                          RequestContext context, StorageProvider storage) {
        List<ActionResult> results = new ArrayList<>();
        for (ActionInvocation invocation : invocations) {
            results.add(execute(invocation.manifest(), invocation.definition(), context, invocation.actor(),
                    invocation.parameters(), invocation.idempotencyKey(), storage));
        }
        int succeeded = (int) results.stream().filter(ActionResult::success).count();
        int pending = (int) results.stream().filter(result -> java.util.Set.of("PENDING", "RUNNING", "COMPENSATING").contains(result.status())).count();
        return new ActionBatchResult(results, succeeded, results.size() - succeeded - pending, pending);
    }

    private static void validateTenantReferences(RequestContext context, java.util.Collection<?> values) {
        for (Object value : values) {
            if (value instanceof ObjectRecord record && !context.tenantId().equals(record.tenantId())) {
                throw new SecurityException("Cross-tenant Action reference");
            }
            if (value instanceof List<?> list) validateTenantReferences(context, list);
        }
    }

    private void requireChanges(RequestContext context, ActionActor actor, ActionTypeDefinition definition,
                                Map<String, Object> parameters, List<EntityKey> affected, Transaction transaction) {
        if (!authorizer.allowedChanges(context, actor, definition, parameters, affected, transaction)) {
            throw new SecurityException("Action effect or replay denied");
        }
    }

    private static List<LinkRecord> selectLinks(ActionManifest.DeleteLink deletion, ActionValues expressions,
                                                 RequestContext context, StorageProvider storage, Transaction transaction,
                                                 boolean transactional) {
        if (deletion.linkId() != null) {
            Object value = expressions.value(deletion.linkId());
            if (!(value instanceof String id) || id.isBlank()) throw new IllegalArgumentException("Link ID must resolve to a string");
            var link = transactional ? transaction.getLink(deletion.linkType(), id) : storage.getLink(context, deletion.linkType(), id);
            return link == null || link.isDeleted() ? List.of() : List.of(link);
        }
        var filter = deletion.filter();
        EntityKey from = filter.from() == null ? null : expressions.entity(filter.from());
        EntityKey to = filter.to() == null ? null : expressions.entity(filter.to());
        if (from == null && to == null) return List.of();
        // Upstream starts with current active links. active=false does not re-delete terminated links.
        return transaction.findLinks(deletion.linkType(), from, to);
    }

    private static String resolveId(String reference, Map<String, Object> parameters) {
        Object value = reference.startsWith("params.")
                ? parameters.get(reference.substring("params.".length()))
                : parameters.get(reference);
        if (value instanceof ObjectRecord object) return object.id();
        if (value instanceof EntityKey key) return key.id();
        if (value instanceof String id && !id.isBlank()) return id;
        if (reference.startsWith("params.")) throw new IllegalArgumentException("Missing generated-object ID parameter");
        return reference + "-" + UUID.randomUUID();
    }

}
