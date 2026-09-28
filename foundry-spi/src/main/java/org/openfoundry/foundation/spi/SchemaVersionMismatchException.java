package org.openfoundry.foundation.spi;

/** The deployment must bind the active schema before it can read or write again. */
public final class SchemaVersionMismatchException extends IllegalStateException {
    public SchemaVersionMismatchException() { super("Storage schema activation changed; reload the active deployment model"); }
}
