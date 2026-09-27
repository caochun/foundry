package org.openfoundry.foundation.actions;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.schema.ActionParameter;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ActionIdempotencyTest {
    private final ActionManifest manifest = new ActionManifest("Noop", 1, false, List.of(), List.of());
    private final ActionTypeDefinition definition = new ActionTypeDefinition("Noop", List.of(new ActionParameter("reason", "String", false)), "can_noop");
    private final ActionActor actor = new ActionActor("u-1", Set.of("admin"));
    private final RequestContext context = RequestContext.system("tenant", actor.id());

    @Test
    void concurrentDuplicateRequestsExecuteExactlyOnceWithinOneStore() throws Exception {
        var storage = new InMemoryStorageProvider();
        var executor = new ActionExecutor(ExpressionEvaluator.simple(), new InMemoryIdempotencyStore(), (c, a, d, p) -> true);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = java.util.stream.IntStream.range(0, 16).<java.util.concurrent.Callable<ActionResult>>mapToObj(i ->
                    () -> executor.execute(manifest, definition, context, actor, Map.of(), "request-1", storage)).toList();
            var responses = pool.invokeAll(tasks);
            var first = responses.getFirst().get();
            for (var response : responses) assertEquals(first, response.get());
        }
        assertEquals(1, storage.auditEntries(context).size());
        assertEquals(1, storage.outboxEntries(context).size());
    }

    @Test
    void sameKeyIsIsolatedByTenantActorAndActionAndConflictingPayloadIsRejected() {
        var storage = new InMemoryStorageProvider();
        var executor = new ActionExecutor(ExpressionEvaluator.simple(), new InMemoryIdempotencyStore(), (c, a, d, p) -> true);
        var first = executor.execute(manifest, definition, context, actor, Map.of("reason", "one"), "key", storage);
        assertEquals(first, executor.execute(manifest, definition, context, actor, Map.of("reason", "one"), "key", storage));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(manifest, definition, context, actor, Map.of("reason", "two"), "key", storage));
        var tenantB = executor.execute(manifest, definition, RequestContext.system("tenant-b", actor.id()), actor, Map.of(), "key", storage);
        var actorB = new ActionActor("u-2", Set.of());
        var userB = executor.execute(manifest, definition, RequestContext.system("tenant", actorB.id()), actorB, Map.of(), "key", storage);
        var another = new ActionManifest("Another", 1, false, List.of(), List.of());
        var actionB = executor.execute(another, new ActionTypeDefinition("Another", List.of(), "can_other"), context, actor, Map.of(), "key", storage);
        assertEquals(4, Set.of(first.actionId(), tenantB.actionId(), userB.actionId(), actionB.actionId()).size());
    }

    @Test
    void revokedAuthorizationIsCheckedBeforeIdempotencyReplay() {
        var allowed = new AtomicBoolean(true);
        var checks = new AtomicInteger();
        var executor = new ActionExecutor(ExpressionEvaluator.simple(), new InMemoryIdempotencyStore(), (c, a, d, p) -> {
            checks.incrementAndGet();
            return allowed.get();
        });
        var storage = new InMemoryStorageProvider();
        executor.execute(manifest, definition, context, actor, Map.of(), "key", storage);
        allowed.set(false);
        assertThrows(SecurityException.class, () -> executor.execute(manifest, definition, context, actor, Map.of(), "key", storage));
        assertEquals(2, checks.get());
        assertEquals(1, storage.auditEntries(context).size());
    }

    @Test
    void missingPolicyPermissionOrActorMismatchCannotExecute() {
        var storage = new InMemoryStorageProvider();
        assertThrows(SecurityException.class, () -> new ActionExecutor().execute(manifest, definition, context, actor, Map.of(), storage));
        var executor = new ActionExecutor().withAuthorization((c, a, d, p) -> true);
        assertThrows(SecurityException.class, () -> executor.execute(manifest, new ActionTypeDefinition("Noop", List.of()), context, actor, Map.of(), storage));
        assertThrows(SecurityException.class, () -> executor.execute(manifest, definition, RequestContext.system("tenant", "someone-else"), actor, Map.of(), storage));
        assertThrows(IllegalStateException.class, () -> executor.execute(manifest, definition, context, actor, Map.of(), "key", storage));
        assertThrows(IllegalArgumentException.class, () -> executor.execute(manifest, definition, context, actor, Map.of(), " ", storage));
        assertTrue(storage.auditEntries(context).isEmpty());
    }

    @Test
    void changedManifestCannotReuseSuccessfulKey() {
        var storage = new InMemoryStorageProvider();
        var executor = new ActionExecutor(ExpressionEvaluator.simple(), new InMemoryIdempotencyStore(), (c, a, d, p) -> true);
        executor.execute(manifest, definition, context, actor, Map.of(), "key", storage);
        var changed = new ActionManifest("Noop", 2, false, List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> executor.execute(changed, definition, context, actor, Map.of(), "key", storage));
    }
}
