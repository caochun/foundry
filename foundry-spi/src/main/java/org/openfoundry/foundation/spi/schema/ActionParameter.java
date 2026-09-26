package org.openfoundry.foundation.spi.schema;

public record ActionParameter(String name, String type, boolean required) {
    public ActionParameter {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
    }
}
