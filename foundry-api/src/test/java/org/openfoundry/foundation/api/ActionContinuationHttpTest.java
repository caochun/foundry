package org.openfoundry.foundation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.openfoundry.foundation.api.SideEffectWorkflowTest.*;

class ActionContinuationHttpTest {
    @Test
    void authenticatedResumeContinuesStoredWorkAndRechecksActorTenantAndCurrentPermission() throws Exception {
        var clock = new TestClock();
        var storage = new InMemoryStorageProvider(clock);
        storage.applySchema(CONTEXT, SCHEMA);
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "a", Map.of("name", "BEFORE"));
            tx.createObject("Item", "b", Map.of("name", "Target"));
            tx.commit();
        }
        var manifest = action(UPDATE, "RETRY_INDEFINITELY", "PT5S", 1);
        var allowed = new AtomicBoolean(true);
        var attempts = new AtomicInteger();
        var context = new AtomicReference<>(new ApiRequestContext(CONTEXT, PRINCIPAL));
        var runtime = new ActionExecutor().withSideEffects(invocation -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("offline");
        }, clock, Duration.ofSeconds(30));
        var app = new ApplicationService(storage, new AuthorizationService((principal, relation, key) -> allowed.get()), runtime,
                SCHEMA, Map.of("Work", manifest), Map.of());
        var json = new ObjectMapper();
        try (var server = new JdkRestServer(0, new RestApiRouter(app), context::get, Map.of("Work", manifest));
             var client = HttpClient.newHttpClient()) {
            server.start();
            String root = "http://localhost:" + server.port() + "/api/v1/actions/Work";
            var submitted = client.send(HttpRequest.newBuilder(URI.create(root)).header("Content-Type", "application/json")
                    .header("Idempotency-Key", "resume-key").POST(HttpRequest.BodyPublishers.ofString("{\"item\":\"a\",\"target\":\"b\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, submitted.statusCode());
            var pending = json.readTree(submitted.body());
            assertEquals("PENDING", pending.path("status").asText());
            var resume = HttpRequest.newBuilder(URI.create(root + "/executions/" + pending.path("actionId").asText() + "/resume"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            clock.advance(5);
            allowed.set(false);
            assertEquals(403, client.send(resume, HttpResponse.BodyHandlers.ofString()).statusCode());
            allowed.set(true);
            context.set(new ApiRequestContext(RequestContext.system("tenant", "other"), new SecurityPrincipal("other", "tenant", Set.of("admin"))));
            assertEquals(403, client.send(resume, HttpResponse.BodyHandlers.ofString()).statusCode());
            context.set(new ApiRequestContext(RequestContext.system("other", "operator"), new SecurityPrincipal("operator", "other", Set.of("admin"))));
            assertEquals(400, client.send(resume, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(1, attempts.get());
            context.set(new ApiRequestContext(CONTEXT, PRINCIPAL));
            var completed = client.send(resume, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, completed.statusCode());
            assertEquals("COMPLETED", json.readTree(completed.body()).path("status").asText());
            assertEquals(2, attempts.get());
            assertEquals(2, storage.getObject(CONTEXT, "Item", "a").version());
            assertEquals(completed.body(), client.send(resume, HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(2, attempts.get());
        }
    }
}
