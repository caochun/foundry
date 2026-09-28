package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.util.Map;

/** A stable event identity and monotonically increasing position within a connector partition. */
public record SourcePosition(String partition, String eventId, long sequence, Object checkpoint) {
    public SourcePosition {
        if (partition == null || partition.isBlank() || eventId == null || eventId.isBlank() || sequence < 0) {
            throw new IllegalArgumentException("Invalid source position");
        }
        if (!(checkpoint instanceof String || checkpoint instanceof Number || checkpoint instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Checkpoint must be a string, number or object");
        }
        checkpoint = PropertyValues.immutableValue(checkpoint);
    }
}
