package org.openfoundry.foundation.actions;

import org.openfoundry.foundation.spi.ObjectRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Length-delimited, ordered encoding; object references bind identity, not mutable server snapshots. */
final class ActionFingerprint {
    private ActionFingerprint() {}

    static String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encode(value).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String encode(Object value) {
        if (value == null) return "null;";
        if (value instanceof ObjectRecord object) return encode(List.of("object", object.tenantId(), object.type(), object.id()));
        if (value instanceof String text) return "s" + text.length() + ":" + text;
        if (value instanceof Boolean bool) return "b" + bool + ";";
        if (value instanceof Number number) return "n" + number.getClass().getSimpleName() + ":" + number + ";";
        if (value instanceof List<?> list) {
            return "l" + list.size() + ":" + list.stream().map(ActionFingerprint::encode).collect(java.util.stream.Collectors.joining());
        }
        if (value instanceof Map<?, ?> map) {
            if (map.keySet().stream().anyMatch(key -> !(key instanceof String))) throw new IllegalArgumentException("Map keys must be strings");
            String content = map.entrySet().stream().sorted(Comparator.comparing(entry -> (String) entry.getKey()))
                    .map(entry -> encode(entry.getKey()) + encode(entry.getValue())).collect(java.util.stream.Collectors.joining());
            return "m" + map.size() + ":" + content;
        }
        if (value.getClass().isRecord()) {
            var fields = new java.util.TreeMap<String, Object>();
            try {
                for (var field : value.getClass().getRecordComponents()) fields.put(field.getName(), field.getAccessor().invoke(value));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalArgumentException("Cannot fingerprint declaration", failure);
            }
            return encode(value.getClass().getName()) + encode(fields);
        }
        throw new IllegalArgumentException("Unsupported fingerprint value");
    }
}
