package org.openfoundry.foundation.schema;

/** A registry update was prepared against a version which is no longer current. */
public final class SchemaVersionConflictException extends IllegalStateException {
    public SchemaVersionConflictException(int expected, int actual) {
        super("Schema version conflict: expected " + expected + ", current " + actual);
    }
}
