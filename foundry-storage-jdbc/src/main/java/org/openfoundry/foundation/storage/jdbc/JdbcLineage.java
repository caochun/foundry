package org.openfoundry.foundation.storage.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Uses the caller's connection and tenant write guard; never commits or borrows another connection. */
final class JdbcLineage {
    private final DatabaseDialect dialect;
    private final ObjectMapper mapper;

    JdbcLineage(DatabaseDialect dialect, ObjectMapper mapper) {
        this.dialect = dialect;
        this.mapper = mapper;
    }

    List<FieldProvenance> query(Connection connection, RequestContext context, EntityKey key, LineageQuery query) throws SQLException {
        String sql = "SELECT * FROM of_field_lineage WHERE tenant_id = ? AND entity_type = ? AND entity_id = ?"
                + (query.field() == null ? "" : " AND field_name = ?")
                + (query.beforeSequence() == null ? "" : " AND lineage_seq < ?")
                + " ORDER BY lineage_seq DESC" + dialect.paginationClause();
        try (var statement = connection.prepareStatement(sql)) {
            identity(statement, context, key, 1);
            int index = 4;
            if (query.field() != null) statement.setString(index++, query.field());
            if (query.beforeSequence() != null) statement.setLong(index++, query.beforeSequence());
            statement.setInt(index++, query.limit());
            statement.setInt(index, 0);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<FieldProvenance>();
                while (rows.next()) result.add(read(rows));
                return List.copyOf(result);
            }
        }
    }

    Map<String, FieldProvenance> latest(Connection connection, RequestContext context, EntityKey key) throws SQLException {
        String sql = "SELECT * FROM of_field_lineage WHERE tenant_id = ? AND entity_type = ? AND entity_id = ?"
                + " AND lineage_seq IN (SELECT MAX(lineage_seq) FROM of_field_lineage WHERE tenant_id = ? AND entity_type = ? AND entity_id = ? GROUP BY field_name)";
        try (var statement = connection.prepareStatement(sql)) {
            identity(statement, context, key, 1);
            identity(statement, context, key, 4);
            try (var rows = statement.executeQuery()) {
                var result = new LinkedHashMap<String, FieldProvenance>();
                while (rows.next()) {
                    var record = read(rows);
                    result.put(record.field(), record);
                }
                return Collections.unmodifiableMap(result);
            }
        }
    }

    void append(Connection connection, RequestContext context, EntityKey key, long version, String transactionId,
                Map<String, LineageValues.Value> fields, MutationSource source, Instant recordedAt) throws SQLException {
        if (fields.isEmpty()) return;
        long sequence;
        try (var statement = connection.prepareStatement("SELECT COALESCE(MAX(lineage_seq), 0) FROM of_field_lineage WHERE tenant_id = ? AND entity_type = ? AND entity_id = ?")) {
            identity(statement, context, key, 1);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new SQLException("Missing lineage sequence result");
                sequence = rows.getLong(1);
            }
        }
        String encoded;
        try {
            encoded = mapper.writeValueAsString(Map.of("kind", source.kind().name(), "name", source.name(), "operationId", source.operationId(),
                    "producedAt", source.producedAt().toString(), "details", source.details()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new SQLException("Cannot encode mutation source", failure); }
        String sql = "INSERT INTO of_field_lineage (tenant_id, entity_type, entity_id, lineage_seq, field_name, entity_version, value_present, value_hash, recorded_at, transaction_id, actor_id, source_json)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (var statement = connection.prepareStatement(sql)) {
            for (var entry : fields.entrySet()) {
                sequence = Math.incrementExact(sequence);
                new FieldProvenance(context.tenantId(), key, entry.getKey(), sequence, version, entry.getValue().present(), entry.getValue().hash(), recordedAt, transactionId, context.actorId(), source);
                identity(statement, context, key, 1);
                statement.setLong(4, sequence);
                statement.setString(5, entry.getKey());
                statement.setLong(6, version);
                statement.setBoolean(7, entry.getValue().present());
                statement.setString(8, entry.getValue().hash());
                statement.setTimestamp(9, Timestamp.from(recordedAt));
                statement.setString(10, transactionId);
                statement.setString(11, context.actorId());
                statement.setString(12, encoded);
                statement.executeUpdate();
            }
        }
    }

    private FieldProvenance read(ResultSet row) throws SQLException {
        try {
            Map<String, Object> source = mapper.readValue(row.getString("source_json"), new TypeReference<>() {});
            if (!source.keySet().equals(Set.of("kind", "name", "operationId", "producedAt", "details"))) throw new IllegalStateException("Invalid mutation source fields");
            @SuppressWarnings("unchecked") var details = (Map<String, Object>) source.get("details");
            var origin = new MutationSource(MutationSource.Kind.valueOf((String) source.get("kind")), (String) source.get("name"),
                    (String) source.get("operationId"), Instant.parse((String) source.get("producedAt")), details);
            return new FieldProvenance(row.getString("tenant_id"), new EntityKey(row.getString("entity_type"), row.getString("entity_id")),
                    row.getString("field_name"), row.getLong("lineage_seq"), row.getLong("entity_version"), row.getBoolean("value_present"),
                    row.getString("value_hash"), row.getTimestamp("recorded_at").toInstant(), row.getString("transaction_id"), row.getString("actor_id"), origin);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException invalid) {
            throw new IllegalStateException("Invalid stored lineage evidence", invalid);
        }
    }

    private static void identity(PreparedStatement statement, RequestContext context, EntityKey key, int index) throws SQLException {
        statement.setString(index, context.tenantId());
        statement.setString(index + 1, key.type());
        statement.setString(index + 2, key.id());
    }
}
