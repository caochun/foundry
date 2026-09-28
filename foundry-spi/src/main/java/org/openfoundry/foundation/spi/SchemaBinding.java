package org.openfoundry.foundation.spi;

import org.openfoundry.foundation.spi.schema.OntologySchema;
import java.util.Objects;

/** Opaque activation identity plus the immutable model it names; never supplied by an API client. */
public record SchemaBinding(String id, OntologySchema schema) {
    public SchemaBinding {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Schema binding ID is required");
        Objects.requireNonNull(schema);
    }
}
