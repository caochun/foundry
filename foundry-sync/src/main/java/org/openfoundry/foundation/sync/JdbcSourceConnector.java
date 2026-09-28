package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.LineageValues;
import org.openfoundry.foundation.spi.Provenance;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/** Bounded keyset reads. Timestamp polling requires a source-maintained, non-null monotonic watermark. */
public final class JdbcSourceConnector implements ManagedConnector {
    public static final String VERSION = "0.1.0";
    private enum State { NEW, READY, CLOSED }

    private final String name;
    private final String sourceSystem;
    private final DataSource dataSource;
    private final Object gate = new Object();
    private final Set<Extraction> extractions = ConcurrentHashMap.newKeySet();
    private volatile State state = State.NEW;
    private boolean paused;
    private Table table;
    private Column key;
    private Column watermark;
    private String quotedTable;
    private String quotedKey;
    private String quotedWatermark;
    private String signature;

    public JdbcSourceConnector(String name, String sourceSystem, DataSource dataSource) {
        if (name == null || name.isBlank() || sourceSystem == null || sourceSystem.isBlank()) {
            throw new IllegalArgumentException("Connector and source names are required");
        }
        this.name = name;
        this.sourceSystem = sourceSystem;
        this.dataSource = Objects.requireNonNull(dataSource);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, true, state == State.READY && watermark != null, false);
    }

    @Override
    public void initialize(DatasourceMapping.Connection configuration) {
        Objects.requireNonNull(configuration);
        synchronized (gate) {
            if (state != State.NEW) {
                throw new IllegalStateException("Connector has already been initialized or closed");
            }
            // Initialization is serialized; no extraction can begin before its metadata is complete.
            try (Connection connection = dataSource.getConnection()) {
                DatabaseMetaData metadata = connection.getMetaData();
                String[] parts = configuration.table().split("\\.", -1);
                if (parts.length > 2) {
                    throw new IllegalArgumentException("Expected table or schema.table");
                }
                String tableName = identifier(parts[parts.length - 1], metadata);
                String schema = parts.length == 2 ? identifier(parts[0], metadata) : connection.getSchema();
                table = inspect(metadata, connection.getCatalog(), schema, tableName);
                if (table.primaryKey().size() != 1) {
                    throw new IllegalArgumentException("Keyset extraction requires a single-column primary key");
                }
                key = column(table.primaryKey().getFirst());
                if (!keyType(key.jdbcType())) {
                    throw new IllegalArgumentException("Keyset identity must be a text or exact numeric primary key");
                }
                String requestedKey = property(configuration, "keyColumn", key.name());
                if (!identifier(requestedKey, metadata).equals(key.name())) {
                    throw new IllegalArgumentException("Configured keyColumn must be the table primary key");
                }
                String watermarkName = identifier(property(configuration, "watermarkColumn", "updated_at"), metadata);
                watermark = table.columns().stream().filter(column -> column.name().equals(watermarkName)).findFirst().orElse(null);
                if (watermark != null && (watermark.nullable() || watermark.jdbcType() != Types.TIMESTAMP && watermark.jdbcType() != Types.TIMESTAMP_WITH_TIMEZONE)) {
                    throw new IllegalArgumentException("Polling watermark must be a non-null timestamp column");
                }
                String quote = metadata.getIdentifierQuoteString().strip();
                if (quote.isEmpty()) {
                    throw new IllegalArgumentException("JDBC driver must support identifier quoting");
                }
                quotedTable = (schema == null || schema.isEmpty() ? "" : quoted(schema, quote) + ".") + quoted(tableName, quote);
                quotedKey = quoted(key.name(), quote);
                quotedWatermark = watermark == null ? null : quoted(watermark.name(), quote);
                signature = LineageValues.hash(true, List.of("jdbc-keyset-v1", name, sourceSystem, configuration.url(),
                        configuration.table(), configuration.properties(), Objects.requireNonNull(metadata.getURL(), "JDBC endpoint identity is required"),
                        Objects.toString(metadata.getUserName(), ""), Objects.toString(schema, ""), tableName, table.columns().stream()
                                .map(column -> List.of(column.name(), column.jdbcType(), column.nullable())).toList(), table.primaryKey()));
            } catch (SQLException failure) {
                throw new IllegalStateException("JDBC source initialization failed", failure);
            }
            state = State.READY;
        }
    }

    @Override
    public Health healthCheck() {
        long start = System.nanoTime();
        if (state != State.READY) {
            return new Health(false, "jdbc", 0, state.name());
        }
        try (Connection connection = dataSource.getConnection()) {
            boolean valid = connection.isValid(5) && state == State.READY;
            return new Health(valid, "jdbc", (System.nanoTime() - start) / 1_000_000, valid ? "READY" : "UNAVAILABLE");
        } catch (SQLException failure) {
            return new Health(false, "jdbc", (System.nanoTime() - start) / 1_000_000, "UNAVAILABLE");
        }
    }

    @Override
    public SourceSchema discoverSchema() {
        requireReady();
        try (Connection connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            var tables = new ArrayList<Table>();
            try (ResultSet result = metadata.getTables(connection.getCatalog(), table.schema(), "%", new String[]{"TABLE"})) {
                while (result.next()) {
                    tables.add(inspect(metadata, result.getString("TABLE_CAT"), result.getString("TABLE_SCHEM"), result.getString("TABLE_NAME")));
                }
            }
            requireReady();
            tables.sort(Comparator.comparing(Table::name));
            return new SourceSchema(tables);
        } catch (SQLException failure) {
            throw new IllegalStateException("JDBC schema discovery failed", failure);
        }
    }

    @Override
    public String partition() {
        return "jdbc-poll-v1";
    }

    @Override
    public Stream<SourceRecord> read(SourceQuery query) {
        throw new UnsupportedOperationException("Use managed fullExtract/incrementalExtract, or JdbcConnector for trusted SQL queries");
    }

    @Override
    public Stream<SourceRecord> fullExtract(ExtractOptions options) {
        return extract(false, null, options);
    }

    @Override
    public Stream<SourceRecord> incrementalExtract(Cursor since, ExtractOptions options) {
        requireReady();
        if (watermark == null) {
            throw new IllegalStateException("Incremental extraction requires a timestamp watermark column");
        }
        return extract(true, since, options);
    }

    private Stream<SourceRecord> extract(boolean incremental, Cursor since, ExtractOptions options) {
        synchronized (gate) {
            requireReady();
            var extraction = new Extraction(incremental, since, Objects.requireNonNull(options));
            extractions.add(extraction);
            return StreamSupport.stream(extraction, false).onClose(extraction::close);
        }
    }

    @Override
    public void pause() {
        synchronized (gate) {
            requireReady();
            paused = true;
        }
    }

    @Override
    public void resume() {
        synchronized (gate) {
            requireReady();
            paused = false;
            gate.notifyAll();
        }
    }

    @Override
    public void close() {
        synchronized (gate) {
            state = State.CLOSED;
            paused = false;
            gate.notifyAll();
        }
        extractions.forEach(Extraction::close);
    }

    private void requireReady() {
        if (state != State.READY) {
            throw new IllegalStateException("JDBC source is not ready: " + state);
        }
    }

    private final class Extraction extends Spliterators.AbstractSpliterator<SourceRecord> implements AutoCloseable {
        private final boolean incremental;
        private final ExtractOptions options;
        private final Instant observedAt = Instant.now();
        private List<Map<String, Object>> page = List.of();
        private int index;
        private boolean lastPage;
        private volatile boolean closed;
        private volatile PreparedStatement activeStatement;
        private Object lastKey;
        private Instant lastWatermark;
        private long sequence;
        private long nextEmission;

        private Extraction(boolean incremental, Cursor since, ExtractOptions options) {
            super(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL);
            this.incremental = incremental;
            this.options = options;
            if (since != null) {
                if (!(since.token() instanceof Map<?, ?> token) || !token.keySet().equals(Set.of("format", "signature", "key", "watermark", "sequence"))
                        || !"jdbc-poll-v1".equals(token.get("format")) || !signature.equals(token.get("signature"))) {
                    throw new IllegalArgumentException("Cursor belongs to another JDBC extraction configuration");
                }
                if (!(token.get("sequence") instanceof Number number) || new BigDecimal(number.toString()).compareTo(BigDecimal.valueOf(since.sequence())) != 0) {
                    throw new IllegalArgumentException("Cursor sequence disagrees with the durable checkpoint");
                }
                lastKey = validateKey(token.get("key"));
                if (!(token.get("watermark") instanceof String timestamp)) {
                    throw new IllegalArgumentException("Cursor watermark is invalid");
                }
                lastWatermark = Instant.parse(timestamp);
                sequence = since.sequence();
            }
        }

        @Override
        public Spliterator<SourceRecord> trySplit() {
            return null;
        }

        @Override
        public boolean tryAdvance(Consumer<? super SourceRecord> action) {
            Objects.requireNonNull(action);
            if (closed) {
                return false;
            }
            try {
                awaitReady(false);
                if (index == page.size()) {
                    if (lastPage) {
                        close();
                        return false;
                    }
                    page = fetch();
                    index = 0;
                    lastPage = page.size() < options.batchSize();
                    if (page.isEmpty()) {
                        close();
                        return false;
                    }
                }
                awaitReady(true);
                Map<String, Object> values = page.get(index++);
                lastKey = validateKey(values.get(key.name()));
                String id = RecordMapper.canonicalId(lastKey);
                SourcePosition position = null;
                String sourceVersion = null;
                Instant producedAt = observedAt;
                if (incremental) {
                    lastWatermark = Instant.parse((String) values.get(watermark.name()));
                    sequence = Math.incrementExact(sequence);
                    var token = Map.<String, Object>of("format", "jdbc-poll-v1", "signature", signature,
                            "key", lastKey, "watermark", lastWatermark.toString(), "sequence", sequence);
                    sourceVersion = LineageValues.hash(true, List.of(signature, lastKey, lastWatermark.toString()));
                    position = new SourcePosition(partition(), sourceVersion, sequence, token);
                    producedAt = lastWatermark;
                }
                var provenance = new Provenance(sourceSystem, id, sourceVersion, "jdbc-keyset", producedAt, name, null);
                action.accept(new SourceRecord(sourceSystem, id, "UPSERT", observedAt, values, provenance, position));
                return true;
            } catch (RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }

        private void awaitReady(boolean emission) {
            synchronized (gate) {
                while (true) {
                    if (closed) {
                        throw new IllegalStateException("Extraction was closed");
                    }
                    requireReady();
                    long delay = emission && options.maxRecordsPerSecond() != null ? nextEmission - System.nanoTime() : 0;
                    if (!paused && delay <= 0) {
                        if (emission && options.maxRecordsPerSecond() != null) {
                            nextEmission = System.nanoTime() + Math.max(1, 1_000_000_000L / options.maxRecordsPerSecond());
                        }
                        return;
                    }
                    try {
                        if (paused) {
                            gate.wait();
                        } else {
                            gate.wait(delay / 1_000_000, (int) (delay % 1_000_000));
                        }
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Extraction interrupted", failure);
                    }
                }
            }
        }

        private List<Map<String, Object>> fetch() {
            String where = "";
            if (lastKey != null) {
                where = incremental ? " WHERE (" + quotedWatermark + " > ? OR (" + quotedWatermark + " = ? AND " + quotedKey + " > ?))"
                        : " WHERE " + quotedKey + " > ?";
            }
            String order = incremental ? quotedWatermark + ", " + quotedKey : quotedKey;
            String sql = "SELECT * FROM " + quotedTable + where + " ORDER BY " + order;
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                activeStatement = statement;
                if (closed || state != State.READY) {
                    throw new IllegalStateException("Extraction was closed");
                }
                statement.setMaxRows(options.batchSize());
                statement.setFetchSize(options.batchSize());
                statement.setQueryTimeout((int) Math.max(1, (options.queryTimeout().toMillis() + 999) / 1000));
                if (lastKey != null) {
                    if (incremental) {
                        timestamp(statement, 1, lastWatermark);
                        timestamp(statement, 2, lastWatermark);
                        statement.setObject(3, lastKey, key.jdbcType());
                    } else {
                        statement.setObject(1, lastKey, key.jdbcType());
                    }
                }
                var result = new ArrayList<Map<String, Object>>();
                try (ResultSet rows = statement.executeQuery()) {
                    while (result.size() < options.batchSize() && rows.next()) {
                        if (closed) {
                            throw new IllegalStateException("Extraction was closed");
                        }
                        result.add(row(rows));
                    }
                }
                return result;
            } catch (SQLException failure) {
                throw new IllegalStateException("JDBC source extraction failed", failure);
            } finally {
                activeStatement = null;
            }
        }

        @Override
        public void close() {
            closed = true;
            PreparedStatement statement = activeStatement;
            if (statement != null) {
                try {
                    statement.cancel();
                } catch (SQLException ignored) {
                    // The extracting thread owns and closes JDBC resources in its finally path.
                }
            }
            extractions.remove(this);
            synchronized (gate) {
                gate.notifyAll();
            }
        }
    }

    private Map<String, Object> row(ResultSet result) throws SQLException {
        var values = new LinkedHashMap<String, Object>();
        for (Column column : table.columns()) {
            Object value;
            if (column.jdbcType() == Types.TIMESTAMP) {
                Timestamp timestamp = result.getTimestamp(column.name(), utc());
                value = timestamp == null ? null : timestamp.toInstant().toString();
            } else if (column.jdbcType() == Types.TIMESTAMP_WITH_TIMEZONE) {
                OffsetDateTime timestamp = result.getObject(column.name(), OffsetDateTime.class);
                value = timestamp == null ? null : timestamp.toInstant().toString();
            } else {
                value = result.getObject(column.name());
                if (value instanceof java.sql.Date date) {
                    value = date.toLocalDate().toString();
                } else if (value instanceof java.sql.Time time) {
                    value = time.toLocalTime().toString();
                } else if (value instanceof java.util.UUID) {
                    value = value.toString();
                }
            }
            values.put(column.name(), value);
        }
        return PropertyValues.immutableMap(values);
    }

    private void timestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
        if (watermark.jdbcType() == Types.TIMESTAMP_WITH_TIMEZONE) {
            statement.setObject(index, value.atOffset(java.time.ZoneOffset.UTC));
        } else {
            statement.setTimestamp(index, Timestamp.from(value), utc());
        }
    }

    private Object validateKey(Object value) {
        if (value == null || (numericKey(key.jdbcType()) ? !(value instanceof Number) : !(value instanceof String))) {
            throw new IllegalArgumentException("Invalid JDBC source identity");
        }
        return value;
    }

    private Column column(String name) {
        return table.columns().stream().filter(column -> column.name().equals(name)).findFirst().orElseThrow();
    }

    private static Table inspect(DatabaseMetaData metadata, String catalog, String schema, String table) throws SQLException {
        var columns = new ArrayList<Column>();
        try (ResultSet result = metadata.getColumns(catalog, pattern(schema, metadata), pattern(table, metadata), null)) {
            while (result.next()) {
                if (table.equals(result.getString("TABLE_NAME")) && Objects.equals(schema, result.getString("TABLE_SCHEM"))) {
                    columns.add(new Column(result.getString("COLUMN_NAME"), result.getString("TYPE_NAME"), result.getInt("DATA_TYPE"),
                            result.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls));
                }
            }
        }
        if (columns.isEmpty()) {
            throw new IllegalArgumentException("Source table was not found");
        }
        var primary = new java.util.TreeMap<Integer, String>();
        try (ResultSet result = metadata.getPrimaryKeys(catalog, schema, table)) {
            while (result.next()) {
                primary.put(result.getInt("KEY_SEQ"), result.getString("COLUMN_NAME"));
            }
        }
        return new Table(schema, table, columns, List.copyOf(primary.values()));
    }

    private static String property(DatasourceMapping.Connection configuration, String name, String fallback) {
        Object value = configuration.properties().getOrDefault(name, fallback);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Invalid JDBC " + name);
        }
        return text;
    }

    private static String identifier(String value, DatabaseMetaData metadata) throws SQLException {
        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid JDBC identifier");
        }
        if (metadata.storesUpperCaseIdentifiers()) {
            return value.toUpperCase(Locale.ROOT);
        }
        return metadata.storesLowerCaseIdentifiers() ? value.toLowerCase(Locale.ROOT) : value;
    }

    private static String quoted(String name, String quote) {
        return quote + name.replace(quote, quote + quote) + quote;
    }

    private static String pattern(String value, DatabaseMetaData metadata) throws SQLException {
        if (value == null) {
            return null;
        }
        String escape = metadata.getSearchStringEscape();
        return value.replace(escape, escape + escape).replace("_", escape + "_").replace("%", escape + "%");
    }

    private static boolean numericKey(int type) {
        return Set.of(Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.NUMERIC, Types.DECIMAL).contains(type);
    }

    private static boolean keyType(int type) {
        return numericKey(type) || Set.of(Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGVARCHAR, Types.LONGNVARCHAR).contains(type);
    }

    private static Calendar utc() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }
}
