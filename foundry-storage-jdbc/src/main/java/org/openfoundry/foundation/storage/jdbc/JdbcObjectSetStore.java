package org.openfoundry.foundation.storage.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Durable query metadata. Owner checks and partial updates occur under the same row lock. */
public final class JdbcObjectSetStore implements ObjectSetStore {
    private final DataSource data;
    private final DatabaseDialect dialect;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(JsonNumbers.module());
    private volatile boolean initialized;

    public JdbcObjectSetStore(DataSource data, DatabaseDialect dialect) { this(data, dialect, Clock.systemUTC()); }
    public JdbcObjectSetStore(DataSource data, DatabaseDialect dialect, Clock clock) {
        this.data = Objects.requireNonNull(data);
        this.dialect = Objects.requireNonNull(dialect);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public ObjectSetDefinition create(RequestContext context, ObjectSetSpec spec) {
        if (context.actorId() == null) throw new SecurityException("ObjectSet creation requires an authenticated actor");
        var now = clock.instant();
        var created = new ObjectSetDefinition(UUID.randomUUID().toString(), context.tenantId(), context.actorId(), now, now, 1, spec);
        return transaction(connection -> {
            try (var statement = connection.prepareStatement("""
                    INSERT INTO of_object_sets (name, object_type, is_public, spec_json, version, updated_at, created_by, created_at, tenant_id, id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                values(statement, created);
                statement.setString(7, created.createdBy());
                statement.setString(8, created.createdAt().toString());
                statement.setString(9, created.tenantId());
                statement.setString(10, created.id());
                statement.executeUpdate();
            }
            return created;
        });
    }

    @Override
    public ObjectSetDefinition get(RequestContext context, String id) {
        initialize();
        try (var connection = data.getConnection(); var statement = connection.prepareStatement(
                "SELECT * FROM of_object_sets WHERE tenant_id = ? AND id = ? AND (is_public = TRUE OR created_by = ?)")) {
            statement.setString(1, context.tenantId());
            statement.setString(2, id);
            statement.setString(3, context.actorId());
            try (var row = statement.executeQuery()) { return row.next() ? read(row) : null; }
        } catch (SQLException failure) { throw new IllegalStateException("Cannot read ObjectSet", failure); }
    }

    @Override
    public ObjectSetDefinition getByName(RequestContext context, String name) {
        return select(context, null, name).stream().findFirst().orElse(null);
    }

    @Override
    public List<ObjectSetDefinition> list(RequestContext context, String objectType) { return select(context, objectType, null); }

    private List<ObjectSetDefinition> select(RequestContext context, String objectType, String name) {
        initialize();
        String sql = "SELECT * FROM of_object_sets WHERE tenant_id = ? AND (is_public = TRUE OR created_by = ?)"
                + (objectType == null ? "" : " AND object_type = ?") + (name == null ? "" : " AND name = ?");
        try (var connection = data.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, context.tenantId());
            statement.setString(2, context.actorId());
            int index = 3;
            if (objectType != null) statement.setString(index++, objectType);
            if (name != null) statement.setString(index, name);
            var results = new ArrayList<ObjectSetDefinition>();
            try (var rows = statement.executeQuery()) { while (rows.next()) results.add(read(rows)); }
            // ISO fractions have variable width; sort by actual instants, not timestamp text.
            return results.stream().sorted(Comparator.comparing(ObjectSetDefinition::createdAt).thenComparing(ObjectSetDefinition::id)).toList();
        } catch (SQLException failure) { throw new IllegalStateException("Cannot list ObjectSets", failure); }
    }

    @Override
    public ObjectSetDefinition update(RequestContext context, String id, Map<String, Object> patch, Long expectedVersion) {
        return transaction(connection -> {
            var existing = owned(connection, context, id, expectedVersion);
            var updated = existing.updated(existing.spec().patched(patch), clock.instant());
            try (var statement = connection.prepareStatement("""
                    UPDATE of_object_sets SET name = ?, object_type = ?, is_public = ?, spec_json = ?, version = ?, updated_at = ?
                    WHERE tenant_id = ? AND id = ? AND version = ?
                    """)) {
                values(statement, updated);
                statement.setString(7, context.tenantId());
                statement.setString(8, id);
                statement.setLong(9, existing.version());
                if (statement.executeUpdate() != 1) throw new ObjectSetConflictException();
            }
            return updated;
        });
    }

    @Override
    public void delete(RequestContext context, String id, Long expectedVersion) {
        transaction(connection -> {
            var existing = owned(connection, context, id, expectedVersion);
            try (var statement = connection.prepareStatement("DELETE FROM of_object_sets WHERE tenant_id = ? AND id = ? AND version = ?")) {
                statement.setString(1, context.tenantId());
                statement.setString(2, id);
                statement.setLong(3, existing.version());
                if (statement.executeUpdate() != 1) throw new ObjectSetConflictException();
            }
            return null;
        });
    }

    private ObjectSetDefinition owned(Connection connection, RequestContext context, String id, Long version) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM of_object_sets WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
            statement.setString(1, context.tenantId());
            statement.setString(2, id);
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw new ObjectSetNotFoundException();
                var definition = read(row);
                definition.requireOwner(context, version);
                return definition;
            }
        }
    }

    private void values(PreparedStatement statement, ObjectSetDefinition definition) throws SQLException {
        statement.setString(1, definition.spec().name());
        statement.setString(2, definition.spec().objectType());
        statement.setBoolean(3, definition.spec().isPublic());
        try { statement.setString(4, mapper.writeValueAsString(definition.spec().toMap())); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalArgumentException("Invalid ObjectSet metadata", failure); }
        statement.setLong(5, definition.version());
        statement.setString(6, definition.updatedAt().toString());
    }

    private ObjectSetDefinition read(ResultSet row) throws SQLException {
        try {
            if (row.getInt("format_version") != 1) throw new IllegalStateException("Unsupported ObjectSet storage format");
            var spec = ObjectSetSpec.fromMap(mapper.readValue(row.getString("spec_json"), new TypeReference<Map<String, Object>>() {}));
            if (!spec.name().equals(row.getString("name")) || !spec.objectType().equals(row.getString("object_type")) || spec.isPublic() != row.getBoolean("is_public")) {
                throw new IllegalStateException("ObjectSet metadata and lookup columns disagree");
            }
            return new ObjectSetDefinition(row.getString("id"), row.getString("tenant_id"), row.getString("created_by"),
                    Instant.parse(row.getString("created_at")), Instant.parse(row.getString("updated_at")), row.getLong("version"), spec);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Stored ObjectSet is not valid JSON", failure); }
    }

    private <T> T transaction(Work<T> work) {
        initialize();
        try (var connection = data.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                var result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) { throw new IllegalStateException("ObjectSet transaction failed", failure); }
    }

    private synchronized void initialize() {
        if (initialized) return;
        try (var connection = data.getConnection()) {
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS of_object_sets (
                          tenant_id VARCHAR(255) NOT NULL, id VARCHAR(64) NOT NULL,
                          name VARCHAR(255) NOT NULL, object_type VARCHAR(255) NOT NULL,
                          is_public BOOLEAN NOT NULL, created_by VARCHAR(255) NOT NULL,
                          created_at VARCHAR(64) NOT NULL, updated_at VARCHAR(64) NOT NULL,
                          version BIGINT NOT NULL, format_version INTEGER NOT NULL DEFAULT 1, spec_json %s NOT NULL, PRIMARY KEY (tenant_id, id)
                        )
                        """.formatted(dialect.textType()));
                statement.execute("CREATE INDEX IF NOT EXISTS idx_of_object_sets_name ON of_object_sets (tenant_id, name)");
            }
            initialized = true;
        } catch (SQLException failure) { throw new IllegalStateException("Cannot initialize ObjectSet store", failure); }
    }
    @FunctionalInterface private interface Work<T> { T run(Connection connection) throws SQLException; }
}
