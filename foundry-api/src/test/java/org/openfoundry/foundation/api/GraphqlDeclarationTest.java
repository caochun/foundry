package org.openfoundry.foundation.api;

import graphql.ExecutionInput;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class GraphqlDeclarationTest {
    @Test
    void interfacesEnumsListsJsonAndAuditFieldsUseTheDeclaredOutputTypes() {
        var schema = new OdlParser().parse("""
                extend schema @namespace(name: "api", version: "1.0.0")
                interface Identifiable { id: ID! @primary }
                interface Named implements Identifiable { name: String @default(value: "A") }
                interface Auditable { createdAt: DateTime! @readonly createdBy: String! @readonly }
                enum Status { READY DONE }
                type Item implements Named & Auditable @objectType {
                  state: Status! @default(value: READY)
                  labels: [String!] @default(value: ["a", "b"])
                  details: JSON @default(value: {source: "test"})
                  secret: String @sensitive
                }
                """);
        var storage = new InMemoryStorageProvider();
        var context = RequestContext.system("tenant", "writer");
        var principal = new SecurityPrincipal("writer", "tenant", Set.of());
        storage.applySchema(context, schema);
        try (var tx = storage.beginTransaction(context)) { tx.createObject("Item", "a", Map.of("secret", "hidden")); tx.commit(); }
        var app = new ApplicationService(storage, new AuthorizationService((p, r, e) -> true), new ActionExecutor(), schema, Map.of(), Map.of());
        var graph = GraphqlApiRuntime.create(schema, app);
        var result = graph.execute(ExecutionInput.newExecutionInput("{ item(id: \"a\") { ... on Identifiable { id } ... on Auditable { createdAt createdBy } state labels details secret } }")
                .graphQLContext(Map.of("request", new ApiRequestContext(context, principal))).build());
        assertTrue(result.getErrors().isEmpty(), result.getErrors().toString());
        Map<String, Object> data = result.getData(); var item = (Map<?, ?>) data.get("item");
        assertEquals("writer", item.get("createdBy"));
        assertEquals("READY", item.get("state"));
        assertEquals(List.of("a", "b"), item.get("labels"));
        assertEquals(Map.of("source", "test"), item.get("details"));
        assertNull(item.get("secret"));
        assertEquals("DateTime", ((graphql.schema.GraphQLNamedType) graph.getGraphQLSchema().getObjectType("Item").getFieldDefinition("createdAt").getType()).getName());
        String sdl = new GraphqlContractGenerator().generate(schema);
        assertTrue(sdl.contains("interface Auditable"));
        assertTrue(sdl.contains("enum Status"));
        assertEquals(Set.of("createObjectSet", "updateObjectSet", "deleteObjectSet"), graph.getGraphQLSchema().getMutationType().getFieldDefinitions()
                .stream().map(graphql.schema.GraphQLFieldDefinition::getName).collect(java.util.stream.Collectors.toSet()));
        assertDoesNotThrow(() -> new graphql.parser.Parser().parseDocument(sdl));
    }
}
