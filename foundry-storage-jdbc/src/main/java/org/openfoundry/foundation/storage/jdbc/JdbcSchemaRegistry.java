package org.openfoundry.foundation.storage.jdbc;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.schema.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Portable persistent schema registry. Registry scopes are deployment configuration, not caller tenant IDs. */
public final class JdbcSchemaRegistry implements SchemaRegistry {
    private final DataSource dataSource;
    private final DatabaseDialect dialect;
    private final String registryKey;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(JsonNumbers.module())
            .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
            .setVisibility(PropertyAccessor.GETTER, JsonAutoDetect.Visibility.NONE)
            .setVisibility(PropertyAccessor.IS_GETTER, JsonAutoDetect.Visibility.NONE);
    private final SchemaCompiler compiler = new SchemaCompiler();
    private final SchemaDiffer differ = new SchemaDiffer();
    private volatile boolean initialized;

    public JdbcSchemaRegistry(DataSource dataSource, DatabaseDialect dialect) {
        this(dataSource, dialect, "default", Clock.systemUTC());
    }

    public JdbcSchemaRegistry(DataSource dataSource, DatabaseDialect dialect, String registryKey, Clock clock) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.dialect = Objects.requireNonNull(dialect);
        if (registryKey == null || registryKey.isBlank() || registryKey.length() > 255) throw new IllegalArgumentException("Invalid schema registry key");
        this.registryKey = registryKey;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public OntologySchema current() {
        var history = history();
        if (history.isEmpty()) throw new IllegalStateException("no schema has been applied");
        return history.getLast().schema();
    }

    @Override
    public OntologySchema atVersion(int version) {
        return history().stream().filter(item -> item.version() == version).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("schema version not found: " + version)).schema();
    }

    @Override
    public List<SchemaVersion> history() {
        return locked((connection, head) -> readHistory(connection, head));
    }

    @Override
    public SchemaVersion apply(OntologySchema schema, MigrationPlan migrationPlan, Integer expectedVersion, boolean onlyIfChanged) {
        compiler.compile(schema);
        if (expectedVersion != null && expectedVersion < 0) throw new IllegalArgumentException("Expected schema version must not be negative");
        String fingerprint = SchemaFingerprint.of(schema);
        return locked((connection, head) -> append(connection, head, schema, migrationPlan, expectedVersion, onlyIfChanged, fingerprint));
    }

    SchemaVersion applyInTransaction(Connection connection, OntologySchema schema, MigrationPlan plan) throws SQLException {
        return append(connection, lockHead(connection), schema, plan, null, true, SchemaFingerprint.of(schema));
    }

    List<SchemaVersion> historyInTransaction(Connection connection) throws SQLException {
        return readHistory(connection, lockHead(connection));
    }

    private SchemaVersion append(Connection connection, Head head, OntologySchema schema, MigrationPlan migrationPlan,
                                 Integer expectedVersion, boolean onlyIfChanged, String fingerprint) throws SQLException {
        var versions = readHistory(connection, head);
        if (expectedVersion != null && expectedVersion != head.version()) throw new SchemaVersionConflictException(expectedVersion, head.version());
        if (onlyIfChanged && fingerprint.equals(head.fingerprint())) return versions.getLast();
        var diff = versions.isEmpty() ? new SchemaDiff(List.of()) : differ.diff(versions.getLast().schema(), schema);
        if (diff.classification() == MigrationClass.BREAKING && (migrationPlan == null || !migrationPlan.approved())) {
            throw new SchemaValidationException(List.of("breaking schema change requires an approved migration plan"));
        }
        int version = Math.addExact(head.version(), 1);
        String timestamp = clock.instant().toString();
        String snapshot = json(schema);
        String diffJson = json(diff);
        String migrationJson = migrationPlan == null ? null : json(migrationPlan);
        String classification = diff.classification().name();
        String checksum = checksum(version, snapshot, fingerprint, timestamp, diffJson, classification, migrationJson);
        try (var insert = connection.prepareStatement("""
                INSERT INTO of_schema_versions
                (registry_key, version, format_version, snapshot_json, snapshot_digest, applied_at, diff_json, classification, migration_json, checksum)
                VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            insert.setString(1, registryKey);
            insert.setInt(2, version);
            insert.setString(3, snapshot);
            insert.setString(4, fingerprint);
            insert.setString(5, timestamp);
            insert.setString(6, diffJson);
            insert.setString(7, classification);
            insert.setString(8, migrationJson);
            insert.setString(9, checksum);
            insert.executeUpdate();
        }
        try (var update = connection.prepareStatement("UPDATE of_schema_heads SET current_version = ?, fingerprint = ? WHERE registry_key = ? AND current_version = ?")) {
            update.setInt(1, version);
            update.setString(2, fingerprint);
            update.setString(3, registryKey);
            update.setInt(4, head.version());
            if (update.executeUpdate() != 1) throw new IllegalStateException("Schema registry head changed under lock");
        }
        return new SchemaVersion(version, schema, Instant.parse(timestamp), diff, diff.classification(), migrationPlan);
    }

    private List<SchemaVersion> readHistory(Connection connection, Head head) throws SQLException {
        var versions = new ArrayList<SchemaVersion>();
        String latest = null;
        try (var statement = connection.prepareStatement("SELECT * FROM of_schema_versions WHERE registry_key = ? ORDER BY version")) {
            statement.setString(1, registryKey);
            try (var row = statement.executeQuery()) {
                while (row.next()) {
                    int version = row.getInt("version");
                    String snapshot = row.getString("snapshot_json");
                    String fingerprint = row.getString("snapshot_digest");
                    String timestamp = row.getString("applied_at");
                    String diffJson = row.getString("diff_json");
                    String classification = row.getString("classification");
                    String migrationJson = row.getString("migration_json");
                    if (row.getInt("format_version") != 1 || version != versions.size() + 1
                            || !checksum(version, snapshot, fingerprint, timestamp, diffJson, classification, migrationJson).equals(row.getString("checksum"))) {
                        throw new IllegalStateException("Schema registry history is corrupt or unsupported");
                    }
                    var schema = read(snapshot, OntologySchema.class);
                    if (!SchemaFingerprint.of(schema).equals(fingerprint)) throw new IllegalStateException("Schema snapshot digest mismatch");
                    var diff = read(diffJson, SchemaDiff.class);
                    var migration = migrationJson == null ? null : read(migrationJson, MigrationPlan.class);
                    // Historical decisions are archived evidence, not reclassified by a future compiler/differ.
                    if (!classification.equals(diff.classification().name())
                            || diff.classification() == MigrationClass.BREAKING && (migration == null || !migration.approved())) {
                        throw new IllegalStateException("Schema registry classification or migration evidence mismatch");
                    }
                    versions.add(new SchemaVersion(version, schema, Instant.parse(timestamp), diff, diff.classification(), migration));
                    latest = fingerprint;
                }
            }
        }
        if (head.version() != versions.size() || !Objects.equals(head.fingerprint(), latest)) {
            throw new IllegalStateException("Schema registry head does not match its history");
        }
        return List.copyOf(versions);
    }

    private <T> T locked(Work<T> work) {
        initialize();
        try (var connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                Head head = lockHead(connection);
                T result = work.run(connection, head);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) { throw new IllegalStateException("Schema registry transaction failed", failure); }
    }

    private Head lockHead(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT current_version, fingerprint FROM of_schema_heads WHERE registry_key = ? FOR UPDATE")) {
            statement.setString(1, registryKey);
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("Schema registry head missing");
                return new Head(row.getInt(1), row.getString(2));
            }
        }
    }

    synchronized void initialize() {
        if (initialized) return;
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS of_schema_heads (
                          registry_key VARCHAR(255) PRIMARY KEY,
                          current_version INTEGER NOT NULL,
                          fingerprint VARCHAR(64)
                        )
                        """);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS of_schema_versions (
                          registry_key VARCHAR(255) NOT NULL, version INTEGER NOT NULL,
                          format_version INTEGER NOT NULL,
                          snapshot_json %s NOT NULL, snapshot_digest VARCHAR(64) NOT NULL,
                          applied_at VARCHAR(64) NOT NULL, diff_json %s NOT NULL,
                          classification VARCHAR(32) NOT NULL, migration_json %s, checksum VARCHAR(64) NOT NULL,
                          PRIMARY KEY (registry_key, version)
                        )
                        """.formatted(dialect.textType(), dialect.textType(), dialect.textType()));
            }
            if (!headExists(connection)) {
                try (var statement = connection.prepareStatement("INSERT INTO of_schema_heads (registry_key, current_version, fingerprint) VALUES (?, 0, NULL)")) {
                    statement.setString(1, registryKey);
                    statement.executeUpdate();
                } catch (SQLException competing) {
                    if (competing.getSQLState() == null || !competing.getSQLState().startsWith("23") || !headExists(connection)) throw competing;
                }
            }
            initialized = true;
        } catch (SQLException failure) { throw new IllegalStateException("Cannot initialize schema registry", failure); }
    }

    private boolean headExists(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT registry_key FROM of_schema_heads WHERE registry_key = ?")) {
            statement.setString(1, registryKey);
            try (var row = statement.executeQuery()) { return row.next(); }
        }
    }

    private String checksum(int version, String snapshot, String fingerprint, String timestamp, String diff, String classification, String migration) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("registry", registryKey);
        payload.put("version", version);
        payload.put("format", 1);
        payload.put("schema", snapshot);
        payload.put("fingerprint", fingerprint);
        payload.put("time", timestamp);
        payload.put("diff", diff);
        payload.put("classification", classification);
        payload.put("migration", migration);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(PropertyValues.canonical(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalArgumentException("Cannot serialize schema registry value", failure); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Invalid schema registry snapshot", failure); }
    }

    private record Head(int version, String fingerprint) {}
    @FunctionalInterface private interface Work<T> { T run(Connection connection, Head head) throws SQLException; }
}
