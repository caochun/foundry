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

    public OpenFgaHttpAuthorizer(URI endpoint, String storeId, String authorizationModelId) {
        this(HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build(), new ObjectMapper(), endpoint, storeId, authorizationModelId);
    }

    OpenFgaHttpAuthorizer(HttpClient client, ObjectMapper mapper, URI endpoint,
                          String storeId, String authorizationModelId) {
        this.client = client;
        this.mapper = mapper;
        String base = endpoint.toString().replaceAll("/$", "");
        if (storeId == null || !storeId.matches("[A-Za-z0-9_-]+") || authorizationModelId == null || authorizationModelId.isBlank()) {
            throw new IllegalArgumentException("OpenFGA store and authorization model are required");
        }
        this.authorizationModelId = authorizationModelId;
        this.checkUri = URI.create(base + "/stores/" + storeId + "/check");
    }

    @Override
    public boolean check(SecurityPrincipal principal, String relation, EntityKey resource) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "authorization_model_id", authorizationModelId,
                    "tuple_key", Map.of(
                            "user", OpenFgaResourceIds.user(principal),
                            "relation", relation,
                            "object", OpenFgaResourceIds.resource(principal.tenantId(), resource))));
            HttpRequest request = HttpRequest.newBuilder(checkUri)
                    .header("content-type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return false;
            com.fasterxml.jackson.databind.JsonNode allowed = mapper.readTree(response.body()).get("allowed");
            return allowed != null && allowed.asBoolean(false);
        } catch (Exception ignored) {
            return false;
        }
    }
}
