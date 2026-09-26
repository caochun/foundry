package org.openfoundry.foundation.schema;

public record MigrationPlan(String description, boolean approved) {
    public MigrationPlan {
        if (description == null || description.isBlank()) throw new IllegalArgumentException("description must not be blank");
    }
}
