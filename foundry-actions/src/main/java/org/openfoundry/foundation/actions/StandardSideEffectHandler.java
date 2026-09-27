package org.openfoundry.foundation.actions;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.events.CloudEvent;
import org.openfoundry.foundation.events.EventSink;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Event delivery and explicitly permitted webhooks. Redirects never bypass endpoint policy. */
public final class StandardSideEffectHandler implements SideEffectHandler {
    private final EventSink events;
    private final HttpClient http;
    private final Predicate<URI> allowedEndpoint;
    private final ObjectMapper json = new ObjectMapper();

    public StandardSideEffectHandler(EventSink events) {
        this(events, null, uri -> false);
    }

    public StandardSideEffectHandler(EventSink events, HttpClient http, Predicate<URI> allowedEndpoint) {
        if (http != null && http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("Webhook client must disable automatic redirects");
        }
        this.events = events;
        this.http = http;
        this.allowedEndpoint = java.util.Objects.requireNonNull(allowedEndpoint);
    }

    @Override
    public boolean supports(String type) {
        return type.equals("event") && events != null || type.equals("webhook") && http != null;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!supports(invocation.type())) throw new IllegalStateException("Side-effect destination is not configured");
        var config = invocation.config();
        if (invocation.type().equals("event")) {
            Map<String, Object> data = config.get("data") == null ? Map.of() : ActionContinuationState.map(config.get("data"));
            events.publish(new CloudEvent("1.0", invocation.idempotencyKey(), (String) config.getOrDefault("source", "openfoundry/actions"),
                    (String) config.get("type"), (String) config.get("subject"), invocation.occurredAt(),
                    invocation.context().tenantId(), invocation.transactionId(), data));
            return;
        }
        URI uri = URI.create((String) config.get("url"));
        if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || !allowedEndpoint.test(uri)) {
            throw new SecurityException("Webhook endpoint is not permitted");
        }
        String method = (String) config.getOrDefault("method", "POST");
        if (!Set.of("POST", "PUT", "PATCH", "DELETE", "GET", "HEAD").contains(method)) throw new IllegalArgumentException("Invalid webhook method");
        long timeout = ((Number) config.getOrDefault("timeoutMs", 10000)).longValue();
        if (timeout < 1 || timeout > 120000) throw new IllegalArgumentException("Invalid webhook timeout");
        try {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeout)).header("Content-Type", "application/json")
                    .header("Idempotency-Key", invocation.idempotencyKey());
            if (config.containsKey("headers")) {
                ActionContinuationState.map(config.get("headers")).forEach((name, value) -> {
                    if (Set.of("idempotency-key", "host", "content-length", "connection").contains(name.toLowerCase(java.util.Locale.ROOT))) {
                        throw new IllegalArgumentException("Reserved webhook header");
                    }
                    request.setHeader(name, (String) value);
                });
            }
            var body = config.containsKey("body") ? HttpRequest.BodyPublishers.ofString(json.writeValueAsString(config.get("body")))
                    : HttpRequest.BodyPublishers.noBody();
            var response = http.send(request.method(method, body).build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Webhook was not accepted");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Webhook interrupted", interrupted);
        } catch (java.io.IOException failed) {
            throw new IllegalStateException("Webhook transport failed", failed);
        }
    }
}
