package org.openfoundry.foundation.spi;

import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.util.Map;

/** One monotonic partition position plus its connector-defined opaque cursor. */
public record IngestionCheckpoint(String key, long version, long sequence, Object token, String sourceSystem, String configuration) {
    public IngestionCheckpoint {
        IngestionReceipt.requireHash(key);
        IngestionReceipt.requireHash(configuration);
        if (version < 1 || sequence < 0) throw new IllegalArgumentException("Invalid ingestion checkpoint position");
        if (!(token instanceof String || token instanceof Number || token instanceof Map<?, ?>)) throw new IllegalArgumentException("Checkpoint must be a string, number or object");
        token = PropertyValues.immutableValue(token);
        if (sourceSystem == null || sourceSystem.isBlank() || sourceSystem.length() > 255) throw new IllegalArgumentException("Invalid checkpoint source system");
    }

    public void requireSuccessor(IngestionCheckpoint previous, long expectedVersion) {
        long actual = previous == null ? 0 : previous.version();
        if (expectedVersion != actual || version != Math.incrementExact(actual)) throw new TransactionConflictException("Ingestion checkpoint version changed");
        if (previous != null && (!key.equals(previous.key()) || sequence <= previous.sequence()
                || !configuration.equals(previous.configuration()) || !sourceSystem.equals(previous.sourceSystem()))) {
            throw new IllegalArgumentException("Checkpoint must advance the same configured source partition");
        }
    }
}
