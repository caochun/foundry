package org.openfoundry.foundation.security;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.schema.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenFgaModelContractTest {
    private static final PropertyDefinition ID = new PropertyDefinition("id", "ID", true, true, false, false, false, false);
    private static final String MODEL = """
            {"schema_version":"1.1","type_definitions":[
              {"type":"user"},
              {"type":"book","relations":{"viewer":{"this":{}},"can_borrow":{"this":{}}},
               "metadata":{"relations":{"viewer":{"directly_related_user_types":[{"type":"user"}]},"can_borrow":{"directly_related_user_types":[{"type":"user"}]}}}},
              {"type":"member","relations":{"viewer":{"this":{}}},"metadata":{"relations":{"viewer":{"directly_related_user_types":[{"type":"user"}]}}}}
            ]}
            """;

    @Test
    void coverageUsesTheFirstObjectParameterAndRequiresExplicitCreationGrants() {
        var schema = schema(List.of(new ActionTypeDefinition("Borrow", List.of(new ActionParameter("note", "String", false),
                new ActionParameter("book", "Book", true), new ActionParameter("member", "Member", true)), "can_borrow")));
        var contract = new OpenFgaModelContract(MODEL, OpenFgaModelContract.typeNames(schema));
        assertDoesNotThrow(() -> contract.requireOntologyTargets(schema));
        assertThrows(IllegalArgumentException.class, () -> contract.requireRelation("Member", "can_borrow"));
        var missing = schema(List.of(new ActionTypeDefinition("Return", List.of(new ActionParameter("book", "Book", true)), "can_return")));
        assertThrows(IllegalArgumentException.class, () -> contract.requireOntologyTargets(missing));
        var create = schema(List.of(new ActionTypeDefinition("Create", List.of(new ActionParameter("name", "String", true)), "can_create")));
        assertThrows(IllegalArgumentException.class, () -> contract.requireOntologyTargets(create));
    }

    @Test
    void nameMappingIsStableAndRejectsReservedOrCollidingTypes() {
        var valid = new OntologySchema("types", "1", List.of(new ObjectTypeDefinition("HTTPServer", List.of(ID)),
                new ObjectTypeDefinition("WardBed", List.of(ID))), List.of(), List.of());
        assertEquals(Map.of("HTTPServer", "http_server", "WardBed", "ward_bed", "ActionType", "action_type"), OpenFgaModelContract.typeNames(valid));
        for (var names : List.of(List.of("HTTPServer", "HttpServer"), List.of("User"), List.of("ActionType"))) {
            var invalid = new OntologySchema("types", "1", names.stream().map(name -> new ObjectTypeDefinition(name, List.of(ID))).toList(), List.of(), List.of());
            assertThrows(IllegalArgumentException.class, () -> OpenFgaModelContract.typeNames(invalid));
        }
    }

    private static OntologySchema schema(List<ActionTypeDefinition> actions) {
        return new OntologySchema("library", "1", List.of(new ObjectTypeDefinition("Book", List.of(ID)),
                new ObjectTypeDefinition("Member", List.of(ID))), List.of(), actions);
    }
}
