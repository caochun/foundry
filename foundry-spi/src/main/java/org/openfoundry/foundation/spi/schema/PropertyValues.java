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
                schema.enums().keySet().stream()).flatMap(stream -> stream).toList()) {
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
        for (var fields : java.util.stream.Stream.concat(schema.objectTypes().stream().map(ObjectTypeDefinition::properties),
                schema.linkTypes().stream().map(LinkTypeDefinition::properties)).toList()) {
            var fieldNames = new java.util.HashSet<String>();
            if (fields.stream().filter(PropertyDefinition::primary).count() != 1) throw new IllegalArgumentException("Exactly one primary property is required");
            for (var field : fields) {
                if (!field.name().matches("[A-Za-z][A-Za-z0-9_]*") || !fieldNames.add(field.name())) throw new IllegalArgumentException("Invalid or reserved property: " + field.name());
                String base = field.type().replace("[", "").replace("]", "").replace("!", "");
                if (!SCALARS.contains(base) && !schema.enums().containsKey(base)) throw new IllegalArgumentException("Unknown property type: " + field.type());
                if (field.primary() && (!field.type().equals("ID") || !field.required())) throw new IllegalArgumentException("Primary property must have type ID!");
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
                merged.put(property.name(), value(schema, property.type(), value, property.name()));
            }
        }
        return immutableMap(merged);
    }

    private static Object value(OntologySchema schema, String type, Object raw, String field) {
        try {
            if (type.startsWith("[") && type.endsWith("]")) {
                if (!(raw instanceof List<?> list)) throw invalid(field);
                String element = type.substring(1, type.length() - 1);
                boolean required = element.endsWith("!");
                if (required) element = element.substring(0, element.length() - 1);
                var result = new ArrayList<>();
                for (Object item : list) {
                    if (item == null && required) throw invalid(field);
                    result.add(item == null ? null : value(schema, element, item, field));
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
                case "JSON" -> copy(raw);
                default -> throw invalid(field);
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
        values.forEach((name, value) -> copy.put(name, copy(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object copy(Object raw) {
        if (raw == null || raw instanceof String || raw instanceof Boolean) return raw;
        if (raw instanceof Number number && Double.isFinite(number.doubleValue())) return raw;
        if (raw instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, value) -> {
                if (!(key instanceof String name)) throw new IllegalArgumentException("JSON keys must be strings");
                result.put(name, copy(value));
            });
            return Collections.unmodifiableMap(result);
        }
        if (raw instanceof List<?> list) return Collections.unmodifiableList(list.stream().map(PropertyValues::copy).toList());
        throw new IllegalArgumentException("Unsupported structured value");
    }

    public static String uniqueKey(OntologySchema schema, PropertyDefinition property, Object raw) {
        return canonical(value(schema, property.type(), raw, property.name()));
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
