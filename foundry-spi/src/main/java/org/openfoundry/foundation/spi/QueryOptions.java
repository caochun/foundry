package org.openfoundry.foundation.spi;

import java.time.Instant;

public record QueryOptions(
        int limit,
        int offset,
        Instant asOfValidTime,
        Instant asOfRecordedTime,
        boolean includeDeleted) {

    public QueryOptions {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
    }

    public static QueryOptions defaults() {
        return new QueryOptions(100, 0, null, null, false);
    }
}
