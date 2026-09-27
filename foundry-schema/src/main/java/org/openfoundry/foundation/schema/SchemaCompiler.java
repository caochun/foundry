package org.openfoundry.foundation.schema;

import org.openfoundry.foundation.spi.schema.ActionParameter;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;
import org.openfoundry.foundation.spi.schema.LinkTypeDefinition;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates and compiles an ontology schema into deterministic runtime metadata. */
public final class SchemaCompiler {
    public CompiledOntology compile(OntologySchema schema) {
        List<String> issues = validate(schema);
        if (!issues.isEmpty()) {
            throw new SchemaValidationException(issues);
        }

        Map<String, String> objects = schema.objectTypes().stream()
                .collect(HashMap::new, (map, type) -> map.put(type.name(), type.name()), Map::putAll);
        Map<String, String> links = schema.linkTypes().stream()
                .collect(HashMap::new, (map, type) -> map.put(type.name(), type.name()), Map::putAll);
        Map<String, String> actions = schema.actionTypes().stream()
                .collect(HashMap::new, (map, type) -> map.put(type.name(), type.name()), Map::putAll);

        return new CompiledOntology(schema, digest(schema), objects, links, actions);
    }

    public List<String> validate(OntologySchema schema) {
        List<String> issues = new ArrayList<>();
        try {
            new org.openfoundry.foundation.validation.PropertyValidator().validateSchema(schema);
        } catch (IllegalArgumentException invalid) {
            issues.add(invalid.getMessage());
        }
        Set<String> objectNames = new HashSet<>();
        Set<String> linkNames = new HashSet<>();
        Set<String> actionNames = new HashSet<>();

        for (ObjectTypeDefinition object : schema.objectTypes()) {
            if (!objectNames.add(object.name())) {
                issues.add("duplicate object type: " + object.name());
            }
            validateProperties("object " + object.name(), object.properties(), issues, true);
        }

        for (LinkTypeDefinition link : schema.linkTypes()) {
            if (!linkNames.add(link.name())) {
                issues.add("duplicate link type: " + link.name());
            }
            if (!objectNames.contains(link.fromType())) {
                issues.add("link " + link.name() + " references unknown from type: " + link.fromType());
            }
            if (!objectNames.contains(link.toType())) {
                issues.add("link " + link.name() + " references unknown to type: " + link.toType());
            }
            validateProperties("link " + link.name(), link.properties(), issues, true);
        }

        for (ActionTypeDefinition action : schema.actionTypes()) {
            if (!actionNames.add(action.name())) {
                issues.add("duplicate action type: " + action.name());
            }
            Set<String> parameterNames = new HashSet<>();
            for (ActionParameter parameter : action.parameters()) {
                if (Set.of("actor", "params", "now").contains(parameter.name())) issues.add("reserved Action parameter: " + parameter.name());
                String parameterType = parameter.baseType();
                if (!org.openfoundry.foundation.spi.schema.PropertyValues.SCALARS.contains(parameterType)
                        && !schema.enums().containsKey(parameterType) && !objectNames.contains(parameterType)) {
                    issues.add("unsupported Action parameter type: " + action.name() + "." + parameter.name() + ": " + parameter.type());
                }
                if (!parameterNames.add(parameter.name())) {
                    issues.add("duplicate parameter " + parameter.name() + " in action " + action.name());
                }
            }
        }

        Set<String> allNames = new HashSet<>();
        for (String name : java.util.stream.Stream.of(objectNames, linkNames, actionNames, schema.enums().keySet(), schema.interfaces().stream().map(org.openfoundry.foundation.spi.schema.InterfaceDefinition::name).collect(java.util.stream.Collectors.toSet())).flatMap(Set::stream).toList()) {
            if (!allNames.add(name)) issues.add("type name reused across kinds: " + name);
        }
        schema.enums().forEach((name, values) -> {
            if (values.isEmpty() || new HashSet<>(values).size() != values.size()) issues.add("invalid enum: " + name);
        });
        var fields = java.util.stream.Stream.concat(schema.objectTypes().stream().flatMap(type -> type.properties().stream()),
                schema.linkTypes().stream().flatMap(type -> type.properties().stream())).toList();
        for (var property : fields) {
            String base = property.type().replace("[", "").replace("]", "").replace("!", "");
            if (!org.openfoundry.foundation.spi.schema.PropertyValues.SCALARS.contains(base) && !schema.enums().containsKey(base)) {
                issues.add("unsupported property type: " + property.name() + ": " + property.type());
            }
            if (property.primary() && (!property.type().equals("ID") || !property.required())) issues.add("primary property must be ID!: " + property.name());
        }
        return List.copyOf(issues);
    }

    private static void validateProperties(String owner, List<PropertyDefinition> properties,
                                           List<String> issues, boolean requirePrimary) {
        Set<String> names = new HashSet<>();
        int primaryCount = 0;
        for (PropertyDefinition property : properties) {
            if (!names.add(property.name())) {
                issues.add("duplicate property " + property.name() + " in " + owner);
            }
            if (property.primary()) {
                primaryCount++;
            }
        }
        if (requirePrimary && primaryCount != 1) {
            issues.add(owner + " must have exactly one primary property, found " + primaryCount);
        }
    }

    private static String digest(OntologySchema schema) {
        String canonical = canonical(schema);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required", e);
        }
    }

    private static String canonical(OntologySchema schema) {
        StringBuilder result = new StringBuilder()
                .append(schema.namespace()).append('|').append(schema.version());
        schema.interfaces().stream().sorted(java.util.Comparator.comparing(org.openfoundry.foundation.spi.schema.InterfaceDefinition::name))
                .forEach(type -> result.append("|I:").append(type.name()).append(type.interfaces()).append(type.constraints()).append(properties(type.properties())).append(linkFields(type.linkFields())).append(computedFields(type.computedFields())));
        schema.enums().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.append("|E:").append(entry.getKey()).append(entry.getValue().stream().sorted().toList()));
        schema.objectTypes().stream().sorted(java.util.Comparator.comparing(ObjectTypeDefinition::name))
                .forEach(type -> result.append("|O:").append(type.name()).append(type.interfaces()).append(type.constraints()).append(properties(type.properties())).append(linkFields(type.linkFields())).append(computedFields(type.computedFields())));
        schema.linkTypes().stream().sorted(java.util.Comparator.comparing(LinkTypeDefinition::name))
                .forEach(type -> result.append("|L:").append(type.name()).append(':')
                        .append(type.fromType()).append(':').append(type.toType()).append(':')
                        .append(type.cardinality()).append(type.interfaces()).append(type.constraints()).append(properties(type.properties())).append(linkFields(type.linkFields())).append(computedFields(type.computedFields())));
        schema.actionTypes().stream().sorted(java.util.Comparator.comparing(ActionTypeDefinition::name))
                .forEach(type -> result.append("|A:").append(type.name()).append(':')
                        .append(type.permission()).append(':')
                        .append(type.parameters().stream().map(p -> p.name() + ':' + p.type() + ':' + p.required())
                                .sorted().toList()));
        return result.toString();
    }

    private static String computedFields(List<org.openfoundry.foundation.spi.schema.ComputedFieldDefinition> fields) {
        if (fields.isEmpty()) return "";
        return "|C:" + fields.stream().sorted(java.util.Comparator.comparing(org.openfoundry.foundation.spi.schema.ComputedFieldDefinition::name))
                .map(field -> org.openfoundry.foundation.spi.schema.PropertyValues.canonical(java.util.Arrays.asList(field.name(), field.type(),
                        field.required(), field.function(), field.arguments(), field.cache().name(), field.ttl(), field.sensitive()))).toList().toString();
    }

    private static String linkFields(List<org.openfoundry.foundation.spi.schema.LinkFieldDefinition> fields) {
        return fields.stream().sorted(java.util.Comparator.comparing(org.openfoundry.foundation.spi.schema.LinkFieldDefinition::name))
                .map(Object::toString).toList().toString();
    }

    private static String properties(List<PropertyDefinition> properties) {
        return properties.stream().map(p -> p.name() + ':' + p.type() + ':' + p.required() + ':'
                        + p.primary() + ':' + p.unique() + ':' + p.indexed() + ':'
                        + p.sensitive() + ':' + p.immutable() + ':' + p.readOnly() + ':' + p.hasDefault() + ':'
                        + org.openfoundry.foundation.spi.schema.PropertyValues.canonical(p.defaultValue()) + ':' + p.constraints())
                .sorted().toList().toString();
    }
}
