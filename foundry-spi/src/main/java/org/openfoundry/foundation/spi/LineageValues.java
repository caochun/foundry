package org.openfoundry.foundation.spi;

import org.openfoundry.foundation.spi.schema.PropertyDefinition;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** One definition of changed/present values for both providers, including null versus absence. */
public final class LineageValues {
    private LineageValues() {}

    public record Value(boolean present, String hash) {}

    public static Map<String, Value> changes(List<PropertyDefinition> definitions, Map<String, Object> previous,
                                              Map<String, Object> current, EntityOperation operation) {
        var result = new TreeMap<String, Value>();
        if (operation != EntityOperation.UPDATED) {
            boolean alive = operation != EntityOperation.DELETED;
            result.put("_entity", new Value(alive, hash(alive, alive ? true : null)));
        }
        for (var field : definitions) {
            if (field.primary() || field.name().startsWith("_")) continue;
            boolean before = previous != null && previous.containsKey(field.name());
            boolean after = operation != EntityOperation.DELETED && current.containsKey(field.name());
            String oldHash = hash(before, before ? previous.get(field.name()) : null);
            String newHash = hash(after, after ? current.get(field.name()) : null);
            if (operation == EntityOperation.RESTORED || !oldHash.equals(newHash)) {
                if (before || after) result.put(field.name(), new Value(after, newHash));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public static String hash(boolean present, Object value) {
        try {
            String canonical = PropertyValues.canonical(Arrays.asList(present, present ? value : null));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static List<FieldProvenance> select(List<FieldProvenance> records, LineageQuery query) {
        return records.stream().filter(record -> query.field() == null || query.field().equals(record.field()))
                .filter(record -> query.beforeSequence() == null || record.sequence() < query.beforeSequence())
                .sorted(Comparator.comparingLong(FieldProvenance::sequence).reversed()).limit(query.limit()).toList();
    }

    public static Map<String, FieldProvenance> latest(List<FieldProvenance> records) {
        var result = new LinkedHashMap<String, FieldProvenance>();
        records.stream().sorted(Comparator.comparingLong(FieldProvenance::sequence).reversed()).forEach(record -> result.putIfAbsent(record.field(), record));
        return Collections.unmodifiableMap(result);
    }
}
