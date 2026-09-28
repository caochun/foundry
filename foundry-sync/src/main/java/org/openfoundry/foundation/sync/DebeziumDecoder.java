package org.openfoundry.foundation.sync;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.LineageValues;
import org.openfoundry.foundation.spi.Provenance;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Kafka Connect JSON envelopes and schemaless Debezium row events. No source code or expressions are evaluated. */
public final class DebeziumDecoder {
    public record Binding(String topic, String database, String schema, String table) {
        public Binding {
            if (topic == null || topic.isBlank() || table == null || table.isBlank()) {
                throw new IllegalArgumentException("CDC topic and source table are required");
            }
            if (database != null && database.isBlank() || schema != null && schema.isBlank()) {
                throw new IllegalArgumentException("Source qualifiers must be nonblank");
            }
        }

        Map<String, Object> definition() {
            var result = new LinkedHashMap<String, Object>();
            result.put("topic", topic);
            result.put("database", database);
            result.put("schema", schema);
            result.put("table", table);
            return PropertyValues.immutableMap(result);
        }
    }

    public record Decoded(SourceRecord record, boolean tombstone) {}

    private final String datasource;
    private final String primaryField;
    private final Binding binding;
    private final ObjectMapper json;

    public DebeziumDecoder(String datasource, String primaryField, Binding binding) {
        if (datasource == null || datasource.isBlank() || primaryField == null || primaryField.isBlank()) {
            throw new IllegalArgumentException("Datasource and source key are required");
        }
        this.datasource = datasource;
        this.primaryField = primaryField;
        this.binding = Objects.requireNonNull(binding);
        var factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100).maxStringLength(1_000_000).maxNumberLength(1000).build()).build();
        json = new ObjectMapper(factory).enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public Decoded decode(CdcTransport.Message message) {
        if (!binding.topic().equals(message.topic())) {
            throw new IllegalArgumentException("CDC message belongs to another topic");
        }
        var key = message.keyJson() == null ? Map.<String, Object>of() : object(unwrap(parse(message.keyJson())), "key");
        Object primary = key.get(primaryField);
        if (message.valueJson() == null || message.valueJson().strip().equals("null")) {
            RecordMapper.canonicalId(primary);
            return new Decoded(null, true);
        }
        var envelope = object(unwrap(parse(message.valueJson())), "envelope");
        String operation = text(envelope, "op");
        if (!Set.of("c", "u", "d", "r").contains(operation)) {
            throw new IllegalArgumentException("Unsupported Debezium operation: " + operation);
        }
        var source = object(envelope.get("source"), "source");
        if (!binding.table().equals(text(source, "table"))
                || binding.database() != null && !binding.database().equals(source.get("db"))
                || binding.schema() != null && !binding.schema().equals(source.get("schema"))) {
            throw new IllegalArgumentException("CDC source identity disagrees with its binding");
        }
        Object raw = envelope.get(operation.equals("d") ? "before" : "after");
        var row = raw == null && operation.equals("d") ? new LinkedHashMap<String, Object>() : new LinkedHashMap<>(object(raw, "row"));
        if (primary == null) {
            primary = row.get(primaryField);
        }
        String id = RecordMapper.canonicalId(primary);
        if (row.containsKey(primaryField) && !id.equals(RecordMapper.canonicalId(row.get(primaryField)))) {
            throw new IllegalArgumentException("CDC key and row identity disagree");
        }
        row.put(primaryField, primary);
        Instant produced = timestamp(source);
        if (produced == null) {
            produced = timestamp(envelope);
        }
        if (produced == null) {
            produced = Objects.requireNonNull(message.timestamp(), "CDC message needs a stable source or broker timestamp");
        }
        String event = LineageValues.hash(true, List.of(message.topic(), message.partition(), message.offset()));
        var token = Map.<String, Object>of("format", "debezium-json-v1", "topic", message.topic(), "partition", message.partition(), "offset", message.offset());
        var position = new SourcePosition(partition(message.topic(), message.partition()), event, message.offset(), token);
        // Keep an envelope digest in provenance so the same offset cannot silently change unmapped input data.
        String digest = LineageValues.hash(true, java.util.Arrays.asList(key, envelope));
        var provenance = new Provenance(datasource, id, event, "debezium-json-v1", produced, datasource, digest);
        return new Decoded(new SourceRecord(datasource, id, switch (operation) {
            case "c" -> "INSERT";
            case "u" -> "UPDATE";
            case "d" -> "DELETE";
            default -> "UPSERT";
        }, produced, row, provenance, position), false);
    }

    public static String partition(String topic, int partition) {
        if (topic == null || topic.isBlank() || partition < 0) {
            throw new IllegalArgumentException("Invalid CDC partition");
        }
        return "cdc_" + LineageValues.hash(true, List.of(topic, partition));
    }

    private Object parse(String text) {
        if (text.length() > 8_000_000) {
            throw new IllegalArgumentException("CDC JSON message exceeds 8 million characters");
        }
        try {
            return json.readValue(text, Object.class);
        } catch (java.io.IOException failure) {
            // Do not include payload or parser diagnostics containing personal data in the public error message.
            throw new IllegalArgumentException("Invalid CDC JSON");
        }
    }

    private Object unwrap(Object value) {
        var root = object(value, "JSON root");
        if (root.containsKey("schema") && root.containsKey("payload")) {
            Object schema = root.get("schema");
            Object payload = convert(root.get("payload"), schema);
            // Some Debezium test fixtures provide the `after` row schema directly instead of the full envelope schema.
            if (payload instanceof Map<?, ?> map && map.containsKey("after") && schema instanceof Map<?, ?> definition
                    && definition.get("type") instanceof String type && type.equals("struct")
                    && !(definition.get("fields") instanceof List<?> fields && fields.stream().anyMatch(field -> field instanceof Map<?, ?> item && "op".equals(item.get("field"))))) {
                var envelope = new LinkedHashMap<String, Object>((Map<String, Object>) map);
                envelope.put("after", convert(map.get("after"), schema));
                if (map.get("before") != null) envelope.put("before", convert(map.get("before"), schema));
                return envelope;
            }
            return payload;
        }
        return root;
    }

    private Object convert(Object value, Object rawSchema) {
        if (value == null || rawSchema == null) {
            return value;
        }
        var schema = object(rawSchema, "Connect schema");
        String name = schema.get("name") instanceof String text ? text : "";
        if (name.equals("org.apache.kafka.connect.data.Decimal")) {
            int scale = Integer.parseInt(text(object(schema.get("parameters"), "decimal parameters"), "scale"));
            if (Math.abs((long) scale) > 1000) {
                throw new IllegalArgumentException("Decimal scale exceeds supported bound");
            }
            if (value instanceof Number) {
                return value;
            }
            if (!(value instanceof String encoded)) {
                throw new IllegalArgumentException("Invalid Connect decimal encoding");
            }
            return new BigDecimal(new BigInteger(Base64.getDecoder().decode(encoded)), scale);
        }
        if (name.equals("io.debezium.data.VariableScaleDecimal")) {
            var decimal = object(value, "variable decimal");
            long scale = integer(decimal.get("scale"));
            if (scale < -1000 || scale > 1000) {
                throw new IllegalArgumentException("Decimal scale exceeds supported bound");
            }
            return new BigDecimal(new BigInteger(Base64.getDecoder().decode(text(decimal, "value"))), (int) scale);
        }
        if (name.equals("io.debezium.time.Date") || name.equals("org.apache.kafka.connect.data.Date")) {
            return LocalDate.ofEpochDay(integer(value)).toString();
        }
        if (name.equals("io.debezium.time.Timestamp") || name.equals("org.apache.kafka.connect.data.Timestamp")) {
            return instant(integer(value), 1000).toString();
        }
        if (name.equals("io.debezium.time.MicroTimestamp")) {
            return instant(integer(value), 1_000_000).toString();
        }
        if (name.equals("io.debezium.time.NanoTimestamp")) {
            return instant(integer(value), 1_000_000_000).toString();
        }
        if (name.equals("io.debezium.time.Time") || name.equals("org.apache.kafka.connect.data.Time")) {
            return LocalTime.ofNanoOfDay(Math.multiplyExact(integer(value), 1_000_000)).toString();
        }
        if (name.equals("io.debezium.time.MicroTime")) {
            return LocalTime.ofNanoOfDay(Math.multiplyExact(integer(value), 1000)).toString();
        }
        if (name.equals("io.debezium.time.NanoTime")) {
            return LocalTime.ofNanoOfDay(integer(value)).toString();
        }
        String type = text(schema, "type");
        if (type.equals("struct")) {
            var original = object(value, "struct");
            var result = new LinkedHashMap<>(original);
            if (!(schema.get("fields") instanceof List<?> fields)) {
                throw new IllegalArgumentException("Connect struct fields are missing");
            }
            for (Object field : fields) {
                var definition = object(field, "field schema");
                String fieldName = text(definition, "field");
                if (original.containsKey(fieldName)) {
                    result.put(fieldName, convert(original.get(fieldName), definition));
                }
            }
            return result;
        }
        if (type.equals("array")) {
            if (!(value instanceof List<?> items)) {
                throw new IllegalArgumentException("Connect array expected");
            }
            var result = new ArrayList<>();
            for (Object item : items) {
                result.add(convert(item, schema.get("items")));
            }
            return result;
        }
        if (type.equals("map")) {
            var result = new LinkedHashMap<String, Object>();
            object(value, "map").forEach((key, item) -> result.put(key, convert(item, schema.get("values"))));
            return result;
        }
        // Zoned timestamp/time, UUID, enum, JSON and unknown named scalar declarations retain their JSON wire values.
        return value;
    }

    private static Instant timestamp(Map<String, Object> source) {
        for (var entry : Map.of("ts_ns", 1_000_000_000L, "ts_us", 1_000_000L, "ts_ms", 1000L).entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).toList()) {
            if (source.get(entry.getKey()) != null) {
                return instant(integer(source.get(entry.getKey())), entry.getValue());
            }
        }
        return null;
    }

    private static Instant instant(long value, long units) {
        return Instant.ofEpochSecond(Math.floorDiv(value, units), Math.floorMod(value, units) * (1_000_000_000 / units));
    }

    private static long integer(Object value) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException("CDC integer value expected");
        }
        return new BigDecimal(number.toString()).longValueExact();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException("CDC " + label + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    private static String text(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("CDC field is missing or invalid: " + key);
        }
        return text;
    }
}
