package org.openfoundry.foundation.api;

import org.junit.jupiter.api.Test;
import graphql.GraphQL;
import graphql.ExecutionInput;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.security.SecurityPrincipal;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiContractTest {
    @Test
    void generatesStableGraphqlQueryAndMutationContract() {
        PropertyDefinition id = new PropertyDefinition("id", "ID", true, true, true, true, false, true);
        OntologySchema schema = new OntologySchema("example", "0.1.0",
                List.of(new ObjectTypeDefinition("Person", List.of(id))), List.of(), List.of());
        String sdl = new GraphqlContractGenerator().generate(schema);
        assertTrue(sdl.contains("type Person"));
        assertTrue(sdl.contains("person(id: ID!)"));
    }

    @Test
    void executesGeneratedGraphqlQueryThroughApplicationService() {
        PropertyDefinition id = new PropertyDefinition("id", "ID", true, true, true, true, false, true);
        OntologySchema schema = new OntologySchema("example", "0.1.0",
                List.of(new ObjectTypeDefinition("Person", List.of(id))), List.of(), List.of());
        var context = RequestContext.system("tenant", "u-1");
        var storage = new InMemoryStorageProvider();
        storage.applySchema(context, schema);
        try (var tx = storage.beginTransaction(context)) {
            tx.createObject("Person", "p-1", Map.of("name", "Alice"));
            tx.commit();
        }
        var app = new ApplicationService(storage, new AuthorizationService((p, r, e) -> true), new ActionExecutor());
        GraphQL graphQL = GraphqlApiRuntime.create(schema, app);
        var principal = new SecurityPrincipal("u-1", "tenant", Set.of("viewer"));
        var input = ExecutionInput.newExecutionInput(graphQLQuery())
                .graphQLContext(Map.of("request", new ApiRequestContext(context, principal))).build();
        var result = graphQL.execute(input);
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
    }

    private static String graphQLQuery() { return "{ person(id: \"p-1\") { id } }"; }
}
