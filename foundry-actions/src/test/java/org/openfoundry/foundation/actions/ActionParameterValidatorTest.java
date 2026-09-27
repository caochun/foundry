package org.openfoundry.foundation.actions;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.schema.ActionParameter;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ActionParameterValidatorTest {
    @Test
    void reportsMissingRequiredParameters() {
        var definition = new ActionTypeDefinition("Rename", List.of(
                new ActionParameter("person", "Person", true),
                new ActionParameter("reason", "String", false)));
        assertEquals(List.of("missing required Action parameter: person"),
                new ActionParameterValidator().validate(definition, Map.of()));
    }
    @Test
    void rejectsUnexpectedTypedAndReservedParameters() {
        var definition = new ActionTypeDefinition("Typed", List.of(
                new ActionParameter("values", "[Int]", true), new ActionParameter("date", "DateTime", true)));
        var validator = new ActionParameterValidator();
        assertEquals(List.of(), validator.validate(definition, Map.of("values", List.of(1, 2), "date", "2026-09-27T00:00:00Z")));
        org.junit.jupiter.api.Assertions.assertFalse(validator.validate(definition,
                Map.of("values", List.of(1, "two"), "date", "yesterday", "extra", true)).isEmpty());
        var reserved = new ActionTypeDefinition("Spoof", List.of(new ActionParameter("actor", "JSON", true)));
        org.junit.jupiter.api.Assertions.assertFalse(validator.validate(reserved, Map.of("actor", Map.of("roles", List.of("admin")))).isEmpty());
    }

    @Test
    void refusesUnsupportedEffectsInsteadOfSilentlyIgnoringThem() {
        var parser = new ActionManifestParser();
        org.junit.jupiter.api.Assertions.assertThrows(ActionParseException.class, () -> parser.parse("""
                action: HiddenSideEffect
                version: 1
                sideEffects: []
                effects: []
                """));
        org.junit.jupiter.api.Assertions.assertThrows(ActionParseException.class, () -> parser.parse("""
                action: UnsupportedDelete
                version: 1
                effects:
                  - type: deleteLink
                    linkType: Related
                    linkId: edge
                    filter: {active: true}
                """));
    }
}
