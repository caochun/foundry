package org.openfoundry.foundation.api;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdkRestServerTest {
    @Test
    void servesObjectThroughRestAdapter() throws Exception {
        PropertyDefinition id = new PropertyDefinition("id", "ID", true, true, true, true, false, true);
        OntologySchema schema = new OntologySchema("example", "0.1.0",
                List.of(new ObjectTypeDefinition("Person", List.of(id))), List.of(), List.of());
        RequestContext context = RequestContext.system("tenant", "u-1");
        var storage = new InMemoryStorageProvider(); storage.applySchema(context, schema);
        try (var tx = storage.beginTransaction(context)) { tx.createObject("Person", "p-1", Map.of()); tx.commit(); }
        var principal = new SecurityPrincipal("u-1", "tenant", Set.of("viewer"));
        var app = new ApplicationService(storage, new AuthorizationService((p, r, e) -> true), new ActionExecutor());
        try (var server = new JdkRestServer(0, new RestApiRouter(app), () -> new ApiRequestContext(context, principal))) {
            server.start();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/v1/Person/p-1")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
        }
    }
}
