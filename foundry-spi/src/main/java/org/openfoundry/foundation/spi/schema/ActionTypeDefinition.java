package org.openfoundry.foundation.spi.schema;

import java.util.List;
import java.util.Objects;

public record ActionTypeDefinition(String name, List<ActionParameter> parameters) {
    public ActionTypeDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters must not be null"));
    }
}
