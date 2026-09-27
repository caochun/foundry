package org.openfoundry.foundation.spi.schema;

import java.util.List;
import java.util.Objects;

public record OntologySchema(
        String namespace,
        String version,
        List<ObjectTypeDefinition> objectTypes,
        List<LinkTypeDefinition> linkTypes,
        List<ActionTypeDefinition> actionTypes, java.util.Map<String, java.util.List<String>> enums) {

    public OntologySchema(String namespace, String version, List<ObjectTypeDefinition> objects,
                          List<LinkTypeDefinition> links, List<ActionTypeDefinition> actions) {
        this(namespace, version, objects, links, actions, java.util.Map.of());
    }

    public OntologySchema {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be blank");
        }
        objectTypes = List.copyOf(Objects.requireNonNull(objectTypes, "objectTypes must not be null"));
        linkTypes = List.copyOf(Objects.requireNonNull(linkTypes, "linkTypes must not be null"));
        enums = enums.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(java.util.Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        actionTypes = List.copyOf(Objects.requireNonNull(actionTypes, "actionTypes must not be null"));
    }
}
