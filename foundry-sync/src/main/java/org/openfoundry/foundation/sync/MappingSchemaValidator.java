package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.schema.*;

import java.util.List;
import java.util.Map;

/** Binds declarations before extraction; resolving a foreign key by an arbitrary attribute is not implied. */
public final class MappingSchemaValidator {
    private MappingSchemaValidator() {}

    public static void validate(MappingConfig mapping, OntologySchema schema) {
        var object = object(schema, mapping.objectType());
        key(mapping.primaryKey(), object.properties());
        properties(mapping.properties(), object.properties());
        for (var link : mapping.links()) {
            var definition = schema.linkTypes().stream().filter(type -> type.name().equals(link.linkType())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown mapped relationship type: " + link.linkType()));
            if (!definition.fromType().equals(mapping.objectType()) || !definition.toType().equals(link.toType())) {
                throw new IllegalArgumentException("Mapped relationship endpoints do not match the schema");
            }
            key(link.toKey(), object(schema, link.toType()).properties());
            properties(link.properties(), definition.properties());
        }
    }

    private static ObjectTypeDefinition object(OntologySchema schema, String name) {
        return schema.objectTypes().stream().filter(type -> type.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown mapped object type: " + name));
    }
    private static void key(KeyMapping mapping, List<PropertyDefinition> fields) {
        if (mapping.target() == null) return; // Old Java constructor selected the identity without naming its field.
        var primary = fields.stream().filter(PropertyDefinition::primary).findFirst().orElseThrow();
        if (!primary.name().equals(mapping.target())) throw new IllegalArgumentException("Mapping key target must name the declared primary field");
    }
    private static void properties(Map<String, PropertyMapping> mappings, List<PropertyDefinition> definitions) {
        for (String name : mappings.keySet()) {
            var field = definitions.stream().filter(value -> value.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown mapped property: " + name));
            if (field.readOnly()) throw new IllegalArgumentException("Cannot map a managed readonly property: " + name);
        }
    }
}
