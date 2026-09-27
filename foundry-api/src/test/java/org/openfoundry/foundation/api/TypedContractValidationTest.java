package org.openfoundry.foundation.api;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.actions.ActionManifest;
import org.openfoundry.foundation.schema.*;
import org.openfoundry.foundation.security.AuthorizationService;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypedContractValidationTest {
    @Test
    void unknownParameterTypesFailCompilationInsteadOfBecomingObjectOrStringInputs() {
        var schema = new OdlParser().parse("""
                extend schema @namespace(name: "typed", version: "1.0.0")
                type Item @objectType { id: ID! @primary }
                type UnknownAction @actionType { value: Missing @param }
                """);
        assertThrows(SchemaValidationException.class, () -> new SchemaCompiler().compile(schema));
    }

    @Test
    void generatedInputAndResultNamesCannotOverrideOntologyTypes() {
        for (String name : List.of("ConfigureInput", "ConfigureResult", "ActionError", "AffectedObject", "ChangeType")) {
            var schema = new OdlParser().parse("""
                    extend schema @namespace(name: "typed", version: "1.0.0")
                    type %s @objectType { id: ID! @primary }
                    type Configure @actionType(permission: "can_configure") { value: String @param }
                    """.formatted(name));
            var manifest = new ActionManifest("Configure", 1, false, List.of(), List.of());
            var app = new ApplicationService(new InMemoryStorageProvider(), new AuthorizationService((p, r, k) -> true), new ActionExecutor(),
                    schema, Map.of("Configure", manifest), Map.of());
            assertThrows(IllegalArgumentException.class, () -> GraphqlApiRuntime.create(schema, app, Map.of("Configure", manifest)), name);
            assertThrows(IllegalArgumentException.class, () -> new GraphqlContractGenerator().generate(schema), name);
        }
    }
}
