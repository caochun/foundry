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
    private final ObjectMapper mapper = ApiJson.mapper().registerModule(new JavaTimeModule());

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
        } catch (org.openfoundry.foundation.spi.SchemaVersionMismatchException stale) {
            write(exchange, new ApiResponse(409, Map.of("error", "Deployment schema must be refreshed", "code", "SCHEMA_VERSION_MISMATCH", "retryable", false)));
        } catch (org.openfoundry.foundation.spi.TransactionConflictException conflict) {
            write(exchange, new ApiResponse(409, Map.of("error", "Concurrent command conflict", "code", "TRANSACTION_CONFLICT", "retryable", true)));
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
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/api/v1/consent") || path.startsWith("/api/v1/consent/")) {
            Map<String, Object> body = Map.of();
            if (exchange.getRequestMethod().equals("POST")) {
                body = mapper.readValue(exchange.getRequestBody(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                if (body == null) throw new IllegalArgumentException("Consent body must be an object");
            }
            response = router.consent(context.request(), context.principal(), exchange.getRequestMethod(), path,
                    parameters(exchange.getRequestURI().getRawQuery()), body);
        } else if (path.equals("/api/v1/object-sets") || path.startsWith("/api/v1/object-sets/")) {
            Map<String, Object> body = Map.of();
            if (exchange.getRequestMethod().equals("POST") || exchange.getRequestMethod().equals("PUT")) {
                body = mapper.readValue(exchange.getRequestBody(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                if (body == null) throw new IllegalArgumentException("ObjectSet body must be an object");
            }
            response = router.objectSets(context.request(), context.principal(), exchange.getRequestMethod(), path,
                    parameters(exchange.getRequestURI().getRawQuery()), body);
        } else if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && exchange.getRequestURI().getPath().matches("/api/v1/[^/]+/search")) {
            String type = exchange.getRequestURI().getPath().split("/")[3];
            response = router.search(context.request(), context.principal(), type, SearchQuery.fromParameters(parameters(exchange.getRequestURI().getRawQuery())));
        } else if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            response = router.get(context.request(), context.principal(), exchange.getRequestURI().getPath(), QueryOptions.defaults());
        } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())
                && exchange.getRequestURI().getPath().startsWith("/api/v1/actions/")) {
            String[] route = exchange.getRequestURI().getPath().substring("/api/v1/actions/".length()).split("/");
            if (route.length == 0) { write(exchange, ApiResponse.notFound()); return; }
            ActionManifest manifest = manifests.get(route[0]);
            if (manifest == null) { write(exchange, ApiResponse.notFound()); return; }
            if (route.length == 4 && route[1].equals("executions") && route[3].equals("resume")) {
                response = router.resume(context.request(), context.principal(), route[0], route[2]);
            } else if (route.length == 1) {
                Map<String, Object> input = mapper.readValue(exchange.getRequestBody(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
                String key = exchange.getRequestHeaders().getFirst("Idempotency-Key");
                response = router.execute(context.request(), context.principal(), route[0], manifest, input, key);
            } else {
                response = ApiResponse.notFound();
            }
        } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())
                && exchange.getRequestURI().getPath().matches("/api/v1/[^/]+/(query|aggregate|search)")) {
            String type = exchange.getRequestURI().getPath().split("/")[3];
            Map<String, Object> input = mapper.readValue(exchange.getRequestBody(), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            String operation = exchange.getRequestURI().getPath().split("/")[4];
            response = switch (operation) {
                case "aggregate" -> router.aggregate(context.request(), context.principal(), type, AggregateQuery.fromJson(input));
                case "search" -> router.search(context.request(), context.principal(), type, SearchQuery.fromJson(input));
                default -> router.query(context.request(), context.principal(), type, ObjectConnectionQuery.fromJson(input));
            };
        } else {
            response = ApiResponse.badRequest("unsupported HTTP method");
        }
        write(exchange, response);
    }

    private static Map<String, String> parameters(String query) {
        var values = new LinkedHashMap<String, String>();
        if (query == null || query.isEmpty()) return values;
        for (String pair : query.split("&", -1)) {
            String[] parts = pair.split("=", 2);
            String name = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length == 2 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            if (values.putIfAbsent(name, value) != null) throw new IllegalArgumentException("Duplicate request parameter");
        }
        return values;
    }

    private void write(HttpExchange exchange, ApiResponse response) throws IOException {
        if (response.status() == 204) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        byte[] body = mapper.writeValueAsBytes(response.body());
        exchange.getResponseHeaders().set("content-type", "application/json");
        exchange.sendResponseHeaders(response.status(), body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
