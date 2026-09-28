package org.openfoundry.foundation.spi.schema;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Shared object/link attribute validation; domain-specific rules remain above the SPI. */
public final class PropertyValues {
    public static final Set<String> SCALARS = Set.of("ID", "String", "Int", "Float", "Boolean", "Date", "DateTime", "Duration", "URI", "GeoPoint", "JSON");
    private PropertyValues() {}

    public static void requireSchema(OntologySchema schema) {
        var names = new java.util.HashSet<String>();
        for (String name : java.util.stream.Stream.of(schema.objectTypes().stream().map(ObjectTypeDefinition::name),
                schema.linkTypes().stream().map(LinkTypeDefinition::name), schema.actionTypes().stream().map(ActionTypeDefinition::name),
                schema.enums().keySet().stream(), schema.interfaces().stream().map(InterfaceDefinition::name), schema.scalars().stream().map(ScalarDefinition::name)).flatMap(stream -> stream).toList()) {
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || !names.add(name)) throw new IllegalArgumentException("Invalid or duplicate schema type: " + name);
        }
        schema.enums().forEach((name, values) -> {
            if (values.isEmpty() || new java.util.HashSet<>(values).size() != values.size()
                    || values.stream().anyMatch(value -> !value.matches("[A-Za-z_][A-Za-z0-9_]*"))) throw new IllegalArgumentException("Invalid enum: " + name);
        });
        var objectNames = schema.objectTypes().stream().map(ObjectTypeDefinition::name).collect(java.util.stream.Collectors.toSet());
        for (var link : schema.linkTypes()) {
            if (!objectNames.contains(link.fromType()) || !objectNames.contains(link.toType())) throw new IllegalArgumentException("Unknown relationship endpoint type");
        }
        requireLinkFields(schema);
        requireComputedFields(schema);
        var interfaces = new LinkedHashMap<String, InterfaceDefinition>();
        schema.interfaces().forEach(type -> interfaces.put(type.name(), type));
        var completed = new java.util.HashSet<String>();
        for (var type : schema.interfaces()) {
            requireFields(schema, type.properties(), false);
            requireInterface(type.name(), interfaces, new java.util.HashSet<>(), completed);
        }
        for (var type : schema.objectTypes()) {
            requireFields(schema, type.properties(), true);
            requireInheritance(type.properties(), type.interfaces(), type.constraints(), interfaces);
        }
        for (var type : schema.linkTypes()) {
            requireFields(schema, type.properties(), true);
            requireInheritance(type.properties(), type.interfaces(), type.constraints(), interfaces);
        }
    }

    private static void requireLinkFields(OntologySchema schema) {
        var links = new LinkedHashMap<String, LinkTypeDefinition>();
        schema.linkTypes().forEach(link -> links.put(link.name(), link));
        var interfaces = new LinkedHashMap<String, InterfaceDefinition>();
        schema.interfaces().forEach(type -> interfaces.put(type.name(), type));
        for (var type : schema.interfaces()) {
            requireLinkFields(null, type.properties(), type.linkFields(), type.interfaces(), links, interfaces);
        }
        for (var type : schema.objectTypes()) {
            requireLinkFields(type.name(), type.properties(), type.linkFields(), type.interfaces(), links, interfaces);
        }
        for (var type : schema.linkTypes()) {
            requireLinkFields(type.name(), type.properties(), type.linkFields(), type.interfaces(), links, interfaces);
        }
    }

    private static void requireLinkFields(String owner, List<PropertyDefinition> properties,
                                          List<LinkFieldDefinition> fields, List<String> parents,
                                          Map<String, LinkTypeDefinition> links, Map<String, InterfaceDefinition> interfaces) {
        var names = new java.util.HashSet<String>();
        properties.forEach(field -> names.add(field.name()));
        for (String parentName : parents) {
            var parent = interfaces.get(parentName);
            if (parent == null || !fields.containsAll(parent.linkFields())) {
                throw new IllegalArgumentException("Unresolved relationship field inheritance: " + parentName);
            }
        }
        for (var field : fields) {
            if (!names.add(field.name())) {
                throw new IllegalArgumentException("Duplicate property or relationship field: " + field.name());
            }
            var link = links.get(field.linkType());
            if (link == null) throw new IllegalArgumentException("Unknown relationship type: " + field.linkType());
            boolean outbound = field.direction() == org.openfoundry.foundation.spi.StorageProvider.Direction.OUTBOUND;
            String source = outbound ? link.fromType() : link.toType();
            String target = outbound ? link.toType() : link.fromType();
            if (owner != null && !source.equals(owner)) {
                throw new IllegalArgumentException("Relationship field direction does not match its owner: " + field.name());
            }
            if (!field.targetType().equals(target) && !field.targetType().equals(link.name())) {
                throw new IllegalArgumentException("Relationship field must return its opposite endpoint or the link: " + field.name());
            }
            boolean single = link.cardinality() == Cardinality.ONE_TO_ONE
                    || outbound && link.cardinality() == Cardinality.MANY_TO_ONE
                    || !outbound && link.cardinality() == Cardinality.ONE_TO_MANY;
            if (!field.many() && !single) {
                throw new IllegalArgumentException("Relationship cardinality requires a list field: " + field.name());
            }
        }
    }

    private static void requireComputedFields(OntologySchema schema) {
        var links = new LinkedHashMap<String, LinkTypeDefinition>();
        schema.linkTypes().forEach(type -> links.put(type.name(), type));
        var interfaces = new LinkedHashMap<String, InterfaceDefinition>();
        schema.interfaces().forEach(type -> interfaces.put(type.name(), type));
        schema.interfaces().forEach(type -> requireComputedFields(null, type.properties(), type.linkFields(), type.computedFields(), type.interfaces(), links, interfaces));
        schema.objectTypes().forEach(type -> requireComputedFields(type.name(), type.properties(), type.linkFields(), type.computedFields(), type.interfaces(), links, interfaces));
        schema.linkTypes().forEach(type -> requireComputedFields(type.name(), type.properties(), type.linkFields(), type.computedFields(), type.interfaces(), links, interfaces));
    }

    private static void requireComputedFields(String owner, List<PropertyDefinition> properties, List<LinkFieldDefinition> navigation,
                                              List<ComputedFieldDefinition> fields, List<String> parents,
                                              Map<String, LinkTypeDefinition> links, Map<String, InterfaceDefinition> interfaces) {
        var names = new java.util.HashSet<String>();
        properties.forEach(field -> names.add(field.name()));
        navigation.forEach(field -> names.add(field.name()));
        for (String parentName : parents) {
            var parent = interfaces.get(parentName);
            if (parent == null || !fields.containsAll(parent.computedFields())) throw new IllegalArgumentException("Unresolved computed field inheritance: " + parentName);
        }
        for (var field : fields) {
            if (!names.add(field.name())) throw new IllegalArgumentException("Conflicting stored, relationship or computed field: " + field.name());
            if (!field.function().equals("countLinks")) throw new IllegalArgumentException("Unknown computed function: " + field.function());
            if (field.cache() != ComputedFieldDefinition.Cache.LAZY || field.ttl() != null) throw new IllegalArgumentException("Only LAZY computed fields are implemented");
            if (!field.type().equals("Int")) throw new IllegalArgumentException("countLinks must return Int");
            if (!Set.of("type", "direction").containsAll(field.arguments().keySet())) throw new IllegalArgumentException("Unknown countLinks argument");
            var link = links.get(field.linkType());
            if (link == null) throw new IllegalArgumentException("Unknown computed relationship: " + field.linkType());
            String endpoint = field.direction() == org.openfoundry.foundation.spi.StorageProvider.Direction.INBOUND ? link.toType() : link.fromType();
            if (owner != null && !owner.equals(endpoint)) throw new IllegalArgumentException("Computed relationship direction does not match owner: " + field.name());
        }
    }

    private static void requireFields(OntologySchema schema, List<PropertyDefinition> fields, boolean concrete) {
        var fieldNames = new java.util.HashSet<String>();
        long primaryCount = fields.stream().filter(PropertyDefinition::primary).count();
        if (concrete ? primaryCount != 1 : primaryCount > 1) {
            throw new IllegalArgumentException("Concrete types require one primary property; interfaces allow at most one");
        }
        for (var field : fields) {
            if (!field.name().matches("[A-Za-z][A-Za-z0-9_]*") || !fieldNames.add(field.name())) {
                throw new IllegalArgumentException("Invalid or reserved property: " + field.name());
            }
            String base = field.type();
            while (base.startsWith("[") && base.endsWith("]")) {
                base = base.substring(1, base.length() - 1);
                if (base.endsWith("!")) base = base.substring(0, base.length() - 1);
            }
            if (!schema.isScalar(base) && !schema.enums().containsKey(base)) {
                throw new IllegalArgumentException("Unknown property type: " + field.type());
            }
            if (field.primary() && (!field.type().equals("ID") || !field.required())) {
                throw new IllegalArgumentException("Primary property must have type ID!");
            }
        }
    }

    private static void requireInterface(String name, Map<String, InterfaceDefinition> interfaces,
                                         Set<String> visiting, Set<String> completed) {
        if (completed.contains(name)) return;
        var type = interfaces.get(name);
        if (type == null) throw new IllegalArgumentException("Unknown interface: " + name);
        if (!visiting.add(name)) throw new IllegalArgumentException("Cyclic interface: " + name);
        for (String parent : type.interfaces()) requireInterface(parent, interfaces, visiting, completed);
        requireInheritance(type.properties(), type.interfaces(), type.constraints(), interfaces);
        visiting.remove(name);
        completed.add(name);
    }

    private static void requireInheritance(List<PropertyDefinition> fields, List<String> parents,
                                           List<String> constraints, Map<String, InterfaceDefinition> interfaces) {
        if (new java.util.HashSet<>(parents).size() != parents.size()) {
            throw new IllegalArgumentException("Duplicate implemented interface");
        }
        for (String parentName : parents) {
            var parent = interfaces.get(parentName);
            if (parent == null) throw new IllegalArgumentException("Unknown interface: " + parentName);
            if (!fields.containsAll(parent.properties()) || !constraints.containsAll(parent.constraints())
                    || !parents.containsAll(parent.interfaces())) {
                throw new IllegalArgumentException("Unresolved or conflicting interface inheritance: " + parentName);
            }
        }
    }

    public static Map<String, Object> validate(OntologySchema schema, List<PropertyDefinition> definitions, String id,
                                                Map<String, Object> patch, Map<String, Object> previous) {
        Objects.requireNonNull(patch, "properties");
        if (id == null || id.isBlank() || id.length() > 512) {
            String primary = definitions.stream().filter(PropertyDefinition::primary).map(PropertyDefinition::name).findFirst().orElse("id");
            throw new PropertyValidationException("INVALID_PRIMARY_ID", primary);
        }
        var byName = new LinkedHashMap<String, PropertyDefinition>();
        definitions.forEach(property -> byName.put(property.name(), property));
        for (String name : patch.keySet()) {
            var property = byName.get(name);
            if (property == null) throw new PropertyValidationException("UNKNOWN_PROPERTY", name);
            if (previous != null && (property.immutable() || property.primary())) throw new PropertyValidationException("IMMUTABLE_PROPERTY", name);
            if (property.primary() && !Objects.equals(id, patch.get(name))) throw new PropertyValidationException("PRIMARY_ID_MISMATCH", name);
        }
        var merged = new LinkedHashMap<String, Object>();
        if (previous != null) merged.putAll(previous);
        merged.putAll(patch);
        for (String name : merged.keySet()) if (!byName.containsKey(name)) throw new PropertyValidationException("UNKNOWN_PROPERTY", name);
        for (var property : definitions) {
            if (property.primary()) {
                if (merged.containsKey(property.name()) && !Objects.equals(id, merged.get(property.name()))) throw new PropertyValidationException("PRIMARY_ID_MISMATCH", property.name());
                continue;
            }
            Object value = merged.get(property.name());
            if (value == null) {
                if (property.required()) throw new PropertyValidationException("REQUIRED_PROPERTY", property.name());
            } else {
                merged.put(property.name(), normalize(schema, property.type(), value, property.name()));
            }
        }
        return immutableMap(merged);
    }

    public static Object normalize(OntologySchema schema, String type, Object raw, String field) {
        try {
            if (type.startsWith("[") && type.endsWith("]")) {
                if (!(raw instanceof List<?> list)) throw invalid(field);
                String element = type.substring(1, type.length() - 1);
                boolean required = element.endsWith("!");
                if (required) element = element.substring(0, element.length() - 1);
                var result = new ArrayList<>();
                for (Object item : list) {
                    if (item == null && required) throw invalid(field);
                    result.add(item == null ? null : normalize(schema, element, item, field));
                }
                return Collections.unmodifiableList(result);
            }
            if (schema.enums().containsKey(type)) {
                if (!(raw instanceof String text) || !schema.enums().get(type).contains(text)) throw invalid(field);
                return raw;
            }
            return switch (type) {
                case "ID", "String" -> {
                    if (!(raw instanceof String)) throw invalid(field);
                    yield raw;
                }
                case "Int" -> {
                    if (!(raw instanceof Integer || raw instanceof Long || raw instanceof Short || raw instanceof Byte)) throw invalid(field);
                    long number = ((Number) raw).longValue();
                    if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) throw invalid(field);
                    yield (int) number;
                }
                case "Float" -> {
                    if (!(raw instanceof Number number) || !Double.isFinite(number.doubleValue())) throw invalid(field);
                    yield number.doubleValue();
                }
                case "Boolean" -> {
                    if (!(raw instanceof Boolean)) throw invalid(field);
                    yield raw;
                }
                case "Date" -> raw instanceof LocalDate date ? date.toString() : LocalDate.parse((String) raw).toString();
                case "DateTime" -> raw instanceof Instant instant ? instant.toString() : OffsetDateTime.parse((String) raw).toInstant().toString();
                case "Duration" -> raw instanceof Duration duration ? duration.toString() : Duration.parse((String) raw).toString();
                case "URI" -> {
                    var uri = java.net.URI.create((String) raw);
                    if (!uri.isAbsolute()) throw invalid(field);
                    yield uri.toString();
                }
                case "GeoPoint" -> {
                    if (!(raw instanceof Map<?, ?> point) || !point.keySet().equals(Set.of("lat", "lon"))
                            || !(point.get("lat") instanceof Number lat) || !(point.get("lon") instanceof Number lon)
                            || !Double.isFinite(lat.doubleValue()) || !Double.isFinite(lon.doubleValue())
                            || Math.abs(lat.doubleValue()) > 90 || Math.abs(lon.doubleValue()) > 180) throw invalid(field);
                    yield Map.of("lat", lat.doubleValue(), "lon", lon.doubleValue());
                }
                case "JSON" -> immutableValue(raw);
                default -> {
                    if (!schema.isScalar(type)) throw invalid(field);
                    yield immutableValue(raw);
                }
            };
        } catch (PropertyValidationException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw invalid(field);
        }
    }

    private static PropertyValidationException invalid(String field) {
        return new PropertyValidationException("INVALID_PROPERTY_TYPE", field);
    }

    public static Map<String, Object> immutableMap(Map<String, Object> values) {
        var copy = new LinkedHashMap<String, Object>();
        values.forEach((name, value) -> copy.put(name, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    public static Object immutableValue(Object raw) {
        if (raw == null || raw instanceof String || raw instanceof Boolean) return raw;
        if (raw instanceof Number number && Double.isFinite(number.doubleValue())) {
            if (raw instanceof Byte || raw instanceof Short || raw instanceof Integer || raw instanceof Long
                    || raw instanceof Float || raw instanceof Double || raw.getClass() == java.math.BigInteger.class || raw.getClass() == BigDecimal.class) return raw;
            throw new IllegalArgumentException("Mutable or custom numeric JSON values are not supported");
        }
        if (raw instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, value) -> {
                if (!(key instanceof String name)) throw new IllegalArgumentException("JSON keys must be strings");
                result.put(name, immutableValue(value));
            });
            return Collections.unmodifiableMap(result);
        }
        if (raw instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(PropertyValues::immutableValue).toList());
        throw new IllegalArgumentException("Unsupported structured value");
    }

    public static String uniqueKey(OntologySchema schema, PropertyDefinition property, Object raw) {
        return canonical(normalize(schema, property.type(), raw, property.name()));
    }

    public static String canonical(Object value) {
        if (value == null) return "null;";
        if (value instanceof String text) return "s" + text.length() + ":" + text;
        if (value instanceof Boolean bool) return "b" + bool + ";";
        if (value instanceof Number number) return "n" + new BigDecimal(number.toString()).stripTrailingZeros().toPlainString() + ";";
        if (value instanceof List<?> list) return "l" + list.size() + ":" + list.stream().map(PropertyValues::canonical).collect(java.util.stream.Collectors.joining());
        if (value instanceof Map<?, ?> map) {
            var ordered = new TreeMap<String, Object>();
            map.forEach((key, item) -> ordered.put((String) key, item));
            return "m" + ordered.size() + ":" + ordered.entrySet().stream().map(entry -> canonical(entry.getKey()) + canonical(entry.getValue())).collect(java.util.stream.Collectors.joining());
        }
        throw new IllegalArgumentException("Unsupported unique value");
    }
}
