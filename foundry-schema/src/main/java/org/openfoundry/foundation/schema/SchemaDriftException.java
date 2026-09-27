package org.openfoundry.foundation.schema;

/** Configured ontology and the authoritative registry head do not agree. */
public final class SchemaDriftException extends IllegalStateException {
    public SchemaDriftException() { super("Configured schema does not match the current registered schema"); }
}
