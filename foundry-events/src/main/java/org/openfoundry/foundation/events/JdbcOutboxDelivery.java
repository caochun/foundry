package org.openfoundry.foundation.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Leases are separate from immutable event payloads, so existing transactional producers need no migration. */
final class JdbcOutboxDelivery {
    private final DataSource dataSource;
    private final DatabaseDialect dialect;
    private final ObjectMapper mapper = new ObjectMapper();

    JdbcOutboxDelivery(DataSource dataSource, DatabaseDialect dialect) {
        this.dataSource = dataSource;
        this.dialect = dialect;
    }

    void initialize() {
        String ddl = """
                CREATE TABLE IF NOT EXISTS of_outbox_delivery (
                  tenant_id VARCHAR(255) NOT NULL, event_id VARCHAR(255) NOT NULL,
                  claim_token VARCHAR(64), lease_until %s, next_attempt_at %s,
                  attempt_count INTEGER NOT NULL,
                  PRIMARY KEY (tenant_id, event_id)
                )
                """.formatted(dialect.timestampType(), dialect.timestampType());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            statement.execute(ddl);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot initialize outbox delivery state", failure);
        }
    }

    List<OutboxClaim> claim(String tenantId, int limit, Instant now, Duration lease) {
        DeliveryTimes.selection(tenantId, limit);
        Instant time = DeliveryTimes.instant(now);
        Instant until = DeliveryTimes.deadline(now, lease);
        return transaction(connection -> {
            String candidatesSql = "SELECT e.id FROM of_outbox_events e LEFT JOIN of_outbox_delivery d"
                    + " ON e.tenant_id = d.tenant_id AND e.id = d.event_id"
                    + " WHERE e.tenant_id = ? AND e.published_at IS NULL"
                    + " AND (d.lease_until IS NULL OR d.lease_until <= ?)"
                    + " AND (d.next_attempt_at IS NULL OR d.next_attempt_at <= ?)"
                    + " ORDER BY e.occurred_at, e.id" + dialect.paginationClause();
            var ids = new ArrayList<String>();
            try (var statement = connection.prepareStatement(candidatesSql)) {
                statement.setString(1, tenantId);
                statement.setTimestamp(2, Timestamp.from(time));
                statement.setTimestamp(3, Timestamp.from(time));
                statement.setInt(4, limit);
                statement.setInt(5, 0);
                try (var result = statement.executeQuery()) {
                    while (result.next()) ids.add(result.getString(1));
                }
            }
            var claims = new ArrayList<OutboxClaim>();
            for (String id : ids) {
                var event = lockEvent(connection, tenantId, id);
                if (event == null || event.publishedAt() != null) continue;
                var previous = delivery(connection, tenantId, id);
                if (previous != null && (previous.until != null && previous.until.isAfter(time)
                        || previous.retryAt != null && previous.retryAt.isAfter(time))) continue;
                String token = UUID.randomUUID().toString();
                int attempt = previous == null ? 1 : Math.addExact(previous.attempt, 1);
                if (previous == null) {
                    try (var statement = connection.prepareStatement("INSERT INTO of_outbox_delivery"
                            + " (claim_token, lease_until, attempt_count, tenant_id, event_id) VALUES (?, ?, ?, ?, ?)")) {
                        statement.setString(1, token);
                        statement.setTimestamp(2, Timestamp.from(until));
                        statement.setInt(3, attempt);
                        statement.setString(4, tenantId);
                        statement.setString(5, id);
                        statement.executeUpdate();
                    }
                } else {
                    try (var statement = connection.prepareStatement("UPDATE of_outbox_delivery"
                            + " SET claim_token = ?, lease_until = ?, attempt_count = ?, next_attempt_at = NULL WHERE tenant_id = ? AND event_id = ?")) {
                        statement.setString(1, token);
                        statement.setTimestamp(2, Timestamp.from(until));
                        statement.setInt(3, attempt);
                        statement.setString(4, tenantId);
                        statement.setString(5, id);
                        statement.executeUpdate();
                    }
                }
                claims.add(new OutboxClaim(event, token, attempt, until));
            }
            return List.copyOf(claims);
        });
    }

    boolean renew(OutboxClaim claim, Instant now, Duration lease) {
        Instant until = DeliveryTimes.deadline(now, lease);
        return transaction(connection -> {
            var current = owned(connection, claim, now);
            if (current == null) return false;
            if (until.isAfter(current.until)) update(connection, claim, current.token, until, null);
            return true;
        });
    }

    boolean complete(OutboxClaim claim, Instant now) {
        return transaction(connection -> {
            if (owned(connection, claim, now) == null) return false;
            try (var statement = connection.prepareStatement("UPDATE of_outbox_events SET published_at = ? WHERE tenant_id = ? AND id = ?")) {
                statement.setTimestamp(1, Timestamp.from(DeliveryTimes.instant(now)));
                statement.setString(2, claim.event().tenantId());
                statement.setString(3, claim.event().id());
                statement.executeUpdate();
            }
            update(connection, claim, null, null, null);
            return true;
        });
    }

    boolean fail(OutboxClaim claim, Instant now, Instant retryAt) {
        Instant retry = DeliveryTimes.instant(retryAt);
        if (retry.isBefore(DeliveryTimes.instant(now))) throw new IllegalArgumentException("Retry cannot precede failure");
        return transaction(connection -> {
            if (owned(connection, claim, now) == null) return false;
            update(connection, claim, null, null, retry);
            return true;
        });
    }

    void legacyComplete(String id, Instant at) {
        transaction(connection -> {
            String tenant;
            try (var statement = connection.prepareStatement("SELECT tenant_id FROM of_outbox_events WHERE id = ?")) {
                statement.setString(1, id);
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("Outbox event not found");
                    tenant = rows.getString(1);
                }
            }
            lockEvent(connection, tenant, id);
            if (delivery(connection, tenant, id) != null) throw new IllegalStateException("Use a delivery token to acknowledge this event");
            try (var statement = connection.prepareStatement("UPDATE of_outbox_events SET published_at = ? WHERE tenant_id = ? AND id = ?")) {
                statement.setTimestamp(1, Timestamp.from(DeliveryTimes.instant(at)));
                statement.setString(2, tenant);
                statement.setString(3, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    private Delivery owned(Connection connection, OutboxClaim claim, Instant now) throws SQLException {
        var event = lockEvent(connection, claim.event().tenantId(), claim.event().id());
        if (event == null || event.publishedAt() != null) return null;
        var current = delivery(connection, event.tenantId(), event.id());
        return current != null && claim.token().equals(current.token) && current.until != null
                && current.until.isAfter(DeliveryTimes.instant(now)) ? current : null;
    }

    private void update(Connection connection, OutboxClaim claim, String token, Instant until, Instant retryAt) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE of_outbox_delivery SET claim_token = ?, lease_until = ?, next_attempt_at = ?"
                + " WHERE tenant_id = ? AND event_id = ?")) {
            statement.setString(1, token);
            statement.setTimestamp(2, until == null ? null : Timestamp.from(until));
            statement.setTimestamp(3, retryAt == null ? null : Timestamp.from(retryAt));
            statement.setString(4, claim.event().tenantId());
            statement.setString(5, claim.event().id());
            statement.executeUpdate();
        }
    }

    private Delivery delivery(Connection connection, String tenant, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM of_outbox_delivery WHERE tenant_id = ? AND event_id = ?")) {
            statement.setString(1, tenant);
            statement.setString(2, id);
            try (var row = statement.executeQuery()) {
                return row.next() ? new Delivery(row.getString("claim_token"), nullable(row, "lease_until"),
                        nullable(row, "next_attempt_at"), row.getInt("attempt_count")) : null;
            }
        }
    }

    private OutboxEvent lockEvent(Connection connection, String tenant, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM of_outbox_events WHERE tenant_id = ? AND id = ? FOR UPDATE")) {
            statement.setString(1, tenant);
            statement.setString(2, id);
            try (var row = statement.executeQuery()) {
                if (!row.next()) return null;
                try {
                    Map<String, Object> data = mapper.readValue(row.getString("data_json"), new TypeReference<>() {});
                    return new OutboxEvent(id, tenant, row.getString("type"), row.getString("subject"),
                            row.getTimestamp("occurred_at").toInstant(), row.getString("transaction_id"), data, nullable(row, "published_at"));
                } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
                    throw new IllegalStateException("Invalid stored event payload", invalid);
                }
            }
        }
    }

    private static Instant nullable(ResultSet row, String column) throws SQLException {
        var value = row.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private <T> T transaction(Work<T> work) {
        try (var connection = dataSource.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Outbox delivery transaction failed", failure);
        }
    }

    private record Delivery(String token, Instant until, Instant retryAt, int attempt) {}
    @FunctionalInterface
    private interface Work<T> { T run(Connection connection) throws SQLException; }
}
