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
}
