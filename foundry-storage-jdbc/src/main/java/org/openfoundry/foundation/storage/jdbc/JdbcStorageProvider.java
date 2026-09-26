package org.openfoundry.foundation.storage.jdbc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.AuditEntry;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.EntityOperation;
import org.openfoundry.foundation.spi.HistorySnapshot;
import org.openfoundry.foundation.spi.LinkRecord;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.OutboxEntry;
import org.openfoundry.foundation.spi.QueryOptions;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageCapabilities;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.Transaction;
import org.openfoundry.foundation.spi.TraversalResult;
import org.openfoundry.foundation.spi.TraversalStep;
import org.openfoundry.foundation.spi.schema.Cardinality;
import org.openfoundry.foundation.spi.schema.LinkTypeDefinition;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** JDBC StorageProvider using portable current-state and history tables. */
public final class JdbcStorageProvider implements StorageProvider, AutoCloseable {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final StorageCapabilities CAPABILITIES = new StorageCapabilities(
            true, true, false, false, false, true, false);

    private final DataSource dataSource;
    private final DatabaseDialect dialect;
    private final ObjectMapper objectMapper;
    private final Object schemaLock = new Object();
    private volatile OntologySchema schema;

    public JdbcStorageProvider(DataSource dataSource, DatabaseDialect dialect) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void applySchema(RequestContext context, OntologySchema schema) {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        synchronized (schemaLock) {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                for (String ddl : dialect.currentTablesDdl().split(";\\s*")) {
                    if (!ddl.isBlank()) statement.execute(ddl);
                }
                connection.commit();
                this.schema = schema;
            } catch (SQLException exception) {
                throw sqlError("apply schema", exception);
            }
        }
    }

    @Override
    public ObjectRecord getObject(RequestContext context, String type, String id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT tenant_id, object_type, object_id, version, created_at, updated_at,
                            deleted_at, last_transaction_id, last_action_id, properties_json
                     FROM of_objects WHERE tenant_id = ? AND object_type = ? AND object_id = ?
                     """)) {
            statement.setString(1, context.tenantId());
            statement.setString(2, type);
            statement.setString(3, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readObject(result) : null;
            }
        } catch (SQLException exception) {
            throw sqlError("read object", exception);
        }
    }

    @Override
    public List<ObjectRecord> queryObjects(RequestContext context, String type, QueryOptions options) {
        String deleted = options.includeDeleted() ? "" : " AND deleted_at IS NULL";
        String sql = """
                SELECT tenant_id, object_type, object_id, version, created_at, updated_at,
                       deleted_at, last_transaction_id, last_action_id, properties_json
                FROM of_objects WHERE tenant_id = ? AND object_type = ?
                """ + deleted + " ORDER BY object_id" + dialect.paginationClause();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, context.tenantId());
            statement.setString(2, type);
            statement.setInt(3, options.limit());
            statement.setInt(4, options.offset());
            try (ResultSet result = statement.executeQuery()) {
                List<ObjectRecord> objects = new ArrayList<>();
                while (result.next()) objects.add(readObject(result));
                return List.copyOf(objects);
            }
        } catch (SQLException exception) {
            throw sqlError("query objects", exception);
        }
    }

    @Override
    public HistorySnapshot getObjectAtVersion(RequestContext context, String type, String id, long version) {
        return readHistory(context, false, type, id,
                " AND version = ?", statement -> statement.setLong(4, version)).stream().findFirst().orElse(null);
    }

    @Override
    public HistorySnapshot getObjectAtTime(RequestContext context, String type, String id,
                                           Instant validTime, Instant recordedTime) {
        return readHistory(context, false, type, id,
                " AND recorded_at <= ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)"
                        + " ORDER BY recorded_at DESC, version DESC" + dialect.paginationClause(), statement -> {
                    statement.setTimestamp(4, timestamp(recordedTime));
                    statement.setTimestamp(5, timestamp(validTime));
                    statement.setTimestamp(6, timestamp(validTime));
                    statement.setInt(7, 1);
                    statement.setInt(8, 0);
                }).stream().findFirst().orElse(null);
    }

    @Override
    public LinkRecord getLink(RequestContext context, String type, String id) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(linkSelect()
                + " WHERE tenant_id = ? AND link_type = ? AND link_id = ?")) {
            statement.setString(1, context.tenantId());
            statement.setString(2, type);
            statement.setString(3, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? readLink(result) : null;
            }
        } catch (SQLException exception) {
            throw sqlError("read link", exception);
        }
    }

    @Override
    public List<LinkRecord> getLinks(RequestContext context, EntityKey endpoint, String linkType,
                                     Direction direction, QueryOptions options) {
        String endpointType = direction == Direction.OUTBOUND ? "from_type" : "to_type";
        String endpointId = direction == Direction.OUTBOUND ? "from_id" : "to_id";
        String deleted = options.includeDeleted() ? "" : " AND deleted_at IS NULL";
        String sql = linkSelect() + " WHERE tenant_id = ? AND link_type = ? AND " + endpointType
                + " = ? AND " + endpointId + " = ?" + deleted
                + " ORDER BY link_id" + dialect.paginationClause();
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, context.tenantId());
            statement.setString(2, linkType);
            statement.setString(3, endpoint.type());
            statement.setString(4, endpoint.id());
            statement.setInt(5, options.limit());
            statement.setInt(6, options.offset());
            try (ResultSet result = statement.executeQuery()) {
                List<LinkRecord> links = new ArrayList<>();
                while (result.next()) links.add(readLink(result));
                return List.copyOf(links);
            }
        } catch (SQLException exception) {
            throw sqlError("query links", exception);
        }
    }

    @Override
    public HistorySnapshot getLinkAtVersion(RequestContext context, String type, String id, long version) {
        return readHistory(context, true, type, id,
                " AND version = ?", statement -> statement.setLong(4, version)).stream().findFirst().orElse(null);
    }

    @Override
    public HistorySnapshot getLinkAtTime(RequestContext context, String type, String id,
                                         Instant validTime, Instant recordedTime) {
        return readHistory(context, true, type, id,
                " AND recorded_at <= ? AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)"
                        + " ORDER BY recorded_at DESC, version DESC" + dialect.paginationClause(), statement -> {
                    statement.setTimestamp(4, timestamp(recordedTime));
                    statement.setTimestamp(5, timestamp(validTime));
                    statement.setTimestamp(6, timestamp(validTime));
                    statement.setInt(7, 1);
                    statement.setInt(8, 0);
                }).stream().findFirst().orElse(null);
    }

    @Override
    public TraversalResult traverseAsOf(RequestContext context, EntityKey start,
                                        List<TraversalStep> path, Instant validTime,
                                        Instant recordedTime, QueryOptions options) {
        if (path.size() > 10) throw new IllegalArgumentException("traversal depth exceeds 10");
        List<EntityKey> frontier = List.of(start);
        List<HistorySnapshot> edges = new ArrayList<>();
        for (TraversalStep step : path) {
            List<EntityKey> next = new ArrayList<>();
            for (EntityKey endpoint : frontier) {
                String endpointType = step.direction() == Direction.OUTBOUND ? "from_type" : "to_type";
                String endpointId = step.direction() == Direction.OUTBOUND ? "from_id" : "to_id";
                String sql = "SELECT * FROM of_link_history WHERE tenant_id = ? AND link_type = ? AND "
                        + endpointType + " = ? AND " + endpointId + " = ? AND recorded_at <= ?"
                        + " AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)"
                        + " ORDER BY link_id, recorded_at DESC, version DESC";
                try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setString(1, context.tenantId()); statement.setString(2, step.linkType());
                    statement.setString(3, endpoint.type()); statement.setString(4, endpoint.id());
                    statement.setTimestamp(5, timestamp(recordedTime)); statement.setTimestamp(6, timestamp(validTime)); statement.setTimestamp(7, timestamp(validTime));
                    try (ResultSet result = statement.executeQuery()) {
                        Map<String, HistorySnapshot> latestByLink = new LinkedHashMap<>();
                        while (result.next()) {
                            HistorySnapshot snapshot = readHistory(result, true);
                            latestByLink.putIfAbsent(snapshot.key().id(), snapshot);
                        }
                        for (HistorySnapshot snapshot : latestByLink.values()) {
                            EntityKey target = step.direction() == Direction.OUTBOUND
                                    ? endpointTo(snapshot) : endpointFrom(snapshot);
                            edges.add(snapshot);
                            next.add(target);
                        }
                    }
                } catch (SQLException exception) { throw sqlError("temporal traversal", exception); }
            }
            frontier = next.stream().distinct().toList();
            if (frontier.isEmpty()) break;
        }
        return new TraversalResult(page(frontier, options), edges);
    }

    @Override
    public List<HistorySnapshot> getEntityHistory(RequestContext context, EntityKey key) {
        boolean link = schema != null && schema.linkTypes().stream().anyMatch(type -> type.name().equals(key.type()));
        String table = link ? "of_link_history" : "of_object_history";
        String typeColumn = link ? "link_type" : "object_type";
        String idColumn = link ? "link_id" : "object_id";
        String sql = "SELECT * FROM " + table + " WHERE tenant_id = ? AND " + typeColumn
                + " = ? AND " + idColumn + " = ? ORDER BY version";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, context.tenantId());
            statement.setString(2, key.type());
            statement.setString(3, key.id());
            try (ResultSet result = statement.executeQuery()) {
                List<HistorySnapshot> snapshots = new ArrayList<>();
                while (result.next()) snapshots.add(readHistory(result, link));
                return List.copyOf(snapshots);
            }
        } catch (SQLException exception) {
            throw sqlError("read entity history", exception);
        }
    }

    @Override
    public Transaction beginTransaction(RequestContext context) {
        try {
            Connection connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            return new JdbcTransaction(context, connection);
        } catch (SQLException exception) {
            throw sqlError("begin transaction", exception);
        }
    }

    @Override
    public StorageCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public void close() {
        // DataSource lifecycle belongs to the application/container.
    }

    private List<HistorySnapshot> readHistory(RequestContext context, boolean link, String type, String id,
                                              String suffix, SqlBinder binder) {
        String table = link ? "of_link_history" : "of_object_history";
        String typeColumn = link ? "link_type" : "object_type";
        String idColumn = link ? "link_id" : "object_id";
        String sql = "SELECT * FROM " + table + " WHERE tenant_id = ? AND " + typeColumn
                + " = ? AND " + idColumn + " = ?" + suffix;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, context.tenantId());
            statement.setString(2, type);
            statement.setString(3, id);
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                List<HistorySnapshot> snapshots = new ArrayList<>();
                while (result.next()) snapshots.add(readHistory(result, link));
                return snapshots;
            }
        } catch (SQLException exception) {
            throw sqlError("read history", exception);
        }
    }

    private ObjectRecord readObject(ResultSet result) throws SQLException {
        return new ObjectRecord(result.getString("tenant_id"), result.getString("object_type"),
                result.getString("object_id"), result.getLong("version"), instant(result, "created_at"),
                instant(result, "updated_at"), instantNullable(result, "deleted_at"),
                result.getString("last_transaction_id"), result.getString("last_action_id"),
                jsonMap(result.getString("properties_json")));
    }

    private LinkRecord readLink(ResultSet result) throws SQLException {
        return new LinkRecord(result.getString("tenant_id"), result.getString("link_type"),
                result.getString("link_id"), new EntityKey(result.getString("from_type"), result.getString("from_id")),
                new EntityKey(result.getString("to_type"), result.getString("to_id")), result.getLong("version"),
                instant(result, "created_at"), instant(result, "updated_at"), instantNullable(result, "deleted_at"),
                instant(result, "valid_from"), instantNullable(result, "valid_to"),
                result.getString("last_transaction_id"), result.getString("last_action_id"),
                jsonMap(result.getString("properties_json")));
    }

    private HistorySnapshot readHistory(ResultSet result, boolean link) throws SQLException {
        String type = result.getString(link ? "link_type" : "object_type");
        String id = result.getString(link ? "link_id" : "object_id");
        Map<String, Object> state = jsonMap(result.getString("state_json"));
        if (link) {
            state = new LinkedHashMap<>(state);
            state.putIfAbsent("_fromType", result.getString("from_type"));
            state.putIfAbsent("_fromId", result.getString("from_id"));
            state.putIfAbsent("_toType", result.getString("to_type"));
            state.putIfAbsent("_toId", result.getString("to_id"));
        }
        return new HistorySnapshot(new EntityKey(type, id), result.getLong("version"),
                EntityOperation.valueOf(result.getString("operation")), instant(result, "valid_from"),
                instantNullable(result, "valid_to"), instant(result, "recorded_at"),
                result.getString("transaction_id"), result.getString("action_id"),
                result.getString("actor_id"), result.getString("source_system"), state);
    }

    private static String linkSelect() {
        return "SELECT tenant_id, link_type, link_id, from_type, from_id, to_type, to_id, version, "
                + "created_at, updated_at, deleted_at, valid_from, valid_to, last_transaction_id, "
                + "last_action_id, properties_json FROM of_links";
    }

    private static EntityKey endpointFrom(HistorySnapshot snapshot) {
        return new EntityKey(String.valueOf(snapshot.state().get("_fromType")), String.valueOf(snapshot.state().get("_fromId")));
    }

    private static EntityKey endpointTo(HistorySnapshot snapshot) {
        return new EntityKey(String.valueOf(snapshot.state().get("_toType")), String.valueOf(snapshot.state().get("_toId")));
    }

    private static <T> List<T> page(List<T> values, QueryOptions options) {
        int from = Math.min(options.offset(), values.size());
        int to = Math.min(from + options.limit(), values.size());
        return List.copyOf(values.subList(from, to));
    }

    private Instant instant(ResultSet result, String column) throws SQLException {
        return result.getTimestamp(column).toInstant();
    }

    private Instant instantNullable(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private String json(Map<String, Object> properties) {
        try {
            return objectMapper.writeValueAsString(properties);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("properties cannot be serialized", exception);
        }
    }

    private Map<String, Object> jsonMap(String value) {
        try {
            Map<String, Object> result = objectMapper.readValue(value, MAP_TYPE);
            return result == null ? Map.of() : result;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored properties are not valid JSON", exception);
        }
    }

    private void requireObjectType(String type) {
        if (schema == null || schema.objectTypes().stream().noneMatch(candidate -> candidate.name().equals(type))) {
            throw new IllegalArgumentException("unknown object type: " + type);
        }
    }

    private LinkTypeDefinition requireLinkType(String type) {
        if (schema == null) throw new IllegalStateException("schema has not been applied");
        return schema.linkTypes().stream().filter(candidate -> candidate.name().equals(type)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown link type: " + type));
    }

    private RuntimeException sqlError(String operation, SQLException exception) {
        return new IllegalStateException("JDBC operation failed: " + operation, exception);
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private final class JdbcTransaction implements Transaction {
        private final RequestContext context;
        private final Connection connection;
        private final String transactionId = UUID.randomUUID().toString();
        private boolean closed;

        private JdbcTransaction(RequestContext context, Connection connection) {
            this.context = context;
            this.connection = connection;
        }

        @Override
        public String transactionId() {
            return transactionId;
        }

        @Override
        public ObjectRecord createObject(String type, String id, Map<String, Object> properties) {
            assertOpen();
            requireObjectType(type);
            if (findObject(type, id) != null) throw new IllegalStateException("object already exists: " + type + ":" + id);
            Instant now = Instant.now();
            String sql = "INSERT INTO of_objects (tenant_id, object_type, object_id, version, created_at, updated_at, "
                    + "deleted_at, last_transaction_id, last_action_id, properties_json) VALUES (?, ?, ?, 1, ?, ?, NULL, ?, NULL, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, context.tenantId()); statement.setString(2, type); statement.setString(3, id);
                statement.setTimestamp(4, timestamp(now)); statement.setTimestamp(5, timestamp(now));
                statement.setString(6, transactionId); statement.setString(7, json(properties)); statement.executeUpdate();
                insertObjectHistory(type, id, 1, EntityOperation.CREATED, now, null, now, properties);
                return findObject(type, id);
            } catch (SQLException exception) { throw sqlError("create object", exception); }
        }

        @Override
        public ObjectRecord updateObject(String type, String id, Map<String, Object> properties, long expectedVersion) {
            assertOpen();
            ObjectRecord existing = requireObject(findObject(type, id), type, id);
            assertVersion(existing.version(), expectedVersion);
            Map<String, Object> merged = new HashMap<>(existing.properties()); merged.putAll(properties);
            Instant now = Instant.now(); long version = existing.version() + 1;
            String sql = "UPDATE of_objects SET version = ?, updated_at = ?, last_transaction_id = ?, properties_json = ? "
                    + "WHERE tenant_id = ? AND object_type = ? AND object_id = ? AND version = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, version); statement.setTimestamp(2, timestamp(now)); statement.setString(3, transactionId);
                statement.setString(4, json(merged)); statement.setString(5, context.tenantId()); statement.setString(6, type);
                statement.setString(7, id); statement.setLong(8, expectedVersion);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("version conflict during object update");
                insertObjectHistory(type, id, version, EntityOperation.UPDATED, existing.updatedAt(), null, now, merged);
                return findObject(type, id);
            } catch (SQLException exception) { throw sqlError("update object", exception); }
        }

        @Override
        public void deleteObject(String type, String id, long expectedVersion) {
            assertOpen();
            ObjectRecord existing = requireObject(findObject(type, id), type, id);
            assertVersion(existing.version(), expectedVersion);
            Instant now = Instant.now(); long version = existing.version() + 1;
            String sql = "UPDATE of_objects SET version = ?, updated_at = ?, deleted_at = ?, last_transaction_id = ? "
                    + "WHERE tenant_id = ? AND object_type = ? AND object_id = ? AND version = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, version); statement.setTimestamp(2, timestamp(now)); statement.setTimestamp(3, timestamp(now));
                statement.setString(4, transactionId); statement.setString(5, context.tenantId()); statement.setString(6, type);
                statement.setString(7, id); statement.setLong(8, expectedVersion);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("version conflict during object delete");
                insertObjectHistory(type, id, version, EntityOperation.DELETED, existing.updatedAt(), now, now, existing.properties());
            } catch (SQLException exception) { throw sqlError("delete object", exception); }
        }

        @Override
        public LinkRecord createLink(String type, String id, EntityKey from, EntityKey to, Map<String, Object> properties) {
            assertOpen(); LinkTypeDefinition definition = requireLinkType(type);
            requireActiveObject(from); requireActiveObject(to);
            if (findLink(type, id) != null) throw new IllegalStateException("link already exists: " + type + ":" + id);
            enforceCardinality(definition, from, to);
            Instant now = Instant.now();
            String sql = "INSERT INTO of_links (tenant_id, link_type, link_id, from_type, from_id, to_type, to_id, version, "
                    + "created_at, updated_at, deleted_at, valid_from, valid_to, last_transaction_id, last_action_id, properties_json) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, NULL, ?, NULL, ?, NULL, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, context.tenantId()); statement.setString(2, type); statement.setString(3, id);
                statement.setString(4, from.type()); statement.setString(5, from.id()); statement.setString(6, to.type()); statement.setString(7, to.id());
                statement.setTimestamp(8, timestamp(now)); statement.setTimestamp(9, timestamp(now)); statement.setTimestamp(10, timestamp(now));
                statement.setString(11, transactionId); statement.setString(12, json(properties)); statement.executeUpdate();
                insertLinkHistory(type, id, from, to, 1, EntityOperation.CREATED, now, null, now, properties);
                return findLink(type, id);
            } catch (SQLException exception) { throw sqlError("create link", exception); }
        }

        @Override
        public LinkRecord updateLink(String type, String id, Map<String, Object> properties, long expectedVersion) {
            assertOpen(); LinkRecord existing = requireLink(findLink(type, id), type, id);
            assertVersion(existing.version(), expectedVersion);
            Map<String, Object> merged = new HashMap<>(existing.properties()); merged.putAll(properties);
            Instant now = Instant.now(); long version = existing.version() + 1;
            String sql = "UPDATE of_links SET version = ?, updated_at = ?, last_transaction_id = ?, properties_json = ? "
                    + "WHERE tenant_id = ? AND link_type = ? AND link_id = ? AND version = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, version); statement.setTimestamp(2, timestamp(now)); statement.setString(3, transactionId);
                statement.setString(4, json(merged)); statement.setString(5, context.tenantId()); statement.setString(6, type); statement.setString(7, id); statement.setLong(8, expectedVersion);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("version conflict during link update");
                insertLinkHistory(type, id, existing.from(), existing.to(), version, EntityOperation.UPDATED,
                        existing.validFrom(), existing.validTo(), now, merged);
                return findLink(type, id);
            } catch (SQLException exception) { throw sqlError("update link", exception); }
        }

        @Override
        public void deleteLink(String type, String id, long expectedVersion) {
            assertOpen(); LinkRecord existing = requireLink(findLink(type, id), type, id);
            assertVersion(existing.version(), expectedVersion); Instant now = Instant.now(); long version = existing.version() + 1;
            String sql = "UPDATE of_links SET version = ?, updated_at = ?, deleted_at = ?, valid_to = ?, last_transaction_id = ? "
                    + "WHERE tenant_id = ? AND link_type = ? AND link_id = ? AND version = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, version); statement.setTimestamp(2, timestamp(now)); statement.setTimestamp(3, timestamp(now)); statement.setTimestamp(4, timestamp(now)); statement.setString(5, transactionId);
                statement.setString(6, context.tenantId()); statement.setString(7, type); statement.setString(8, id); statement.setLong(9, expectedVersion);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("version conflict during link delete");
                insertLinkHistory(type, id, existing.from(), existing.to(), version, EntityOperation.DELETED,
                        existing.validFrom(), now, now, existing.properties());
            } catch (SQLException exception) { throw sqlError("delete link", exception); }
        }

        @Override
        public void appendAudit(AuditEntry audit) {
            assertOpen();
            if (!audit.tenantId().equals(context.tenantId())) throw new IllegalArgumentException("audit tenant mismatch");
            String sql = "INSERT INTO of_audit_records (id, tenant_id, timestamp_value, actor_id, operation_type, object_type, object_id, action_type, transaction_id, result, detail_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, audit.id()); statement.setString(2, audit.tenantId()); statement.setTimestamp(3, timestamp(audit.timestamp()));
                statement.setString(4, audit.actorId()); statement.setString(5, audit.operationType()); statement.setString(6, audit.objectType());
                statement.setString(7, audit.objectId()); statement.setString(8, audit.actionType()); statement.setString(9, audit.transactionId());
                statement.setString(10, audit.result()); statement.setString(11, json(audit.detail())); statement.executeUpdate();
            } catch (SQLException exception) { throw sqlError("append transactional audit", exception); }
        }

        @Override
        public void enqueueOutbox(OutboxEntry event) {
            assertOpen();
            if (!event.tenantId().equals(context.tenantId())) throw new IllegalArgumentException("event tenant mismatch");
            String sql = "INSERT INTO of_outbox_events (id, tenant_id, type, subject, occurred_at, transaction_id, data_json, published_at) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, event.id()); statement.setString(2, event.tenantId()); statement.setString(3, event.type());
                statement.setString(4, event.subject()); statement.setTimestamp(5, timestamp(event.occurredAt()));
                statement.setString(6, event.transactionId()); statement.setString(7, json(event.data())); statement.executeUpdate();
            } catch (SQLException exception) { throw sqlError("enqueue transactional outbox event", exception); }
        }

        @Override
        public void commit() {
            assertOpen();
            try { connection.commit(); closed = true; connection.close(); }
            catch (SQLException exception) { throw sqlError("commit transaction", exception); }
        }

        @Override
        public void rollback() {
            if (closed) return;
            try { connection.rollback(); closed = true; connection.close(); }
            catch (SQLException exception) { throw sqlError("rollback transaction", exception); }
        }

        @Override
        public void close() { rollback(); }

        private ObjectRecord findObject(String type, String id) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM of_objects WHERE tenant_id = ? AND object_type = ? AND object_id = ?")) {
                statement.setString(1, context.tenantId()); statement.setString(2, type); statement.setString(3, id);
                try (ResultSet result = statement.executeQuery()) { return result.next() ? readObject(result) : null; }
            } catch (SQLException exception) { throw sqlError("read transactional object", exception); }
        }

        private LinkRecord findLink(String type, String id) {
            try (PreparedStatement statement = connection.prepareStatement(linkSelect() + " WHERE tenant_id = ? AND link_type = ? AND link_id = ?")) {
                statement.setString(1, context.tenantId()); statement.setString(2, type); statement.setString(3, id);
                try (ResultSet result = statement.executeQuery()) { return result.next() ? readLink(result) : null; }
            } catch (SQLException exception) { throw sqlError("read transactional link", exception); }
        }

        private void requireActiveObject(EntityKey key) {
            ObjectRecord object = findObject(key.type(), key.id());
            if (object == null || object.isDeleted()) throw new IllegalStateException("link endpoint is not active: " + key);
        }

        private void enforceCardinality(LinkTypeDefinition definition, EntityKey from, EntityKey to) {
            String predicate = switch (definition.cardinality()) {
                case ONE_TO_ONE -> "(from_type = ? AND from_id = ?) OR (to_type = ? AND to_id = ?)";
                case ONE_TO_MANY -> "to_type = ? AND to_id = ?";
                case MANY_TO_ONE -> "from_type = ? AND from_id = ?";
                case MANY_TO_MANY -> null;
            };
            if (predicate == null) return;
            String sql = "SELECT COUNT(*) FROM of_links WHERE tenant_id = ? AND link_type = ? AND deleted_at IS NULL AND (" + predicate + ")";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, context.tenantId()); statement.setString(2, definition.name());
                if (definition.cardinality() == Cardinality.ONE_TO_ONE) {
                    statement.setString(3, from.type()); statement.setString(4, from.id()); statement.setString(5, to.type()); statement.setString(6, to.id());
                } else {
                    EntityKey endpoint = definition.cardinality() == Cardinality.ONE_TO_MANY ? to : from;
                    statement.setString(3, endpoint.type()); statement.setString(4, endpoint.id());
                }
                try (ResultSet result = statement.executeQuery()) { if (result.next() && result.getLong(1) > 0) throw new IllegalStateException("link cardinality violated: " + definition.name()); }
            } catch (SQLException exception) { throw sqlError("check link cardinality", exception); }
        }

        private void insertObjectHistory(String type, String id, long version, EntityOperation operation,
                                         Instant validFrom, Instant validTo, Instant recordedAt, Map<String, Object> state) throws SQLException {
            String sql = "INSERT INTO of_object_history (tenant_id, object_type, object_id, version, operation, valid_from, valid_to, recorded_at, transaction_id, action_id, actor_id, source_system, state_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, context.tenantId()); statement.setString(2, type); statement.setString(3, id); statement.setLong(4, version); statement.setString(5, operation.name());
                statement.setTimestamp(6, timestamp(validFrom)); setNullableTimestamp(statement, 7, validTo); statement.setTimestamp(8, timestamp(recordedAt)); statement.setString(9, transactionId); statement.setString(10, context.actorId()); statement.setString(11, json(state)); statement.executeUpdate();
            }
        }

        private void insertLinkHistory(String type, String id, EntityKey from, EntityKey to, long version, EntityOperation operation,
                                       Instant validFrom, Instant validTo, Instant recordedAt, Map<String, Object> state) throws SQLException {
            String sql = "INSERT INTO of_link_history (tenant_id, link_type, link_id, from_type, from_id, to_type, to_id, version, operation, valid_from, valid_to, recorded_at, transaction_id, action_id, actor_id, source_system, state_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, context.tenantId()); statement.setString(2, type); statement.setString(3, id); statement.setString(4, from.type()); statement.setString(5, from.id()); statement.setString(6, to.type()); statement.setString(7, to.id()); statement.setLong(8, version); statement.setString(9, operation.name());
                statement.setTimestamp(10, timestamp(validFrom)); setNullableTimestamp(statement, 11, validTo); statement.setTimestamp(12, timestamp(recordedAt)); statement.setString(13, transactionId); statement.setString(14, context.actorId()); statement.setString(15, json(state)); statement.executeUpdate();
            }
        }

        private static void setNullableTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
            if (value == null) statement.setTimestamp(index, null); else statement.setTimestamp(index, timestamp(value));
        }

        private void assertOpen() { if (closed) throw new IllegalStateException("transaction is closed"); }
    }

    private static ObjectRecord requireObject(ObjectRecord object, String type, String id) {
        if (object == null) throw new IllegalArgumentException("object not found: " + type + ":" + id);
        return object;
    }

    private static LinkRecord requireLink(LinkRecord link, String type, String id) {
        if (link == null) throw new IllegalArgumentException("link not found: " + type + ":" + id);
        return link;
    }

    private static void assertVersion(long actual, long expected) {
        if (actual != expected) throw new IllegalStateException("version conflict: expected " + expected + ", current " + actual);
    }
}
