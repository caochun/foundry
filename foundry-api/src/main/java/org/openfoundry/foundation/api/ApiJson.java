package org.openfoundry.foundation.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.io.IOException;

/** Preserve scalar numeric values before the application can validate or fingerprint them. */
final class ApiJson {
    private ApiJson() {}

    static ObjectMapper mapper() {
        var module = new SimpleModule();
        module.addDeserializer(Number.class, new JsonDeserializer<Number>() {
            @Override
            public Number deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                return parser.currentToken() == JsonToken.VALUE_NUMBER_INT
                        ? parser.getNumberValue() : PropertyValues.jsonDecimal(parser.getDecimalValue());
            }
        });
        return new ObjectMapper().registerModule(module);
    }
}
