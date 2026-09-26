package org.openfoundry.foundation.spi.schema;

import java.util.List;
import java.util.Objects;

public record OntologySchema(
        String namespace,
        String version,
        List<ObjectTypeDefinition> objectTypes,
        List<LinkTypeDefinition> linkTypes,
        List<ActionTypeDefinition> actionTypes) {

    public OntologySchema {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        objectTypes = List.copyOf(Objects.requireNonNull(objectTypes, "objectTypes must not be null"));
        linkTypes = List.copyOf(Objects.requireNonNull(linkTypes, "linkTypes must not be null"));
        actionTypes = List.copyOf(Objects.requireNonNull(actionTypes, "actionTypes must not be null"));
    }
}
