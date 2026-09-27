package org.openfoundry.foundation.spi.schema;

/** A safe, structured error; never includes a sensitive field value. */
public final class PropertyValidationException extends IllegalArgumentException {
    private final String code;
    private final String field;

    public PropertyValidationException(String code, String field) {
        super(code + ": " + field);
        this.code = code;
        this.field = field;
    }

    public String code() { return code; }
    public String field() { return field; }
}
