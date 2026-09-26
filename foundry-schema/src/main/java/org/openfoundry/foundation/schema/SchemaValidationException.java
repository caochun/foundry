package org.openfoundry.foundation.schema;

import java.util.List;

public final class SchemaValidationException extends RuntimeException {
    private final List<String> issues;

    public SchemaValidationException(List<String> issues) {
        super(String.join("; ", issues));
        this.issues = List.copyOf(issues);
    }

    public List<String> issues() {
        return issues;
    }
}
