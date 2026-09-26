package org.openfoundry.foundation.schema;

import org.openfoundry.foundation.spi.schema.OntologySchema;

import java.util.Map;

/** Deterministic intermediate representation consumed by runtime layers. */
public record CompiledOntology(
        OntologySchema schema,
        String schemaDigest,
        Map<String, String> objectTypes,
        Map<String, String> linkTypes,
        Map<String, String> actionTypes) {
    public CompiledOntology {
        objectTypes = Map.copyOf(objectTypes);
        linkTypes = Map.copyOf(linkTypes);
        actionTypes = Map.copyOf(actionTypes);
    }
}
