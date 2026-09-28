package org.openfoundry.foundation.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.Provenance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Read-only REST connector for JSON object or array responses. */
public final class RestConnector implements Connector {
    private final String name;
    private final String sourceSystem;
    private final HttpClient client;
    private final ObjectMapper mapper;

    public RestConnector(String name, String sourceSystem) {
        this(name, sourceSystem, HttpClient.newHttpClient(), exactMapper());
    }

    RestConnector(String name, String sourceSystem, HttpClient client, ObjectMapper mapper) {
        this.name = name;
        this.sourceSystem = sourceSystem;
        this.client = client;
        this.mapper = mapper;
    }

    private static ObjectMapper exactMapper() {
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addDeserializer(Number.class, new com.fasterxml.jackson.databind.JsonDeserializer<Number>() {
            @Override public Number deserialize(com.fasterxml.jackson.core.JsonParser parser, com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
                return parser.currentToken() == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT ? parser.getNumberValue()
                        : org.openfoundry.foundation.spi.schema.PropertyValues.jsonDecimal(parser.getDecimalValue());
            }
        });
        return new ObjectMapper().registerModule(module);
    }

    @Override
    public String name() { return name; }

    @Override
    public Stream<SourceRecord> read(SourceQuery query) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(query.resource())).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("HTTP status " + response.statusCode());
            List<Map<String, Object>> rows = parseRows(response.body());
            Instant observed = Instant.now();
            return rows.stream().map(row -> {
                Object id = row.getOrDefault("id", row.get("_id"));
                if (id == null) throw new IllegalStateException("REST row has no id field");
                String sourceId = RecordMapper.canonicalId(id);
                return new SourceRecord(sourceSystem, sourceId, "UPSERT", observed,
                        row, new Provenance(sourceSystem, sourceId, null, "rest", observed, name, null));
            });
        } catch (Exception exception) {
            throw new IllegalStateException("REST connector failed: " + name, exception);
        }
    }

    private List<Map<String, Object>> parseRows(String json) throws Exception {
        if (json.trim().startsWith("[")) return mapper.readValue(json, new TypeReference<>() {});
        Map<String, Object> object = mapper.readValue(json, new TypeReference<>() {});
        Object rows = object.get("data");
        if (rows instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object value : list) result.add(mapper.convertValue(value, new TypeReference<>() {}));
            return result;
        }
        return List.of(object);
    }
}
