package org.openfoundry.foundation.storage.jdbc;

/** Numeric JSON metadata must survive reloading without silent precision loss. */
final class JsonNumbers {
    private JsonNumbers() {}

    static com.fasterxml.jackson.databind.module.SimpleModule module() {
        var module = new com.fasterxml.jackson.databind.module.SimpleModule();
        module.addDeserializer(Number.class, new com.fasterxml.jackson.databind.JsonDeserializer<Number>() {
            @Override
            public Number deserialize(com.fasterxml.jackson.core.JsonParser parser, com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
                if (parser.currentToken() == com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT) return parser.getNumberValue();
                return org.openfoundry.foundation.spi.schema.PropertyValues.jsonDecimal(parser.getDecimalValue());
            }
        });
        return module;
    }

}
