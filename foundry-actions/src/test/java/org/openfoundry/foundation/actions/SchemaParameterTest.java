package org.openfoundry.foundation.actions;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.schema.*;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SchemaParameterTest {
    private static final OntologySchema SCHEMA = new OntologySchema("parameters", "1.0.0", List.of(), List.of(), List.of(), Map.of("Status", List.of("READY", "DONE")));

    @Test
    void schemaAwareValidationSupportsEnumsAllScalarKindsAndNestedNullability() {
        var definition = new ActionTypeDefinition("Validate", List.of(
                new ActionParameter("state", "Status", true), new ActionParameter("states", "[[Status!]!]", true),
                new ActionParameter("duration", "Duration", true), new ActionParameter("uri", "URI", true),
                new ActionParameter("position", "GeoPoint", true), new ActionParameter("date", "Date", true)), "can_validate");
        var values = Map.<String, Object>of("state", "READY", "states", List.of(List.of("DONE")), "duration", "PT5M",
                "uri", "urn:example:item", "position", Map.of("lat", 0, "lon", 0), "date", "2030-02-28");
        var validator = new ActionParameterValidator(SCHEMA);
        assertTrue(validator.validate(definition, values).isEmpty());
        for (var change : List.<Map<String, Object>>of(Map.of("state", "UNKNOWN"), Map.of("states", List.of(java.util.Arrays.asList("DONE", null))),
                Map.of("duration", "five minutes"), Map.of("uri", "relative"), Map.of("position", Map.of("lat", 0, "lon", 181)), Map.of("date", "2030-02-30"))) {
            var invalid = new java.util.LinkedHashMap<>(values);
            invalid.putAll(change);
            assertFalse(validator.validate(definition, invalid).isEmpty(), change.toString());
        }
        assertFalse(new ActionParameterValidator().validate(definition, values).isEmpty(), "An undeclared enum must never be accepted as an arbitrary string");
    }

    @Test
    void directExecutionKeepsSchemaMetadataAcrossAuthorizationAndSideEffectWiring() {
        var definition = new ActionTypeDefinition("Validate", List.of(new ActionParameter("state", "Status", true)), "can_validate");
        var manifest = new ActionManifest("Validate", 1, false, List.of(), List.of());
        var context = RequestContext.system("tenant", "actor");
        var storage = new InMemoryStorageProvider();
        storage.applySchema(context, SCHEMA);
        var executor = new ActionExecutor().withParameterSchema(SCHEMA).withAuthorization((ctx, actor, type, values) -> true)
                .withSideEffects(invocation -> fail("No side effect was declared"));
        assertTrue(executor.execute(manifest, definition, context, new ActionActor("actor", Set.of()), Map.of("state", "READY"), storage).success());
        assertThrows(IllegalArgumentException.class, () -> executor.execute(manifest, definition, context,
                new ActionActor("actor", Set.of()), Map.of("state", "INVALID"), storage));
        assertEquals(1, storage.auditEntries(context).size());
    }

    @Test
    void optionalNullDoesNotAuthorizeUnknownTypesAndTypeSyntaxIsValidated() {
        var validator = new ActionParameterValidator(SCHEMA);
        var unknown = new ActionTypeDefinition("Invalid", List.of(new ActionParameter("value", "Unknown", false)), "can_invalid");
        assertFalse(validator.validate(unknown, Map.of()).isEmpty());
        for (String type : List.of("Int!", "[Int", "Int]", "[[Int!]", "[Int!!]", "[]", "A B")) {
            assertThrows(IllegalArgumentException.class, () -> new ActionParameter("value", type, false), type);
        }
        assertEquals("Status", new ActionParameter("value", "[[Status!]!]", false).baseType());
    }
}
