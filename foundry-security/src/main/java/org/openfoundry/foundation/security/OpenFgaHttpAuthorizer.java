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

    public OpenFgaHttpAuthorizer(URI endpoint, String storeId, String authorizationModelId) {
        this(HttpClient.newHttpClient(), new ObjectMapper(), endpoint, storeId, authorizationModelId);
    }

    OpenFgaHttpAuthorizer(HttpClient client, ObjectMapper mapper, URI endpoint,
                          String storeId, String authorizationModelId) {
        this.client = client;
        this.mapper = mapper;
        String base = endpoint.toString().replaceAll("/$", "");
        this.checkUri = URI.create(base + "/stores/" + storeId + "/authorization-models/"
                + authorizationModelId + "/check");
    }

    @Override
    public boolean check(SecurityPrincipal principal, String relation, EntityKey resource) {
        try {
            String body = mapper.writeValueAsString(Map.of(
                    "tuple_key", Map.of(
                            "user", "user:" + principal.id(),
                            "relation", relation,
                            "object", resource.type() + ":" + resource.id())));
            HttpRequest request = HttpRequest.newBuilder(checkUri)
                    .header("content-type", "application/json")
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
