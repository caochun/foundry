package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Parsed deployment declaration. Parsing does not connect, interpolate secrets or start a scheduler. */
public record DatasourceMapping(String datasource, String connector, Connection connection, MappingConfig mapping, Sync sync) {
    public enum Mode { OVERLAY, CDC, POLLING, BATCH }
    public DatasourceMapping {
        if (datasource == null || datasource.isBlank() || connector == null || connector.isBlank()) throw new IllegalArgumentException("Datasource and connector names are required");
        Objects.requireNonNull(connection);
        Objects.requireNonNull(mapping);
        Objects.requireNonNull(sync);
    }
    public record Connection(String url, String table, Map<String, Object> properties) {
        public Connection {
            if (url == null || url.isBlank() || table == null || table.isBlank()) throw new IllegalArgumentException("Connection url and table are required");
            properties = PropertyValues.immutableMap(properties);
        }
    }
    public record Sync(Mode mode, String interval, ConflictResolver.Strategy conflictResolution, Integer maxRecordsPerSecond,
                       String cacheStrategy, String cacheTTL, Boolean writeback) {
        public Sync {
            Objects.requireNonNull(mode);
            if (interval != null && (Duration.parse(interval).isNegative() || Duration.parse(interval).isZero())) throw new IllegalArgumentException("Polling interval must be positive");
            if (maxRecordsPerSecond != null && maxRecordsPerSecond < 1) throw new IllegalArgumentException("Rate limit must be positive");
            if (cacheStrategy != null && cacheStrategy.isBlank()) throw new IllegalArgumentException("Cache strategy is empty");
            if (cacheTTL != null && Duration.parse(cacheTTL).isNegative()) throw new IllegalArgumentException("Cache TTL must not be negative");
        }
    }
}
