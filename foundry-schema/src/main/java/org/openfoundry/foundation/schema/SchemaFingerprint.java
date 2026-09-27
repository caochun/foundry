package org.openfoundry.foundation.schema;

import org.openfoundry.foundation.spi.schema.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Versioned registry identity. Does not change compiler/receipt digest compatibility. */
public final class SchemaFingerprint {
    private SchemaFingerprint() {}

    public static String of(OntologySchema schema) {
        var canonical = new OntologySchema(schema.namespace(), schema.version(),
                schema.objectTypes().stream().sorted(Comparator.comparing(ObjectTypeDefinition::name)).toList(),
                schema.linkTypes().stream().sorted(Comparator.comparing(LinkTypeDefinition::name)).toList(),
                schema.actionTypes().stream().sorted(Comparator.comparing(ActionTypeDefinition::name)).toList(), schema.enums(),
                schema.interfaces().stream().sorted(Comparator.comparing(InterfaceDefinition::name)).toList());
        try {
            // Nested declaration order is retained, especially Action parameters and ordering inputs.
            String value = "registry-schema-v1:" + PropertyValues.canonical(value(canonical));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static Object value(Object raw) {
        if (raw == null || raw instanceof String || raw instanceof Boolean || raw instanceof Number) return raw;
        if (raw instanceof Enum<?> enumeration) return enumeration.name();
        if (raw instanceof List<?> list) return list.stream().map(SchemaFingerprint::value).toList();
        if (raw instanceof Map<?, ?> map) {
            var result = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> result.put((String) key, value(item)));
            return result;
        }
        if (raw.getClass().isRecord()) {
            var result = new LinkedHashMap<String, Object>();
            for (var component : raw.getClass().getRecordComponents()) {
                try { result.put(component.getName(), value(component.getAccessor().invoke(raw))); }
                catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot fingerprint schema record", failure); }
            }
            return result;
        }
        throw new IllegalArgumentException("Unsupported schema value");
    }
}
