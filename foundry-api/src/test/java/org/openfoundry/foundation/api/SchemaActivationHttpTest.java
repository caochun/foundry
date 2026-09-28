package org.openfoundry.foundation.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.storage.jdbc.*;

import java.net.URI;
import java.net.http.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SchemaActivationHttpTest {
    @Test
    void anOldDeploymentReturnsNonRetryableSchemaConflictUntilTheApplicationIsRebuilt() throws Exception {
        String odl = """
                extend schema @namespace(name: "http-activation", version: "1.0.0")
                type Item @objectType { id: ID! @primary name: String! }
                type CreateItem @actionType(permission: "can_create") { name: String! @param }
                """;
        var oldSchema = new OdlParser().parse(odl);
        var nextSchema = new OdlParser().parse(odl.replace("name: String! }", "name: String! extra: String }"));
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:http_activation_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        var context = RequestContext.system("tenant", "operator");
        var principal = new SecurityPrincipal("operator", "tenant", Set.of());
        var oldStorage = new JdbcStorageProvider(data, DatabaseDialect.h2());
        oldStorage.applySchema(context, oldSchema);
        var nextStorage = new JdbcStorageProvider(data, DatabaseDialect.h2());
        nextStorage.applySchema(context, oldSchema);
        var manifest = new ActionManifest("CreateItem", 1, false, List.of(), List.of(new ActionManifest.CreateObject("Item", "created", Map.of("name", "params.name"))));
        var manifests = Map.of("CreateItem", manifest);
        var authorization = new AuthorizationService((p, r, key) -> true);
        var oldApp = new ApplicationService(oldStorage, authorization, new ActionExecutor(), oldSchema, manifests, Map.of());
        nextStorage.activateSchema(context, nextSchema, null, 1);
        try (var client = HttpClient.newHttpClient();
             var oldServer = new JdkRestServer(0, new RestApiRouter(oldApp), () -> new ApiRequestContext(context, principal), manifests)) {
            oldServer.start();
            var rejected = send(client, oldServer);
            assertEquals(409, rejected.statusCode(), rejected.body());
            var error = new ObjectMapper().readTree(rejected.body());
            assertEquals("SCHEMA_VERSION_MISMATCH", error.path("code").asText());
            assertFalse(error.path("retryable").asBoolean());
            assertTrue(nextStorage.queryObjects(context, "Item", QueryOptions.defaults()).isEmpty());
            var nextApp = new ApplicationService(nextStorage, authorization, new ActionExecutor(), nextSchema, manifests, Map.of());
            try (var nextServer = new JdkRestServer(0, new RestApiRouter(nextApp), () -> new ApiRequestContext(context, principal), manifests)) {
                nextServer.start();
                var accepted = send(client, nextServer);
                assertEquals(200, accepted.statusCode(), accepted.body());
                assertTrue(new ObjectMapper().readTree(accepted.body()).path("success").asBoolean());
                assertEquals(1, nextStorage.queryObjects(context, "Item", QueryOptions.defaults()).size());
            }
        }
    }

    private HttpResponse<String> send(HttpClient client, JdkRestServer server) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + server.port() + "/api/v1/actions/CreateItem"))
                .header("Content-Type", "application/json").header("Idempotency-Key", "same-command")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"Accepted only by current deployment\"}")).build(), HttpResponse.BodyHandlers.ofString());
    }
}
