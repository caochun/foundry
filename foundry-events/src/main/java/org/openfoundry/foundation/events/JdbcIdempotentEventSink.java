package org.openfoundry.foundation.events;

import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Durable consumer completion receipts. A lease claim is never treated as completed delivery. */
public final class JdbcIdempotentEventSink implements EventSink {
    private final DataSource dataSource;
    private final EventSink delegate;
    private final String consumer;
    private final Clock clock;
    private final Duration lease;

    public JdbcIdempotentEventSink(DataSource dataSource, EventSink delegate) {
        this(dataSource, "default", delegate);
    }

    public JdbcIdempotentEventSink(DataSource dataSource, String consumer, EventSink delegate) {
        this(dataSource, DatabaseDialect.standard("standard", "VARCHAR", "TIMESTAMP WITH TIME ZONE"),
                consumer, delegate, Clock.systemUTC(), Duration.ofMinutes(1));
    }

    public JdbcIdempotentEventSink(DataSource dataSource, DatabaseDialect dialect, String consumer,
                                   EventSink delegate, Clock clock, Duration lease) {
        this.dataSource = Objects.requireNonNull(dataSource);
        this.delegate = Objects.requireNonNull(delegate);
        if (consumer == null || consumer.isBlank()) throw new IllegalArgumentException("Consumer ID is required");
        this.consumer = consumer;
        this.clock = Objects.requireNonNull(clock);
        DeliveryTimes.deadline(clock.instant(), lease);
        this.lease = lease;
        initialize(dialect);
    }

    private void initialize(DatabaseDialect dialect) {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(true);
            // Old rows cannot distinguish a successful callback from a process exit before the callback.
            statement.execute("CREATE TABLE IF NOT EXISTS of_consumed_events (event_id VARCHAR(255) PRIMARY KEY, consumed_at "
                    + dialect.timestampType() + " NOT NULL)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS of_event_delivery_receipts (
                      tenant_id VARCHAR(255) NOT NULL, receipt_key VARCHAR(64) NOT NULL,
                      event_hash VARCHAR(64) NOT NULL, claim_token VARCHAR(64),
                      lease_until %s, completed_at %s,
                      legacy_reviewed BOOLEAN NOT NULL, attempt_count INTEGER NOT NULL,
                      PRIMARY KEY (tenant_id, receipt_key)
                    )
                    """.formatted(dialect.timestampType(), dialect.timestampType()));
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot initialize event delivery receipts", failure);
        }
    }

    @Override
    public void publish(CloudEvent event) {
        var identity = EventIdentity.of(consumer, event);
        ensure(identity);
        String token = claim(identity, event.id());
        if (token == null) return;
        try {
            delegate.publish(event);
        } catch (RuntimeException | Error failure) {
            try { finish(identity, token, false); }
            catch (RuntimeException releaseFailure) { failure.addSuppressed(releaseFailure); }
            throw failure;
        }
        if (!finish(identity, token, true)) throw new EventDeliveryException(EventDeliveryException.Reason.LEASE_LOST);
    }

    /** Migration only: the caller must have independent evidence that the old delivery completed. */
    public void confirmLegacyCompletion(CloudEvent event) {
        reviewLegacy(event, true);
    }

    /** Migration only: the caller explicitly authorizes possible duplicate execution of an ambiguous old delivery. */
    public void allowLegacyReplay(CloudEvent event) {
        reviewLegacy(event, false);
    }

    private void reviewLegacy(CloudEvent event, boolean completed) {
        var identity = EventIdentity.of(consumer, event);
        ensure(identity);
        transaction(connection -> {
            var current = lock(connection, identity);
            requireContent(current, identity);
            Instant now = DeliveryTimes.instant(clock.instant());
            if (current.until != null && current.until.isAfter(now)) throw new EventDeliveryException(EventDeliveryException.Reason.BUSY);
            if (current.completedAt != null) throw new IllegalStateException("Completed receipts cannot be reclassified");
            if (!legacyExists(connection, event.id())) throw new IllegalArgumentException("No legacy receipt exists for this event");
            try (var statement = connection.prepareStatement("UPDATE of_event_delivery_receipts SET legacy_reviewed = TRUE,"
                    + " completed_at = ?, claim_token = NULL, lease_until = NULL WHERE tenant_id = ? AND receipt_key = ?")) {
                statement.setTimestamp(1, completed ? Timestamp.from(now) : null);
                statement.setString(2, identity.tenant());
                statement.setString(3, identity.key());
                statement.executeUpdate();
            }
            return null;
        });
    }

    private void ensure(EventIdentity identity) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "INSERT INTO of_event_delivery_receipts (tenant_id, receipt_key, event_hash, legacy_reviewed, attempt_count) VALUES (?, ?, ?, FALSE, 0)")) {
            connection.setAutoCommit(true);
            statement.setString(1, identity.tenant());
            statement.setString(2, identity.key());
            statement.setString(3, identity.fingerprint());
            statement.executeUpdate();
        } catch (SQLException failure) {
            // Only a duplicate key is expected. A connection error or another integrity violation must propagate.
            if (!"23505".equals(failure.getSQLState())) throw new IllegalStateException("Cannot create event delivery receipt", failure);
        }
    }

    private String claim(EventIdentity identity, String eventId) {
        return transaction(connection -> {
            var current = lock(connection, identity);
            requireContent(current, identity);
            if (current.completedAt != null) return null;
            Instant now = DeliveryTimes.instant(clock.instant());
            if (current.until != null && current.until.isAfter(now)) throw new EventDeliveryException(EventDeliveryException.Reason.BUSY);
            if (!current.legacyReviewed && legacyExists(connection, eventId)) {
                throw new EventDeliveryException(EventDeliveryException.Reason.LEGACY_REVIEW_REQUIRED);
            }
            String token = UUID.randomUUID().toString();
            try (var statement = connection.prepareStatement("UPDATE of_event_delivery_receipts SET claim_token = ?, lease_until = ?,"
                    + " attempt_count = ? WHERE tenant_id = ? AND receipt_key = ?")) {
                statement.setString(1, token);
                statement.setTimestamp(2, Timestamp.from(DeliveryTimes.deadline(now, lease)));
                statement.setInt(3, Math.addExact(current.attempt, 1));
                statement.setString(4, identity.tenant());
                statement.setString(5, identity.key());
                statement.executeUpdate();
            }
            return token;
        });
    }

    private boolean finish(EventIdentity identity, String token, boolean completed) {
        Instant now = DeliveryTimes.instant(clock.instant());
        String sql = "UPDATE of_event_delivery_receipts SET completed_at = ?, claim_token = NULL, lease_until = NULL"
                + " WHERE tenant_id = ? AND receipt_key = ? AND claim_token = ? AND lease_until > ? AND completed_at IS NULL";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(true);
            statement.setTimestamp(1, completed ? Timestamp.from(now) : null);
            statement.setString(2, identity.tenant());
            statement.setString(3, identity.key());
            statement.setString(4, token);
            statement.setTimestamp(5, Timestamp.from(now));
            return statement.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot finish event delivery receipt", failure);
        }
    }

    private static Receipt lock(Connection connection, EventIdentity identity) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM of_event_delivery_receipts WHERE tenant_id = ? AND receipt_key = ? FOR UPDATE")) {
            statement.setString(1, identity.tenant());
            statement.setString(2, identity.key());
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("Event receipt disappeared");
                var until = row.getTimestamp("lease_until");
                var completed = row.getTimestamp("completed_at");
                return new Receipt(row.getString("event_hash"), until == null ? null : until.toInstant(),
                        completed == null ? null : completed.toInstant(), row.getBoolean("legacy_reviewed"), row.getInt("attempt_count"));
            }
        }
    }

    private static boolean legacyExists(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT event_id FROM of_consumed_events WHERE event_id = ?")) {
            statement.setString(1, id);
            try (var row = statement.executeQuery()) {
                return row.next();
            }
        }
    }

    private static void requireContent(Receipt receipt, EventIdentity identity) {
        if (!receipt.hash.equals(identity.fingerprint())) throw new IllegalArgumentException("Event ID reused with different content");
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
            throw new IllegalStateException("Event receipt transaction failed", failure);
        }
    }

    private record Receipt(String hash, Instant until, Instant completedAt, boolean legacyReviewed, int attempt) {}
    @FunctionalInterface
    private interface Work<T> { T run(Connection connection) throws SQLException; }
}
