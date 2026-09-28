package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.IngestionCheckpoint;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Explicit source lifecycle. Extraction streams must be closed, including after short-circuit operations. */
public interface ManagedConnector extends Connector, AutoCloseable {
    String version();

    Capabilities capabilities();

    void initialize(DatasourceMapping.Connection configuration);

    Health healthCheck();

    SourceSchema discoverSchema();

    Stream<SourceRecord> fullExtract(ExtractOptions options);

    Stream<SourceRecord> incrementalExtract(Cursor since, ExtractOptions options);

    String partition();

    void pause();

    void resume();

    @Override
    void close();

    record Capabilities(boolean discovery, boolean fullExtract, boolean incrementalExtract, boolean writeback) {}

    record Health(boolean healthy, String provider, long latencyMillis, String status) {}

    record SourceSchema(List<Table> tables) {
        public SourceSchema {
            tables = List.copyOf(tables);
        }
    }

    record Table(String schema, String name, List<Column> columns, List<String> primaryKey) {
        public Table {
            columns = List.copyOf(columns);
            primaryKey = List.copyOf(primaryKey);
        }
    }

    record Column(String name, String type, int jdbcType, boolean nullable) {}

    record ExtractOptions(int batchSize, Integer maxRecordsPerSecond, Duration queryTimeout) {
        public ExtractOptions {
            if (batchSize < 1 || batchSize > 10_000) {
                throw new IllegalArgumentException("Extraction batch size must be between 1 and 10000");
            }
            if (maxRecordsPerSecond != null && maxRecordsPerSecond < 1) {
                throw new IllegalArgumentException("Extraction rate must be positive");
            }
            Objects.requireNonNull(queryTimeout);
            if (queryTimeout.isNegative() || queryTimeout.isZero() || queryTimeout.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException("Query timeout must be positive and at most one hour");
            }
        }

        public static ExtractOptions defaults() {
            return new ExtractOptions(1000, null, Duration.ofSeconds(30));
        }
    }

    record Cursor(long sequence, Object token) {
        public Cursor {
            if (sequence < 0 || token == null) {
                throw new IllegalArgumentException("Invalid source cursor");
            }
            token = PropertyValues.immutableValue(token);
        }

        public static Cursor from(IngestionCheckpoint checkpoint) {
            return checkpoint == null ? null : new Cursor(checkpoint.sequence(), checkpoint.token());
        }
    }
}
