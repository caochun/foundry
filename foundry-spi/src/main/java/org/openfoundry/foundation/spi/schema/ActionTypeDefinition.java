package org.openfoundry.foundation.spi.schema;

import java.util.List;
import java.util.Objects;

public record ActionTypeDefinition(String name, List<ActionParameter> parameters, String permission) {
    public ActionTypeDefinition {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters must not be null"));
        if (permission != null && !permission.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid Action permission relation");
        }
    }

    public ActionTypeDefinition(String name, List<ActionParameter> parameters) {
        this(name, parameters, null);
    }
}
