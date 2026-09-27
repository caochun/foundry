package org.openfoundry.foundation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.actions.ActionManifest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.function.Supplier;

/** Minimal JDK HTTP adapter for the framework-neutral REST router. */
public final class JdkRestServer implements AutoCloseable {
    private final HttpServer server;
    private final RestApiRouter router;
    private final Supplier<ApiRequestContext> requestContext;
    private final Map<String, ActionManifest> manifests;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    public JdkRestServer(int port, RestApiRouter router, Supplier<ApiRequestContext> requestContext) throws IOException {
        this(port, router, requestContext, Map.of());
    }

    public JdkRestServer(int port, RestApiRouter router, Supplier<ApiRequestContext> requestContext,
                         Map<String, ActionManifest> manifests) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.router = router;
        this.requestContext = requestContext;
        this.manifests = Map.copyOf(manifests);
        server.createContext("/api/v1", this::handle);
    }

    public void start() { server.start(); }
    public int port() { return server.getAddress().getPort(); }

    @Override
    public void close() { server.stop(0); }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            handleAuthenticated(exchange);
        } catch (org.openfoundry.foundation.spi.schema.PropertyValidationException invalid) {
            write(exchange, new ApiResponse(400, Map.of("error", "Property validation failed", "code", invalid.code(), "field", invalid.field())));
        } catch (org.openfoundry.foundation.spi.TemporalHistoryUnavailableException legacy) {
            write(exchange, new ApiResponse(409, Map.of("error", "Historical time query requires migration", "code", legacy.code())));
        } catch (SecurityException denied) {
            write(exchange, ApiResponse.forbidden());
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException invalid) {
            write(exchange, ApiResponse.badRequest("Invalid request"));
        } catch (RuntimeException failure) {
            write(exchange, new ApiResponse(500, Map.of("error", "request failed")));
        }
    }

    private void handleAuthenticated(HttpExchange exchange) throws IOException {
        ApiRequestContext context = requestContext.get();
        ApiResponse response;
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            response = router.get(context.request(), context.principal(), exchange.getRequestURI().getPath(), QueryOptions.defaults());
        } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())
                && exchange.getRequestURI().getPath().startsWith("/api/v1/actions/")) {
            String actionName = exchange.getRequestURI().getPath().substring("/api/v1/actions/".length());
            ActionManifest manifest = manifests.get(actionName);
            if (manifest == null) { write(exchange, ApiResponse.notFound()); return; }
            Map<String, Object> input = mapper.readValue(exchange.getRequestBody(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
            response = router.execute(context.request(), context.principal(), actionName, manifest, input, key);
        } else {
            response = ApiResponse.badRequest("unsupported HTTP method");
        }
        write(exchange, response);
    }

    private void write(HttpExchange exchange, ApiResponse response) throws IOException {
        byte[] body = mapper.writeValueAsBytes(response.body());
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
