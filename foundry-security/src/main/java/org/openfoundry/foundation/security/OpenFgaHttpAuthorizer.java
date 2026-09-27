package org.openfoundry.foundation.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.EntityKey;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

/** Minimal fail-closed OpenFGA Check API adapter. */
public final class OpenFgaHttpAuthorizer implements RelationshipAuthorizer {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI checkUri;
    private final String authorizationModelId;
    private final Map<String, String> typeNames;

    public OpenFgaHttpAuthorizer(URI endpoint, String storeId, String authorizationModelId) {
        this(HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build(), new ObjectMapper(), endpoint, storeId, authorizationModelId);
    }

    OpenFgaHttpAuthorizer(HttpClient client, ObjectMapper mapper, URI endpoint,
                          String storeId, String authorizationModelId) {
        this(client, mapper, endpoint, storeId, authorizationModelId, null);
    }

    public OpenFgaHttpAuthorizer(URI endpoint, String storeId, String authorizationModelId, Map<String, String> typeNames) {
        this(HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build(), new ObjectMapper(), endpoint,
                storeId, authorizationModelId, Map.copyOf(typeNames));
    }

    private OpenFgaHttpAuthorizer(HttpClient client, ObjectMapper mapper, URI endpoint,
                                  String storeId, String authorizationModelId, Map<String, String> typeNames) {
        if (typeNames != null && new java.util.HashSet<>(typeNames.values()).size() != typeNames.size()) {
            throw new IllegalArgumentException("OpenFGA type aliases must be one-to-one");
        }
        this.typeNames = typeNames;
        this.client = client;
        this.mapper = mapper;
        String base = endpoint.toString().replaceAll("/$", "");
        if (storeId == null || !storeId.matches("[A-Za-z0-9_-]+") || authorizationModelId == null || authorizationModelId.isBlank()) {
            throw new IllegalArgumentException("OpenFGA store and authorization model are required");
        }
        this.authorizationModelId = authorizationModelId;
        this.checkUri = URI.create(base + "/stores/" + storeId + "/check");
    }

    /** Reads the immutable server model and verifies the relations used by ontology-target authorization. */
    public static OpenFgaHttpAuthorizer forOntology(URI endpoint, String storeId, String modelId,
                                                    org.openfoundry.foundation.spi.schema.OntologySchema schema) {
        if (modelId == null || !modelId.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid OpenFGA model ID");
        var aliases = OpenFgaModelContract.typeNames(schema);
        var result = new OpenFgaHttpAuthorizer(endpoint, storeId, modelId, aliases);
        String base = endpoint.toString().replaceAll("/$", "");
        var request = HttpRequest.newBuilder(URI.create(base + "/stores/" + storeId + "/authorization-models/" + modelId))
                .timeout(java.time.Duration.ofSeconds(5)).GET().build();
        try {
            var response = result.client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Cannot read OpenFGA authorization model");
            var model = result.mapper.readTree(response.body()).path("authorization_model");
            if (!modelId.equals(model.path("id").asText())) throw new IllegalStateException("OpenFGA model identity mismatch");
            new OpenFgaModelContract(result.mapper.writeValueAsString(model), aliases).requireOntologyTargets(schema);
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenFGA model validation interrupted", interrupted);
        } catch (java.io.IOException failed) {
            throw new IllegalStateException("OpenFGA model validation failed", failed);
        }
    }

    @Override
    public boolean check(SecurityPrincipal principal, String relation, EntityKey resource) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "authorization_model_id", authorizationModelId,
                    "consistency", "HIGHER_CONSISTENCY",
                    "tuple_key", Map.of(
                            "user", OpenFgaResourceIds.user(principal),
                            "relation", relation,
                            "object", typeNames == null ? OpenFgaResourceIds.resource(principal.tenantId(), resource)
                                    : OpenFgaResourceIds.resource(principal.tenantId(), resource, typeNames))));
            HttpRequest request = HttpRequest.newBuilder(checkUri)
                    .header("content-type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return false;
            com.fasterxml.jackson.databind.JsonNode allowed = mapper.readTree(response.body()).get("allowed");
            return allowed != null && allowed.isBoolean() && allowed.booleanValue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }
}
