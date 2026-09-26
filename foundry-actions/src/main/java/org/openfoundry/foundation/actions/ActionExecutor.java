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

    public ActionExecutor() {
        this(new CelExpressionEvaluator(), null);
    }

    public ActionExecutor(ExpressionEvaluator evaluator) {
        this(evaluator, null);
    }

    public ActionExecutor(ExpressionEvaluator evaluator, IdempotencyStore idempotencyStore) {
        this.evaluator = evaluator;
        this.idempotencyStore = idempotencyStore;
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
        String actionId = "act_" + UUID.randomUUID();
        if (idempotencyKey != null && idempotencyStore != null) {
            ActionResult previous = idempotencyStore.get(idempotencyKey);
            if (previous != null) return previous;
        }
        if (definition != null && !new ActionParameterValidator().validate(definition, parameters).isEmpty()) {
            ActionResult result = new ActionResult(false, actionId, List.of());
            remember(idempotencyKey, result);
            return result;
        }
        for (ActionManifest.Precondition precondition : manifest.preconditions()) {
            if (!evaluator.evaluate(precondition.expression(), parameters, actor)) {
                ActionResult result = new ActionResult(false, actionId, List.of());
                remember(idempotencyKey, result);
                return result;
            }
        }

        List<EntityKey> affected = new ArrayList<>();
        try (Transaction transaction = storage.beginTransaction(context)) {
            for (ActionManifest.ActionEffect effect : manifest.effects()) {
                if (effect instanceof ActionManifest.UpdateObject update) {
                    ObjectRecord target = object(parameters, update.target());
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
                    String linkId = resolveId(delete.linkId(), parameters);
                    transaction.deleteLink(delete.linkType(), linkId, currentLinkVersion(storage, context,
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
            transaction.commit();
        }
        ActionResult result = new ActionResult(true, actionId, affected);
        remember(idempotencyKey, result);
        return result;
    }

    public ActionBatchResult executeBatch(List<ActionInvocation> invocations,
                                          RequestContext context, StorageProvider storage) {
        List<ActionResult> results = new ArrayList<>();
        for (ActionInvocation invocation : invocations) {
            results.add(execute(invocation.manifest(), null, context, invocation.actor(),
                    invocation.parameters(), invocation.idempotencyKey(), storage));
        }
        int succeeded = (int) results.stream().filter(ActionResult::success).count();
        return new ActionBatchResult(results, succeeded, results.size() - succeeded);
    }

    private void remember(String key, ActionResult result) {
        if (key != null && idempotencyStore != null) idempotencyStore.put(key, result);
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
        return reference.startsWith("params.") ? String.valueOf(value) : reference + "-" + Instant.now().toEpochMilli();
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
