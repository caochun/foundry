package org.openfoundry.foundation.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Append-only audit and transactional outbox tables for a JDBC database. */
public final class JdbcEventStore implements AuditStore, OutboxStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private final DataSource dataSource;
    private final DatabaseDialect dialect;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JdbcEventStore(DataSource dataSource, DatabaseDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    public void initialize() {
        String text = dialect.textType();
        String timestamp = dialect.timestampType();
        String ddl = """
                CREATE TABLE IF NOT EXISTS of_audit_records (
                  id VARCHAR(255) PRIMARY KEY, tenant_id VARCHAR(255) NOT NULL,
                  timestamp_value %s NOT NULL, actor_id VARCHAR(255), operation_type VARCHAR(64) NOT NULL,
                  object_type VARCHAR(255), object_id VARCHAR(512), action_type VARCHAR(255),
                  transaction_id VARCHAR(255), result VARCHAR(32) NOT NULL, detail_json %s NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_of_audit_object ON of_audit_records (tenant_id, object_type, object_id, timestamp_value);
                CREATE TABLE IF NOT EXISTS of_outbox_events (
                  id VARCHAR(255) PRIMARY KEY, tenant_id VARCHAR(255) NOT NULL, type VARCHAR(255) NOT NULL,
                  subject VARCHAR(512), occurred_at %s NOT NULL, transaction_id VARCHAR(255),
                  data_json %s NOT NULL, published_at %s NULL
                );
                CREATE INDEX IF NOT EXISTS idx_of_outbox_pending ON of_outbox_events (tenant_id, published_at, occurred_at);
                """.formatted(timestamp, text, timestamp, text, timestamp);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String statementSql : ddl.split(";\\s*")) if (!statementSql.isBlank()) statement.execute(statementSql);
        } catch (SQLException exception) { throw failure("initialize event tables", exception); }
    }

    @Override
    public void append(AuditRecord record) {
        String sql = "INSERT INTO of_audit_records (id, tenant_id, timestamp_value, actor_id, operation_type, object_type, object_id, action_type, transaction_id, result, detail_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, record.id()); statement.setString(2, record.tenantId()); statement.setTimestamp(3, timestamp(record.timestamp()));
            statement.setString(4, record.actorId()); statement.setString(5, record.operationType()); statement.setString(6, record.objectType()); statement.setString(7, record.objectId());
            statement.setString(8, record.actionType()); statement.setString(9, record.transactionId()); statement.setString(10, record.result()); statement.setString(11, json(record.detail()));
            statement.executeUpdate();
        } catch (SQLException exception) { throw failure("append audit", exception); }
    }

    @Override
    public List<AuditRecord> query(String tenantId, String objectType, String objectId, Instant from, Instant to, int limit) {
        String sql = "SELECT * FROM of_audit_records WHERE tenant_id = ? AND (? IS NULL OR object_type = ?) AND (? IS NULL OR object_id = ?)"
                + " AND (? IS NULL OR timestamp_value >= ?) AND (? IS NULL OR timestamp_value <= ?) ORDER BY timestamp_value DESC LIMIT ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId); statement.setString(2, objectType); statement.setString(3, objectType); statement.setString(4, objectId); statement.setString(5, objectId);
            setNullable(statement, 6, from); setNullable(statement, 7, from); setNullable(statement, 8, to); setNullable(statement, 9, to); statement.setInt(10, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<AuditRecord> records = new ArrayList<>(); while (result.next()) records.add(readAudit(result)); return List.copyOf(records);
            }
        } catch (SQLException exception) { throw failure("query audit", exception); }
    }

    @Override
    public void append(OutboxEvent event) {
        String sql = "INSERT INTO of_outbox_events (id, tenant_id, type, subject, occurred_at, transaction_id, data_json, published_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, event.id()); statement.setString(2, event.tenantId()); statement.setString(3, event.type()); statement.setString(4, event.subject());
            statement.setTimestamp(5, timestamp(event.occurredAt())); statement.setString(6, event.transactionId()); statement.setString(7, json(event.data())); setNullable(statement, 8, event.publishedAt()); statement.executeUpdate();
        } catch (SQLException exception) { throw failure("append outbox", exception); }
    }

    @Override
    public List<OutboxEvent> pending(String tenantId, int limit) {
        String sql = "SELECT * FROM of_outbox_events WHERE tenant_id = ? AND published_at IS NULL ORDER BY occurred_at LIMIT ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId); statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<OutboxEvent> events = new ArrayList<>(); while (result.next()) events.add(readOutbox(result)); return List.copyOf(events);
            }
        } catch (SQLException exception) { throw failure("read outbox", exception); }
    }

    @Override
    public void markPublished(String eventId, Instant publishedAt) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("UPDATE of_outbox_events SET published_at = ? WHERE id = ?")) {
            statement.setTimestamp(1, timestamp(publishedAt)); statement.setString(2, eventId);
            if (statement.executeUpdate() != 1) throw new IllegalArgumentException("outbox event not found: " + eventId);
        } catch (SQLException exception) { throw failure("mark outbox published", exception); }
    }

    private AuditRecord readAudit(ResultSet result) throws SQLException {
        return new AuditRecord(result.getString("id"), instant(result, "timestamp_value"), result.getString("tenant_id"), result.getString("actor_id"), result.getString("operation_type"), result.getString("object_type"), result.getString("object_id"), result.getString("action_type"), result.getString("transaction_id"), result.getString("result"), map(result.getString("detail_json")));
    }

    private OutboxEvent readOutbox(ResultSet result) throws SQLException {
        return new OutboxEvent(result.getString("id"), result.getString("tenant_id"), result.getString("type"), result.getString("subject"), instant(result, "occurred_at"), result.getString("transaction_id"), map(result.getString("data_json")), nullableInstant(result, "published_at"));
    }

    private String json(Map<String, Object> data) {
        try { return objectMapper.writeValueAsString(data); } catch (JsonProcessingException exception) { throw new IllegalArgumentException("event detail cannot be serialized", exception); }
    }

    private Map<String, Object> map(String json) {
        try { return objectMapper.readValue(json, MAP_TYPE); } catch (JsonProcessingException exception) { throw new IllegalStateException("stored event detail is invalid JSON", exception); }
    }

    private static Timestamp timestamp(Instant instant) { return Timestamp.from(instant); }
    private static Instant instant(ResultSet result, String column) throws SQLException { return result.getTimestamp(column).toInstant(); }
    private static Instant nullableInstant(ResultSet result, String column) throws SQLException { Timestamp value = result.getTimestamp(column); return value == null ? null : value.toInstant(); }
    private static void setNullable(PreparedStatement statement, int index, Instant value) throws SQLException { if (value == null) statement.setTimestamp(index, null); else statement.setTimestamp(index, timestamp(value)); }
    private static RuntimeException failure(String operation, SQLException exception) { return new IllegalStateException("event store failed: " + operation, exception); }
}
