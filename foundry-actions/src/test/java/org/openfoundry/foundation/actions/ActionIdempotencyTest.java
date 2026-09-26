package org.openfoundry.foundation.actions;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionIdempotencyTest {
    @Test
    void reusesPreviousResultForSameIdempotencyKey() {
        ActionManifest manifest = new ActionManifest("Noop", 1, false, List.of(), List.of());
        var executor = new ActionExecutor(ExpressionEvaluator.simple(), new InMemoryIdempotencyStore());
        var actor = new ActionActor("u-1", Set.of("admin"));
        var first = executor.execute(manifest, null, RequestContext.system("tenant", "u-1"), actor,
                Map.of(), "request-1", new InMemoryStorageProvider());
        var second = executor.execute(manifest, null, RequestContext.system("tenant", "u-1"), actor,
                Map.of(), "request-1", new InMemoryStorageProvider());
        assertEquals(first, second);
    }
}
