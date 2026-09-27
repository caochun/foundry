package org.openfoundry.foundation.spi;

/** Raw history remains readable, but an unsupported historical format cannot safely answer an as-of query. */
public final class TemporalHistoryUnavailableException extends IllegalStateException {
    public TemporalHistoryUnavailableException(String type, String id) {
        super("Legacy temporal history requires explicit migration: " + type + "/" + id);
    }

    public String code() {
        return "TEMPORAL_HISTORY_MIGRATION_REQUIRED";
    }
}
