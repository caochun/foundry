package org.openfoundry.foundation.events;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/** Durable process-shared event deduplication backed by a primary-key table. */
public final class JdbcIdempotentEventSink implements EventSink {
    private final DataSource dataSource;
    private final EventSink delegate;

    public JdbcIdempotentEventSink(DataSource dataSource, EventSink delegate) {
        this.dataSource = dataSource;
        this.delegate = delegate;
        initialize();
    }

    private void initialize() {
        try (Connection connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS of_consumed_events (event_id VARCHAR(255) PRIMARY KEY, consumed_at TIMESTAMP NOT NULL)");
        } catch (SQLException exception) {
            throw new IllegalStateException("cannot initialize consumed event table", exception);
        }
    }

    @Override
    public void publish(CloudEvent event) {
        if (!claim(event.id())) return;
        try {
            delegate.publish(event);
        } catch (RuntimeException exception) {
            release(event.id());
            throw exception;
        }
    }

    private boolean claim(String id) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO of_consumed_events (event_id, consumed_at) VALUES (?, ?)")) {
            statement.setString(1, id); statement.setTimestamp(2, Timestamp.from(Instant.now()));
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            // Duplicate primary key means another consumer already claimed it.
            if (exception.getSQLState() != null && (exception.getSQLState().startsWith("23") || exception.getSQLState().equals("23505"))) return false;
            throw new IllegalStateException("cannot claim consumed event", exception);
        }
    }

    private void release(String id) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM of_consumed_events WHERE event_id = ?")) {
            statement.setString(1, id); statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IllegalStateException("cannot release failed event claim", exception);
        }
    }
}
