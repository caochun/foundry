package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.schema.ActionParameter;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ActionParameterValidator {
    public List<String> validate(ActionTypeDefinition definition, Map<String, Object> parameters) {
        List<String> errors = new ArrayList<>();
        for (ActionParameter parameter : definition.parameters()) {
            Object value = parameters.get(parameter.name());
            if (parameter.required() && value == null) {
                errors.add("missing required Action parameter: " + parameter.name());
            }
        }
        return List.copyOf(errors);
    }
}
