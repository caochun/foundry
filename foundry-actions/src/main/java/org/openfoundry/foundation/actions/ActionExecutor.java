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
        this.evaluator = java.util.Objects.requireNonNull(evaluator);
        this.idempotencyStore = idempotencyStore;
        this.authorizer = java.util.Objects.requireNonNull(authorizer);
    }

    /** Only trusted application wiring may supply policies; no HTTP request can provide one. */
    public ActionExecutor withAuthorization(ActionAuthorizer policy) {
        return new ActionExecutor(evaluator, idempotencyStore, policy);
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
        if (!new ActionParameterValidator().validate(definition, parameters).isEmpty()) {
            throw new IllegalArgumentException("Action parameters do not match the registered schema");
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
            return idempotencyStore.execute(scoped, fingerprint, () -> executeEffects(manifest, context, actor, parameters, storage));
        }
        if (storage.capabilities().transactionalCommandReceipts()) {
            return executeTransactional(manifest, definition, context, actor, parameters, storage, null, null);
        }
        return executeEffects(manifest, context, actor, parameters, storage);
    }

    private ActionResult executeTransactional(ActionManifest manifest, ActionTypeDefinition definition, RequestContext context,
                                               ActionActor actor, Map<String, Object> parameters, StorageProvider storage,
                                               String key, String fingerprint) {
        for (int attempt = 0; attempt < 8; attempt++) {
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
                    return decodeResult(receipt.result());
                }
                parameters.forEach((name, value) -> checkReferenceVersions(value, resolved.get(name)));
                ActionResult result = applyEffects(manifest, context, actor, resolved, storage, transaction, true);
                if (!result.success()) return result;
                if (key != null) {
                    transaction.putCommandReceipt(new org.openfoundry.foundation.spi.CommandReceipt(key, actor.id(), manifest.action(), fingerprint,
                            Map.of("format", 1, "success", true, "actionId", result.actionId(), "affected", result.affected().stream()
                                    .map(entity -> Map.of("type", entity.type(), "id", entity.id())).toList())));
                }
                transaction.commit();
                return result;
            } catch (org.openfoundry.foundation.spi.TransactionConflictException conflict) {
                if (attempt == 7) throw conflict;
            }
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
        return new ActionResult(true, actionId, affected);
    }

    private ActionResult executeEffects(ActionManifest manifest, RequestContext context, ActionActor actor,
                                        Map<String, Object> parameters, StorageProvider storage) {
        try (Transaction transaction = storage.beginTransaction(context)) {
            ActionResult result = applyEffects(manifest, context, actor, parameters, storage, transaction, false);
            if (result.success()) transaction.commit();
            return result;
        }
    }

    private ActionResult applyEffects(ActionManifest manifest, RequestContext context, ActionActor actor,
                                      Map<String, Object> parameters, StorageProvider storage, Transaction transaction,
                                      boolean transactional) {
        String actionId = "act_" + UUID.randomUUID();
        for (ActionManifest.Precondition precondition : manifest.preconditions()) {
            if (!evaluator.evaluate(precondition.expression(), parameters, actor)) {
                ActionResult result = new ActionResult(false, actionId, List.of());
                return result;
            }
        }

        List<EntityKey> affected = new ArrayList<>();
        for (ActionManifest.ActionEffect effect : manifest.effects()) {
            if (effect instanceof ActionManifest.UpdateObject update) {
                ObjectRecord target = object(parameters, update.target());
                if (transactional) target = transaction.getObject(target.type(), target.id());
                Map<String, Object> values = resolveMap(update.set(), parameters);
                transaction.updateObject(target.type(), target.id(), values, target.version());
                affected.add(target.key());
            } else if (effect instanceof ActionManifest.CreateObject create) {
                Map<String, Object> values = resolveMap(create.properties(), parameters);
                ObjectRecord created = transaction.createObject(create.objectType(), resolveId(create.target(), parameters), values);
                affected.add(created.key());
            } else if (effect instanceof ActionManifest.CreateLink create) {
                EntityKey from = entity(parameters, create.from());
                EntityKey to = entity(parameters, create.to());
                LinkRecord link = transaction.createLink(create.linkType(), resolveId(create.linkType(), parameters), from, to,
                        resolveMap(create.properties(), parameters));
                affected.add(new EntityKey(link.type(), link.id()));
            } else if (effect instanceof ActionManifest.DeleteLink delete) {
                String linkId = String.valueOf(resolveValue(delete.linkId(), parameters));
                transaction.deleteLink(delete.linkType(), linkId, transactional ? transaction.getLink(delete.linkType(), linkId).version() : currentLinkVersion(storage, context,
                        new ActionManifest.DeleteLink(delete.linkType(), linkId)));
                affected.add(new EntityKey(delete.linkType(), linkId));
            }
        }
        Map<String, Object> detail = Map.of(
                "action", manifest.action(),
                "affected", affected.stream().map(key -> key.type() + "/" + key.id()).toList());
        transaction.appendAudit(new AuditEntry(
                "audit_" + actionId, Instant.now(), context.tenantId(), actor.id(),
                "action", null, null, manifest.action(), transaction.transactionId(),
                "success", detail));
        transaction.enqueueOutbox(new OutboxEntry(
                "event_" + actionId, context.tenantId(), "openfoundry.action.completed",
                manifest.action() + "/" + actionId, Instant.now(), transaction.transactionId(), detail));
        return new ActionResult(true, actionId, affected);
    }

    public ActionBatchResult executeBatch(List<ActionInvocation> invocations,
                                          RequestContext context, StorageProvider storage) {
        List<ActionResult> results = new ArrayList<>();
        for (ActionInvocation invocation : invocations) {
            results.add(execute(invocation.manifest(), invocation.definition(), context, invocation.actor(),
                    invocation.parameters(), invocation.idempotencyKey(), storage));
        }
        int succeeded = (int) results.stream().filter(ActionResult::success).count();
        return new ActionBatchResult(results, succeeded, results.size() - succeeded);
    }

    private static void validateTenantReferences(RequestContext context, java.util.Collection<?> values) {
        for (Object value : values) {
            if (value instanceof ObjectRecord record && !context.tenantId().equals(record.tenantId())) {
                throw new SecurityException("Cross-tenant Action reference");
            }
            if (value instanceof List<?> list) validateTenantReferences(context, list);
        }
    }

    private static long currentLinkVersion(StorageProvider storage, RequestContext context, ActionManifest.DeleteLink effect) {
        LinkRecord link = storage.getLink(context, effect.linkType(), effect.linkId());
        if (link == null) throw new IllegalArgumentException("link not found: " + effect.linkType() + ":" + effect.linkId());
        return link.version();
    }

    private static ObjectRecord object(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        if (!(value instanceof ObjectRecord object)) throw new IllegalArgumentException("Action parameter is not an object: " + name);
        return object;
    }

    private static EntityKey entity(Map<String, Object> parameters, String name) {
        Object value = parameters.get(name);
        if (value instanceof ObjectRecord object) return object.key();
        if (value instanceof EntityKey key) return key;
        throw new IllegalArgumentException("Action endpoint is not an object: " + name);
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

    private static Map<String, Object> resolveMap(Map<String, String> source, Map<String, Object> parameters) {
        Map<String, Object> result = new HashMap<>();
        source.forEach((key, value) -> result.put(key, resolveValue(value, parameters)));
        return result;
    }

    private static Object resolveValue(String value, Map<String, Object> parameters) {
        if (value.startsWith("params.")) return parameters.get(value.substring("params.".length()));
        int dot = value.indexOf('.');
        if (dot > 0 && parameters.containsKey(value.substring(0, dot))) {
            Object current = parameters.get(value.substring(0, dot));
            String field = value.substring(dot + 1);
            if (current instanceof ObjectRecord object && field.equals("id")) return object.id();
            if (current instanceof ObjectRecord object) return object.properties().get(field);
        }
        return value;
    }
}
