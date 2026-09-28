package org.openfoundry.foundation.storage.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.*;

import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Constant-size entries; current membership is read from links, not copied into every assertion. */
final class JdbcRelationshipAssertions {
    private final DatabaseDialect dialect;
    private final ObjectMapper mapper;
    JdbcRelationshipAssertions(DatabaseDialect dialect, ObjectMapper mapper) { this.dialect = dialect; this.mapper = mapper; }

    List<RelationshipAssertion> read(Connection connection, RequestContext context, RelationshipScope scope, int limit, Long before) throws SQLException {
        if (limit < 0 || limit > 1000 || before != null && before < 1) throw new IllegalArgumentException("Invalid relationship assertion page");
        String sql = "SELECT * FROM of_relationship_assertions WHERE tenant_id = ? AND scope_key = ?"
                + (before == null ? "" : " AND revision < ?") + " ORDER BY revision DESC" + dialect.paginationClause();
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, context.tenantId()); statement.setString(2, scope.key());
            int index = 3;
            if (before != null) statement.setLong(index++, before);
            statement.setInt(index++, limit); statement.setInt(index, 0);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<RelationshipAssertion>();
                while (rows.next()) {
                    var stored = new RelationshipScope(new EntityKey(rows.getString("endpoint_type"), rows.getString("endpoint_id")),
                            rows.getString("link_type"), StorageProvider.Direction.valueOf(rows.getString("direction")));
                    if (!stored.equals(scope)) throw new IllegalStateException("Relationship scope identity mismatch");
                    try {
                        Map<String, Object> source = mapper.readValue(rows.getString("source_json"), new TypeReference<>() {});
                        String operation = rows.getString("operation");
                        result.add(new RelationshipAssertion(scope, rows.getLong("revision"), operation == null ? null : EntityOperation.valueOf(operation),
                                rows.getString("link_id"), rows.getTimestamp("recorded_at").toInstant(), rows.getString("transaction_id"), rows.getString("actor_id"), MutationSource.fromMap(source)));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Invalid relationship provenance", invalid); }
                }
                return List.copyOf(result);
            }
        }
    }

    void append(Connection connection, RequestContext context, RelationshipScope scope, EntityOperation operation, String linkId,
                MutationSource source, Instant now, String transactionId, Long expected) throws SQLException {
        var previous = read(connection, context, scope, 1, null);
        long revision = previous.isEmpty() ? 0 : previous.getFirst().revision();
        if (expected != null && revision != expected) throw new TransactionConflictException("Relationship scope changed");
        var assertion = new RelationshipAssertion(scope, Math.incrementExact(revision), operation, linkId, now, transactionId, context.actorId(), source);
        String encoded;
        try { encoded = mapper.writeValueAsString(source.toMap()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new SQLException("Cannot encode relationship provenance", failure); }
        try (var statement = connection.prepareStatement("INSERT INTO of_relationship_assertions (tenant_id, scope_key, revision, endpoint_type, endpoint_id, link_type, direction, operation, link_id, recorded_at, transaction_id, actor_id, source_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, context.tenantId()); statement.setString(2, scope.key()); statement.setLong(3, assertion.revision());
            statement.setString(4, scope.endpoint().type()); statement.setString(5, scope.endpoint().id()); statement.setString(6, scope.linkType());
            statement.setString(7, scope.direction().name()); statement.setString(8, operation == null ? null : operation.name()); statement.setString(9, linkId);
            statement.setTimestamp(10, Timestamp.from(now)); statement.setString(11, transactionId); statement.setString(12, context.actorId()); statement.setString(13, encoded);
            statement.executeUpdate();
        }
    }
}
