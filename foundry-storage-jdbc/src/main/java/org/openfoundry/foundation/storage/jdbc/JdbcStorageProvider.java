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
import org.openfoundry.foundation.spi.TemporalHistory;
import org.openfoundry.foundation.spi.MutationSource;
import org.openfoundry.foundation.spi.FieldProvenance;
import org.openfoundry.foundation.spi.LineageQuery;
import org.openfoundry.foundation.spi.LineageValues;
import org.openfoundry.foundation.spi.IngestionReceipt;
import org.openfoundry.foundation.spi.IngestionCheckpoint;
import java.time.Clock;
import org.openfoundry.foundation.spi.schema.PropertyValues;
import org.openfoundry.foundation.spi.schema.UniquePropertyIndex;
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
            true, true, false, false, false, true, false, true, true, true);

    private final DataSource dataSource;
    private final Clock clock;
    private final org.openfoundry.foundation.validation.PropertyValidator propertyValidator = new org.openfoundry.foundation.validation.PropertyValidator();
    private final DatabaseDialect dialect;
    private final ObjectMapper objectMapper;
    private final JdbcLineage lineage;
    private final Object schemaLock = new Object();
    private boolean tablesInitialized;
    private volatile JdbcSchemaActivation.Binding binding;
    private final JdbcSchemaActivation activation;

    public JdbcStorageProvider(DataSource dataSource, DatabaseDialect dialect) {
        this(dataSource, dialect, Clock.systemUTC());
    }

    public JdbcStorageProvider(DataSource dataSource, DatabaseDialect dialect, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.dialect = Objects.requireNonNull(dialect, "dialect must not be null");
        this.objectMapper = new ObjectMapper().registerModule(JsonNumbers.module());
        this.lineage = new JdbcLineage(dialect, objectMapper);
        this.activation = new JdbcSchemaActivation(dataSource, dialect, clock);
    }

    @Override
    public void applySchema(RequestContext context, OntologySchema schema) {
        Objects.requireNonNull(context, "context must not be null");
        propertyValidator.validateSchema(schema);
        synchronized (schemaLock) {
            initializeTables();
            binding = activation.apply(context, schema, null, null, true);
        }
    }

    /** Explicit deployment operation; approval never substitutes for current-state data validation. */
    public org.openfoundry.foundation.schema.SchemaVersion activateSchema(RequestContext context, OntologySchema schema,
                                                                         org.openfoundry.foundation.schema.MigrationPlan plan, int expectedActiveVersion) {
        Objects.requireNonNull(context, "context must not be null");
        propertyValidator.validateSchema(schema);
        synchronized (schemaLock) {
            initializeTables();
            binding = activation.apply(context, schema, plan, expectedActiveVersion, false);
            return binding.version();
        }
    }

    public int boundSchemaVersion() {
        var current = binding;
        return current == null ? 0 : current.version().version();
    }

    private void initializeTables() {
        if (tablesInitialized) return;
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                for (String ddl : dialect.currentTablesDdl().split(";\\s*")) {
                    if (!ddl.isBlank()) statement.execute(ddl);
                }
            }
            ensureHistoryFormat(connection, "of_object_history");
            ensureHistoryFormat(connection, "of_link_history");
            tablesInitialized = true;
        } catch (SQLException exception) { throw sqlError("initialize storage tables", exception); }
    }

    @Override
    public org.openfoundry.foundation.spi.SchemaBinding schemaBinding() {
        var current = binding;
        return current == null ? null : new org.openfoundry.foundation.spi.SchemaBinding(current.id(), current.schema());
    }

    @Override
    public void requireSchemaBinding(RequestContext context, org.openfoundry.foundation.spi.SchemaBinding expected) {
        var current = binding;
        if (expected == null || current == null || !current.id().equals(expected.id())) {
            throw new org.openfoundry.foundation.spi.SchemaVersionMismatchException();
        }
        try (var connection = dataSource.getConnection()) {
            activation.requireBinding(connection, current, false);
        } catch (SQLException failure) { throw sqlError("verify read schema binding", failure); }
    }

    private <T> T schemaRead(RequestContext context, java.util.function.Supplier<T> reader) {
        var expected = schemaBinding();
        requireSchemaBinding(context, expected);
        try { return reader.get(); }
        finally { requireSchemaBinding(context, expected); }
    }

    @Override
    public ObjectRecord getObject(RequestContext context, String type, String id) {
        return schemaRead(context, () -> {
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
        });
    }

    @Override
    public List<ObjectRecord> queryObjects(RequestContext context, String type, QueryOptions options) {
        return schemaRead(context, () -> {
            if (options.asOfValidTime() != null) {
                try (Connection connection = dataSource.getConnection()) {
                    var history = readTemporalHistory(connection, context, false, type, null, options.asOfRecordedTime());
                    var rows = TemporalHistory.group(history).values().stream().map(versions -> {
                        var snapshot = TemporalHistory.at(versions, options.asOfValidTime(), options.asOfRecordedTime());
                        return snapshot == null ? null : TemporalHistory.object(context.tenantId(), snapshot, TemporalHistory.createdAt(versions));
                    }).filter(Objects::nonNull).filter(object -> options.includeDeleted() || !object.isDeleted())
                            .sorted(java.util.Comparator.comparing(ObjectRecord::id)).toList();
                    return TemporalHistory.page(rows, options);
                } catch (SQLException failure) { throw sqlError("temporal object list", failure); }
            }
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
        });
    }

    @Override
    public HistorySnapshot getObjectAtVersion(RequestContext context, String type, String id, long version) {
        return schemaRead(context, () -> {
            return readHistory(context, false, type, id,
                    " AND version = ?", statement -> statement.setLong(4, version)).stream().findFirst().orElse(null);
        });
    }

    @Override
    public HistorySnapshot getObjectAtTime(RequestContext context, String type, String id,
                                           Instant validTime, Instant recordedTime) {
        return schemaRead(context, () -> {
            try (Connection connection = dataSource.getConnection()) {
                return TemporalHistory.at(readTemporalHistory(connection, context, false, type, id, recordedTime), validTime, recordedTime);
            } catch (SQLException failure) { throw sqlError("temporal object read", failure); }
        });
    }

    @Override
    public LinkRecord getLink(RequestContext context, String type, String id) {
        return schemaRead(context, () -> {
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
        });
    }

    @Override
    public List<LinkRecord> getLinks(RequestContext context, EntityKey endpoint, String linkType,
                                     Direction direction, QueryOptions options) {
        return schemaRead(context, () -> {
            if (options.asOfValidTime() != null) {
                try (Connection connection = dataSource.getConnection()) {
                    var history = readTemporalHistory(connection, context, true, linkType, null, options.asOfRecordedTime());
                    var rows = TemporalHistory.group(history).values().stream().map(versions -> {
                        var snapshot = TemporalHistory.at(versions, options.asOfValidTime(), options.asOfRecordedTime());
                        return snapshot == null ? null : TemporalHistory.link(context.tenantId(), snapshot, versions, options.asOfRecordedTime());
                    }).filter(Objects::nonNull)
                            .filter(link -> direction == Direction.OUTBOUND ? link.from().equals(endpoint) : link.to().equals(endpoint))
                            .filter(link -> options.includeDeleted() || !link.isDeleted())
                            .sorted(java.util.Comparator.comparing(LinkRecord::id)).toList();
                    return TemporalHistory.page(rows, options);
                } catch (SQLException failure) { throw sqlError("temporal link list", failure); }
            }
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
        });
    }

    @Override
    public HistorySnapshot getLinkAtVersion(RequestContext context, String type, String id, long version) {
        return schemaRead(context, () -> {
            return readHistory(context, true, type, id,
                    " AND version = ?", statement -> statement.setLong(4, version)).stream().findFirst().orElse(null);
        });
    }

    @Override
    public HistorySnapshot getLinkAtTime(RequestContext context, String type, String id,
                                         Instant validTime, Instant recordedTime) {
        return schemaRead(context, () -> {
            try (Connection connection = dataSource.getConnection()) {
                return TemporalHistory.at(readTemporalHistory(connection, context, true, type, id, recordedTime), validTime, recordedTime);
            } catch (SQLException failure) { throw sqlError("temporal link read", failure); }
        });
    }

    @Override
    public TraversalResult traverseAsOf(RequestContext context, EntityKey start,
                                        List<TraversalStep> path, Instant validTime,
                                        Instant recordedTime, QueryOptions options) {
        return schemaRead(context, () -> {
            if (path.size() > 10) throw new IllegalArgumentException("traversal depth exceeds 10");
            try (Connection connection = dataSource.getConnection()) {
                connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try {
                    var objectTypes = new java.util.HashSet<String>();
                    objectTypes.add(start.type());
                    var linkTypes = new java.util.HashSet<String>();
                    for (var step : path) {
                        var definition = requireLinkType(step.linkType());
                        linkTypes.add(definition.name());
                        objectTypes.add(definition.fromType());
                        objectTypes.add(definition.toType());
                    }
                    var objects = new ArrayList<HistorySnapshot>();
                    var links = new ArrayList<HistorySnapshot>();
                    for (String type : objectTypes) objects.addAll(readTemporalHistory(connection, context, false, type, null, recordedTime));
                    for (String type : linkTypes) links.addAll(readTemporalHistory(connection, context, true, type, null, recordedTime));
                    var result = TemporalHistory.traverse(objects, links, start, path, validTime, recordedTime, options);
                    connection.commit();
                    return result;
                } catch (RuntimeException | SQLException failure) {
                    connection.rollback();
                    throw failure;
                }
            } catch (SQLException failure) { throw sqlError("temporal traversal", failure); }
        });
    }

    @Override
    public List<FieldProvenance> getLineage(RequestContext context, EntityKey key, LineageQuery query) {
        return schemaRead(context, () -> {
            try (var connection = dataSource.getConnection()) { return lineage.query(connection, context, key, query); }
            catch (SQLException failure) { throw sqlError("read field lineage", failure); }
        });
    }

    @Override
    public IngestionCheckpoint getIngestionCheckpoint(RequestContext context, String key) {
        IngestionReceipt.requireHash(key);
        return schemaRead(context, () -> {
            try (var connection = dataSource.getConnection()) { return readIngestionCheckpoint(connection, context, key); }
            catch (SQLException failure) { throw sqlError("read ingestion checkpoint", failure); }
        });
    }

    private IngestionCheckpoint readIngestionCheckpoint(Connection connection, RequestContext context, String key) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM of_ingestion_checkpoints WHERE tenant_id = ? AND checkpoint_key = ?")) {
            statement.setString(1, context.tenantId());
            statement.setString(2, key);
            try (var row = statement.executeQuery()) {
                return row.next() ? new IngestionCheckpoint(key, row.getLong("version"), row.getLong("source_sequence"),
                        jsonMap(row.getString("token_json")).get("value"), row.getString("source_system"), row.getString("configuration_hash")) : null;
            }
        }
    }

    @Override
    public List<HistorySnapshot> getEntityHistory(RequestContext context, EntityKey key) {
        return schemaRead(context, () -> {
            var current = binding;
            boolean link = current != null && current.schema().linkTypes().stream().anyMatch(type -> type.name().equals(key.type()));
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
        });
    }

    @Override
    public List<org.openfoundry.foundation.spi.ActionExecution> pendingActions(RequestContext context, Instant now, int limit) {
        return schemaRead(context, () -> {
            if (limit < 1 || limit > 10000) throw new IllegalArgumentException("Invalid continuation limit");
            String sql = "SELECT * FROM of_action_executions WHERE tenant_id = ? AND actor_id = ? AND available_at <= ?"
                    + " ORDER BY available_at, execution_id" + dialect.paginationClause();
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
                statement.setString(1, context.tenantId());
                statement.setString(2, context.actorId());
                statement.setTimestamp(3, timestamp(now));
                statement.setInt(4, limit);
                statement.setInt(5, 0);
                try (var rows = statement.executeQuery()) {
                    var result = new ArrayList<org.openfoundry.foundation.spi.ActionExecution>();
                    while (rows.next()) result.add(readActionExecution(rows));
                    return List.copyOf(result);
                }
            } catch (SQLException failure) {
                throw sqlError("list action continuations", failure);
            }
        });
    }

    private org.openfoundry.foundation.spi.ActionExecution readActionExecution(ResultSet row) throws SQLException {
        var available = row.getTimestamp("available_at");
        return new org.openfoundry.foundation.spi.ActionExecution(row.getString("execution_id"), row.getString("actor_id"),
                row.getString("action_name"), row.getLong("version"), row.getString("status"),
                available == null ? null : available.toInstant(), jsonMap(row.getString("state_json")));
    }

    @Override
    public Transaction beginTransaction(RequestContext context) {
        var current = binding;
        if (current == null) throw new IllegalStateException("schema has not been applied");
        initializeWriteGuard(context);
        try {
            Connection connection = dataSource.getConnection();
            // After waiting on the tenant row, subsequent reads must see the previous writer's commit.
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            return new JdbcTransaction(context, connection, current);
        } catch (SQLException exception) {
            throw sqlError("begin transaction", exception);
        }
    }

    @Override
    public Transaction beginTransaction(RequestContext context, org.openfoundry.foundation.spi.SchemaBinding expected) {
        var current = binding;
        if (expected == null || current == null || !current.id().equals(expected.id())) {
            throw new org.openfoundry.foundation.spi.SchemaVersionMismatchException();
        }
        initializeWriteGuard(context);
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            activation.requireBinding(connection, current, false);
            return new JdbcTransaction(context, connection, current);
        } catch (SQLException | RuntimeException failure) {
            if (connection != null) {
                try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
            }
            if (failure instanceof SQLException sql) throw sqlError("begin bound transaction", sql);
            throw (RuntimeException) failure;
        }
    }

    private void initializeWriteGuard(RequestContext context) {
        try (Connection initialization = dataSource.getConnection()) {
            initialization.setAutoCommit(true);
            try (var exists = initialization.prepareStatement("SELECT tenant_id FROM of_write_guards WHERE tenant_id = ?")) {
                exists.setString(1, context.tenantId());
                try (var row = exists.executeQuery()) {
                    if (row.next()) return;
                }
            }
            try (var insert = initialization.prepareStatement("INSERT INTO of_write_guards (tenant_id) VALUES (?)")) {
                insert.setString(1, context.tenantId());
                insert.executeUpdate();
            } catch (SQLException duplicate) {
                if (!"23505".equals(duplicate.getSQLState())) throw duplicate;
            }
        } catch (SQLException failure) {
            throw sqlError("initialize tenant write guard", failure);
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

    private static boolean hasHistoryFormat(Connection connection, String table) throws SQLException {
        try (var columns = connection.getMetaData().getColumns(null, connection.getSchema(), null, null)) {
            while (columns.next()) {
                if (table.equalsIgnoreCase(columns.getString("TABLE_NAME")) && "temporal_format".equalsIgnoreCase(columns.getString("COLUMN_NAME"))) return true;
            }
            return false;
        }
    }

    private static void ensureHistoryFormat(Connection connection, String table) throws SQLException {
        if (hasHistoryFormat(connection, table)) return;
        try (var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN temporal_format INTEGER DEFAULT 1 NOT NULL");
        } catch (SQLException competingInitializer) {
            if (!hasHistoryFormat(connection, table)) throw competingInitializer;
        }
    }

    private List<HistorySnapshot> readTemporalHistory(Connection connection, RequestContext context, boolean link,
                                                       String type, String id, Instant recordedTime) throws SQLException {
        String table = link ? "of_link_history" : "of_object_history";
        String prefix = link ? "link" : "object";
        String sql = "SELECT * FROM " + table + " WHERE tenant_id = ? AND " + prefix + "_type = ?"
                + (recordedTime == null ? "" : " AND recorded_at <= ?")
                + (id == null ? "" : " AND " + prefix + "_id = ?") + " ORDER BY " + prefix + "_id, version";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, context.tenantId());
            statement.setString(2, type);
            int parameter = 3;
            if (recordedTime != null) statement.setTimestamp(parameter++, timestamp(recordedTime));
            if (id != null) statement.setString(parameter, id);
            try (var rows = statement.executeQuery()) {
                var result = new ArrayList<HistorySnapshot>();
                while (rows.next()) {
                    if (rows.getInt("temporal_format") != 2) {
                        throw new org.openfoundry.foundation.spi.TemporalHistoryUnavailableException(type, rows.getString(prefix + "_id"));
                    }
                    result.add(readHistory(rows, link));
                }
                return List.copyOf(result);
            }
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
            state.put("_fromType", result.getString("from_type"));
            state.put("_fromId", result.getString("from_id"));
            state.put("_toType", result.getString("to_type"));
            state.put("_toId", result.getString("to_id"));
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

    private LinkTypeDefinition requireLinkType(String type) {
        var current = binding;
        if (current == null) throw new IllegalStateException("schema has not been applied");
        return current.schema().linkTypes().stream().filter(candidate -> candidate.name().equals(type)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown link type: " + type));
    }

    private RuntimeException sqlError(String operation, SQLException exception) {
        return new IllegalStateException("JDBC operation failed: " + operation, exception);
    }

    @FunctionalInterface
    private interface SqlBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private final class JdbcTransaction implements Transaction, JdbcTransactionAccess {
        private final RequestContext context;
        private final Connection connection;
        private final String transactionId = UUID.randomUUID().toString();
        private boolean closed;
        private MutationSource source;
        private boolean sourceFrozen;
        private boolean writeLocked;
        private boolean rollbackOnly;
        private final JdbcSchemaActivation.Binding transactionBinding;
        private final OntologySchema schema;
        private final UniquePropertyIndex uniqueProperties = new UniquePropertyIndex();

        private JdbcTransaction(RequestContext context, Connection connection, JdbcSchemaActivation.Binding binding) {
            this.context = context;
            this.connection = connection;
            this.transactionBinding = binding;
            this.schema = binding.schema();
        }

        private void requireObjectType(String type) {
            if (schema == null || schema.objectTypes().stream().noneMatch(candidate -> candidate.name().equals(type))) {
                throw new IllegalArgumentException("unknown object type: " + type);
            }
        }

        private List<org.openfoundry.foundation.spi.schema.PropertyDefinition> objectProperties(String type) {
            requireObjectType(type);
            return schema.objectTypes().stream().filter(candidate -> candidate.name().equals(type)).findFirst().orElseThrow().properties();
        }

        private List<String> objectConstraints(String type) {
            requireObjectType(type);
            return schema.objectTypes().stream().filter(candidate -> candidate.name().equals(type)).findFirst().orElseThrow().constraints();
        }

        private LinkTypeDefinition requireLinkType(String type) {
            if (schema == null) throw new IllegalStateException("schema has not been applied");
            return schema.linkTypes().stream().filter(candidate -> candidate.name().equals(type)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown link type: " + type));
        }

        @Override public RequestContext context() { return context; }

        @Override
        public void mutationSource(MutationSource source) {
            assertOpen();
            if (sourceFrozen) throw new IllegalStateException("Mutation source cannot change after fact or provenance writes");
            this.source = Objects.requireNonNull(source);
        }

        @Override
        public Map<String, FieldProvenance> latestLineage(EntityKey key) {
            assertOpen();
            try { return lineage.latest(connection, context, key); }
            catch (SQLException failure) { throw sqlError("read transactional lineage", failure); }
        }

        @Override
        public void recordProvenance(EntityKey key, long expectedVersion, java.util.Set<String> fields) {
            assertOpen();
            lockWrites();
            boolean relationship = schema.linkTypes().stream().anyMatch(type -> type.name().equals(key.type()));
            var definitions = relationship ? requireLinkType(key.type()).properties() : objectProperties(key.type());
            var declared = definitions.stream().filter(field -> !field.primary()).map(org.openfoundry.foundation.spi.schema.PropertyDefinition::name).collect(java.util.stream.Collectors.toSet());
            declared.add("_entity");
            if (!declared.containsAll(fields)) throw new IllegalArgumentException("Unknown or primary provenance field");
            Map<String, Object> current;
            boolean alive;
            long actualVersion;
            if (relationship) {
                var link = findLink(key.type(), key.id());
                actualVersion = link == null ? 0 : link.version();
                alive = link != null && !link.isDeleted();
                current = link == null ? Map.of() : link.properties();
            } else {
                var object = findObject(key.type(), key.id());
                actualVersion = object == null ? 0 : object.version();
                alive = object != null && !object.isDeleted();
                current = object == null ? Map.of() : object.properties();
            }
            assertVersion(actualVersion, expectedVersion);
            if (actualVersion == 0 && !fields.equals(java.util.Set.of("_entity"))) throw new IllegalArgumentException("Only absent identity observations are allowed before creation");
            var values = new java.util.TreeMap<String, LineageValues.Value>();
            for (String field : fields) {
                boolean present = alive && (field.equals("_entity") || current.containsKey(field));
                Object value = field.equals("_entity") ? true : current.get(field);
                values.put(field, new LineageValues.Value(present, LineageValues.hash(present, value)));
            }
            if (values.isEmpty()) return;
            var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            freezeSource(now);
            try { lineage.append(connection, context, key, expectedVersion, transactionId, values, source, now); }
            catch (SQLException failure) { throw sqlError("record provenance observation", failure); }
            catch (RuntimeException | Error failure) { rollbackOnly = true; throw failure; }
        }

        @Override
        public IngestionReceipt getIngestionReceipt(String key) {
            assertOpen();
            IngestionReceipt.requireHash(key);
            try (var statement = connection.prepareStatement("SELECT * FROM of_ingestion_receipts WHERE tenant_id = ? AND receipt_key = ?")) {
                statement.setString(1, context.tenantId());
                statement.setString(2, key);
                try (var row = statement.executeQuery()) {
                    return row.next() ? new IngestionReceipt(key, row.getString("request_hash"), new EntityKey(row.getString("target_type"), row.getString("target_id")), jsonMap(row.getString("result_json"))) : null;
                }
            } catch (SQLException failure) { throw sqlError("read ingestion receipt", failure); }
        }

        @Override
        public void putIngestionReceipt(IngestionReceipt receipt) {
            assertOpen();
            lockWrites();
            try (var statement = connection.prepareStatement("INSERT INTO of_ingestion_receipts (tenant_id, receipt_key, request_hash, target_type, target_id, actor_id, result_json) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, context.tenantId());
                statement.setString(2, receipt.key());
                statement.setString(3, receipt.requestHash());
                statement.setString(4, receipt.target().type());
                statement.setString(5, receipt.target().id());
                statement.setString(6, context.actorId());
                statement.setString(7, json(receipt.result()));
                statement.executeUpdate();
            } catch (SQLException failure) { throw sqlError("write ingestion receipt", failure); }
        }

        @Override
        public IngestionCheckpoint getIngestionCheckpoint(String key) {
            assertOpen();
            IngestionReceipt.requireHash(key);
            try { return readIngestionCheckpoint(connection, context, key); }
            catch (SQLException failure) { throw sqlError("read transactional ingestion checkpoint", failure); }
        }

        @Override
        public void putIngestionCheckpoint(IngestionCheckpoint checkpoint, long expectedVersion) {
            assertOpen();
            lockWrites();
            checkpoint.requireSuccessor(getIngestionCheckpoint(checkpoint.key()), expectedVersion);
            String sql = expectedVersion == 0
                    ? "INSERT INTO of_ingestion_checkpoints (version, source_sequence, token_json, source_system, configuration_hash, tenant_id, checkpoint_key) VALUES (?, ?, ?, ?, ?, ?, ?)"
                    : "UPDATE of_ingestion_checkpoints SET version = ?, source_sequence = ?, token_json = ?, source_system = ?, configuration_hash = ? WHERE tenant_id = ? AND checkpoint_key = ? AND version = ?";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setLong(1, checkpoint.version());
                statement.setLong(2, checkpoint.sequence());
                statement.setString(3, json(Map.of("value", checkpoint.token())));
                statement.setString(4, checkpoint.sourceSystem());
                statement.setString(5, checkpoint.configuration());
                statement.setString(6, context.tenantId());
                statement.setString(7, checkpoint.key());
                if (expectedVersion != 0) statement.setLong(8, expectedVersion);
                if (statement.executeUpdate() != 1) throw new org.openfoundry.foundation.spi.TransactionConflictException("Ingestion checkpoint version changed");
            } catch (SQLException failure) { throw sqlError("write ingestion checkpoint", failure); }
        }

        private void freezeSource(Instant now) {
            if (source == null) source = MutationSource.direct(transactionId, now);
            sourceFrozen = true;
        }

        @Override public DataSource transactionDataSource() { return dataSource; }
        @Override public Connection transactionConnection() { assertOpen(); return connection; }

        @Override
        public String transactionId() {
            return transactionId;
        }

        @Override
        public ObjectRecord createObject(String type, String id, Map<String, Object> properties) {
            return createObject(type, id, properties, null);
        }

        @Override
        public ObjectRecord createObject(String type, String id, Map<String, Object> properties, Instant effectiveAt) {
            assertOpen();
            lockWrites();
            requireObjectType(type);
            if (findObject(type, id) != null) throw new IllegalStateException("object already exists: " + type + ":" + id);
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            properties = propertyValidator.validate(schema, objectProperties(type), objectConstraints(type), id, properties, null, context, now);
            uniqueProperties.check(schema, "object", type, objectProperties(type), id, properties, () -> currentProperties(false, type));
            Instant effective = effectiveTime(false, type, id, effectiveAt, now);
            String sql = "INSERT INTO of_objects (tenant_id, object_type, object_id, version, created_at, updated_at, "
                    + "deleted_at, last_transaction_id, last_action_id, properties_json) VALUES (?, ?, ?, 1, ?, ?, NULL, ?, NULL, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, context.tenantId()); statement.setString(2, type); statement.setString(3, id);
                statement.setTimestamp(4, timestamp(now)); statement.setTimestamp(5, timestamp(now));
                statement.setString(6, transactionId); statement.setString(7, json(properties)); statement.executeUpdate();
                insertObjectHistory(type, id, 1, EntityOperation.CREATED, effective, null, now, properties, null);
                uniqueProperties.applied(schema, "object", type, objectProperties(type), id, null, properties);
                return findObject(type, id);
            } catch (SQLException exception) { throw sqlError("create object", exception); }
        }

        @Override
        public ObjectRecord updateObject(String type, String id, Map<String, Object> properties, long expectedVersion) {
            return updateObject(type, id, properties, expectedVersion, null);
        }

        @Override
        public ObjectRecord updateObject(String type, String id, Map<String, Object> properties, long expectedVersion, Instant effectiveAt) {
            return writeObjectProperties(type, id, properties, expectedVersion, effectiveAt, false, false);
        }

        @Override
        public ObjectRecord restoreObjectProperties(String type, String id, Map<String, Object> properties, long expectedVersion) {
            return writeObjectProperties(type, id, properties, expectedVersion, null, true, false);
        }

        @Override
        public ObjectRecord restoreObject(String type, String id, Map<String, Object> properties, long expectedVersion) {
            return writeObjectProperties(type, id, properties, expectedVersion, null, false, true);
        }

        private ObjectRecord writeObjectProperties(String type, String id, Map<String, Object> properties,
                                                    long expectedVersion, Instant effectiveAt, boolean replace, boolean restoring) {
            assertOpen();
            lockWrites();
            ObjectRecord existing = requireObject(findObject(type, id), type, id);
            assertVersion(existing.version(), expectedVersion);
            if (existing.isDeleted() != restoring) throw new IllegalStateException(restoring ? "Entity is already active" : "Entity is already deleted");
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Map<String, Object> merged = replace
                    ? propertyValidator.restore(schema, objectProperties(type), objectConstraints(type), id, properties, existing.properties(), context, now)
                    : propertyValidator.validate(schema, objectProperties(type), objectConstraints(type), id, properties, existing.properties(), context, now);
            uniqueProperties.check(schema, "object", type, objectProperties(type), id, merged, () -> currentProperties(false, type));
            Instant effective = effectiveTime(false, type, id, effectiveAt, now);
            long version = existing.version() + 1;
            String sql = "UPDATE of_objects SET version = ?, updated_at = ?, last_transaction_id = ?, properties_json = ?"
                    + (restoring ? ", deleted_at = NULL" : "") + " WHERE tenant_id = ? AND object_type = ? AND object_id = ? AND version = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, version); statement.setTimestamp(2, timestamp(now)); statement.setString(3, transactionId);
                statement.setString(4, json(merged)); statement.setString(5, context.tenantId()); statement.setString(6, type);
                statement.setString(7, id); statement.setLong(8, expectedVersion);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("version conflict during object update");
                insertObjectHistory(type, id, version, restoring ? EntityOperation.RESTORED : EntityOperation.UPDATED, effective, null, now, merged, existing.properties());
                uniqueProperties.applied(schema, "object", type, objectProperties(type), id, restoring ? null : existing.properties(), merged);
                return findObject(type, id);
            } catch (SQLException exception) { throw sqlError("update object", exception); }
        }

        @Override
        public void deleteObject(String type, String id, long expectedVersion) {
            deleteObject(type, id, expectedVersion, null);
        }

        @Override
        public void deleteObject(String type, String id, long expectedVersion, Instant effectiveAt) {
            assertOpen();
            lockWrites();
            ObjectRecord existing = requireObject(findObject(type, id), type, id);
            assertVersion(existing.version(), expectedVersion);
            if (existing.isDeleted()) throw new IllegalStateException("Entity is already deleted");
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Instant effective = effectiveTime(false, type, id, effectiveAt, now);
            long version = existing.version() + 1;
            String sql = "UPDATE of_objects SET version = ?, updated_at = ?, deleted_at = ?, last_transaction_id = ? "
                    + "WHERE tenant_id = ? AND object_type = ? AND object_id = ? AND version = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, version); statement.setTimestamp(2, timestamp(now)); statement.setTimestamp(3, timestamp(effective));
                statement.setString(4, transactionId); statement.setString(5, context.tenantId()); statement.setString(6, type);
                statement.setString(7, id); statement.setLong(8, expectedVersion);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("version conflict during object delete");
                insertObjectHistory(type, id, version, EntityOperation.DELETED, effective, null, now, existing.properties(), existing.properties());
                uniqueProperties.applied(schema, "object", type, objectProperties(type), id, existing.properties(), null);
            } catch (SQLException exception) { throw sqlError("delete object", exception); }
        }

        @Override
        public LinkRecord createLink(String type, String id, EntityKey from, EntityKey to, Map<String, Object> properties) {
            return createLink(type, id, from, to, properties, null);
        }

        @Override
        public LinkRecord createLink(String type, String id, EntityKey from, EntityKey to, Map<String, Object> properties, Instant effectiveAt) {
            assertOpen();
            lockWrites();
            LinkTypeDefinition definition = requireLinkType(type);
            requireActiveObject(from); requireActiveObject(to);
            if (findLink(type, id) != null) throw new IllegalStateException("link already exists: " + type + ":" + id);
            enforceCardinality(definition, from, to);
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            properties = propertyValidator.validate(schema, requireLinkType(type).properties(), requireLinkType(type).constraints(), id, properties, null, context, now);
            uniqueProperties.check(schema, "link", type, requireLinkType(type).properties(), id, properties, () -> currentProperties(true, type));
            Instant effective = effectiveTime(true, type, id, effectiveAt, now);
            if (effectiveAt != null) {
                requireHistoricalEndpoint(from, effective, now);
                requireHistoricalEndpoint(to, effective, now);
                enforceHistoricalCardinality(definition, from, to, effective, now);
            }
            String sql = "INSERT INTO of_links (tenant_id, link_type, link_id, from_type, from_id, to_type, to_id, version, "
                    + "created_at, updated_at, deleted_at, valid_from, valid_to, last_transaction_id, last_action_id, properties_json) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, NULL, ?, NULL, ?, NULL, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, context.tenantId()); statement.setString(2, type); statement.setString(3, id);
                statement.setString(4, from.type()); statement.setString(5, from.id()); statement.setString(6, to.type()); statement.setString(7, to.id());
                statement.setTimestamp(8, timestamp(now)); statement.setTimestamp(9, timestamp(now)); statement.setTimestamp(10, timestamp(effective));
                statement.setString(11, transactionId); statement.setString(12, json(properties)); statement.executeUpdate();
                insertLinkHistory(type, id, from, to, 1, EntityOperation.CREATED, effective, null, now, properties, null);
                uniqueProperties.applied(schema, "link", type, requireLinkType(type).properties(), id, null, properties);
                return findLink(type, id);
            } catch (SQLException exception) { throw sqlError("create link", exception); }
        }

        @Override
        public LinkRecord updateLink(String type, String id, Map<String, Object> properties, long expectedVersion) {
            return updateLink(type, id, properties, expectedVersion, null);
        }

        @Override
        public LinkRecord updateLink(String type, String id, Map<String, Object> properties, long expectedVersion, Instant effectiveAt) {
            assertOpen();
            lockWrites();
            LinkRecord existing = requireLink(findLink(type, id), type, id);
            assertVersion(existing.version(), expectedVersion);
            if (existing.isDeleted()) throw new IllegalStateException("Entity is already deleted");
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Map<String, Object> merged = propertyValidator.validate(schema, requireLinkType(type).properties(), requireLinkType(type).constraints(), id, properties, existing.properties(), context, now);
            uniqueProperties.check(schema, "link", type, requireLinkType(type).properties(), id, merged, () -> currentProperties(true, type));
            Instant effective = effectiveTime(true, type, id, effectiveAt, now);
            long version = existing.version() + 1;
            String sql = "UPDATE of_links SET version = ?, updated_at = ?, last_transaction_id = ?, properties_json = ? "
                    + "WHERE tenant_id = ? AND link_type = ? AND link_id = ? AND version = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, version); statement.setTimestamp(2, timestamp(now)); statement.setString(3, transactionId);
                statement.setString(4, json(merged)); statement.setString(5, context.tenantId()); statement.setString(6, type); statement.setString(7, id); statement.setLong(8, expectedVersion);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("version conflict during link update");
                insertLinkHistory(type, id, existing.from(), existing.to(), version, EntityOperation.UPDATED,
                        effective, null, now, merged, existing.properties());
                uniqueProperties.applied(schema, "link", type, requireLinkType(type).properties(), id, existing.properties(), merged);
                return findLink(type, id);
            } catch (SQLException exception) { throw sqlError("update link", exception); }
        }

        @Override
        public void deleteLink(String type, String id, long expectedVersion) {
            deleteLink(type, id, expectedVersion, null);
        }

        @Override
        public void deleteLink(String type, String id, long expectedVersion, Instant effectiveAt) {
            assertOpen();
            lockWrites();
            LinkRecord existing = requireLink(findLink(type, id), type, id);
            assertVersion(existing.version(), expectedVersion);
            if (existing.isDeleted()) throw new IllegalStateException("Entity is already deleted"); Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Instant effective = effectiveTime(true, type, id, effectiveAt, now);
            long version = existing.version() + 1;
            String sql = "UPDATE of_links SET version = ?, updated_at = ?, deleted_at = ?, valid_to = ?, last_transaction_id = ? "
                    + "WHERE tenant_id = ? AND link_type = ? AND link_id = ? AND version = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, version); statement.setTimestamp(2, timestamp(now)); statement.setTimestamp(3, timestamp(effective)); statement.setTimestamp(4, timestamp(effective)); statement.setString(5, transactionId);
                statement.setString(6, context.tenantId()); statement.setString(7, type); statement.setString(8, id); statement.setLong(9, expectedVersion);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("version conflict during link delete");
                insertLinkHistory(type, id, existing.from(), existing.to(), version, EntityOperation.DELETED,
                        effective, null, now, existing.properties(), existing.properties());
                uniqueProperties.applied(schema, "link", type, requireLinkType(type).properties(), id, existing.properties(), null);
            } catch (SQLException exception) { throw sqlError("delete link", exception); }
        }

        @Override
        public LinkRecord restoreLink(String type, String id, long expectedVersion) {
            acquireWrite();
            var definition = requireLinkType(type);
            var existing = requireLink(findLink(type, id), type, id);
            assertVersion(existing.version(), expectedVersion);
            if (!existing.isDeleted()) throw new IllegalStateException("Relationship is not terminated");
            if (!definition.fromType().equals(existing.from().type()) || !definition.toType().equals(existing.to().type())) {
                throw new IllegalArgumentException("Relationship endpoints no longer match the active schema");
            }
            requireActiveObject(existing.from());
            requireActiveObject(existing.to());
            enforceCardinality(definition, existing.from(), existing.to());
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            Instant effective = effectiveTime(true, type, id, null, now);
            var properties = propertyValidator.validate(schema, definition.properties(), definition.constraints(), id,
                    Map.of(), existing.properties(), context, now);
            uniqueProperties.check(schema, "link", type, definition.properties(), id, properties, () -> currentProperties(true, type));
            String sql = "UPDATE of_links SET version = ?, updated_at = ?, deleted_at = NULL, valid_from = ?, valid_to = NULL,"
                    + " last_transaction_id = ?, properties_json = ? WHERE tenant_id = ? AND link_type = ? AND link_id = ? AND version = ?";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setLong(1, existing.version() + 1);
                statement.setTimestamp(2, timestamp(now));
                statement.setTimestamp(3, timestamp(effective));
                statement.setString(4, transactionId);
                statement.setString(5, json(properties));
                statement.setString(6, context.tenantId());
                statement.setString(7, type);
                statement.setString(8, id);
                statement.setLong(9, expectedVersion);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("version conflict during relationship restore");
                insertLinkHistory(type, id, existing.from(), existing.to(), existing.version() + 1, EntityOperation.RESTORED,
                        effective, null, now, properties, null);
                uniqueProperties.applied(schema, "link", type, definition.properties(), id, null, properties);
                return findLink(type, id);
            } catch (SQLException failure) {
                throw sqlError("restore relationship", failure);
            }
        }

        @Override
        public void acquireWrite() {
            assertOpen();
            lockWrites();
        }

        @Override
        public ObjectRecord getObject(String type, String id) {
            assertOpen();
            return findObject(type, id);
        }

        @Override
        public LinkRecord getLink(String type, String id) {
            assertOpen();
            return findLink(type, id);
        }

        @Override
        public List<LinkRecord> findLinks(String type, EntityKey from, EntityKey to) {
            return findLinks(type, from, to, false);
        }

        @Override
        public List<LinkRecord> findLinks(String type, EntityKey from, EntityKey to, boolean includeDeleted) {
            assertOpen();
            requireLinkType(type);
            if (from == null && to == null) throw new IllegalArgumentException("A relationship endpoint is required");
            String sql = linkSelect() + " WHERE tenant_id = ? AND link_type = ?" + (includeDeleted ? "" : " AND deleted_at IS NULL")
                    + (from == null ? "" : " AND from_type = ? AND from_id = ?")
                    + (to == null ? "" : " AND to_type = ? AND to_id = ?") + " ORDER BY link_id";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, context.tenantId());
                statement.setString(2, type);
                int index = 3;
                if (from != null) {
                    statement.setString(index++, from.type());
                    statement.setString(index++, from.id());
                }
                if (to != null) {
                    statement.setString(index++, to.type());
                    statement.setString(index, to.id());
                }
                try (ResultSet result = statement.executeQuery()) {
                    var rows = new ArrayList<LinkRecord>();
                    while (result.next()) rows.add(readLink(result));
                    return List.copyOf(rows);
                }
            } catch (SQLException failure) {
                rollbackOnly = true;
                throw sqlError("select links in transaction", failure);
            }
        }

        @Override
        public org.openfoundry.foundation.spi.CommandReceipt getCommandReceipt(String key) {
            acquireWrite();
            try (var statement = connection.prepareStatement("SELECT actor_id, action_name, request_hash, result_json FROM of_command_receipts WHERE tenant_id = ? AND receipt_key = ?")) {
                statement.setString(1, context.tenantId());
                statement.setString(2, key);
                try (var row = statement.executeQuery()) {
                    if (!row.next()) return null;
                    if (!row.getString(1).equals(context.actorId())) throw new SecurityException("Command receipt belongs to another actor");
                    return new org.openfoundry.foundation.spi.CommandReceipt(key, row.getString(1), row.getString(2), row.getString(3), jsonMap(row.getString(4)));
                }
            } catch (SQLException failure) { throw sqlError("read command receipt", failure); }
        }

        @Override
        public void putCommandReceipt(org.openfoundry.foundation.spi.CommandReceipt receipt) {
            acquireWrite();
            if (!receipt.actorId().equals(context.actorId())) throw new SecurityException("Command receipt actor mismatch");
            try (var statement = connection.prepareStatement("INSERT INTO of_command_receipts (tenant_id, receipt_key, actor_id, action_name, request_hash, result_json) VALUES (?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, context.tenantId());
                statement.setString(2, receipt.key());
                statement.setString(3, receipt.actorId());
                statement.setString(4, receipt.action());
                statement.setString(5, receipt.requestHash());
                statement.setString(6, json(receipt.result()));
                statement.executeUpdate();
            } catch (SQLException failure) { throw sqlError("write command receipt", failure); }
        }

        @Override
        public List<LinkRecord> connectedLinks(EntityKey endpoint) {
            assertOpen();
            String sql = linkSelect() + " WHERE tenant_id = ? AND deleted_at IS NULL"
                    + " AND ((from_type = ? AND from_id = ?) OR (to_type = ? AND to_id = ?)) ORDER BY link_type, link_id";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, context.tenantId());
                statement.setString(2, endpoint.type());
                statement.setString(3, endpoint.id());
                statement.setString(4, endpoint.type());
                statement.setString(5, endpoint.id());
                try (var rows = statement.executeQuery()) {
                    var result = new ArrayList<LinkRecord>();
                    while (rows.next()) result.add(readLink(rows));
                    return List.copyOf(result);
                }
            } catch (SQLException failure) {
                throw sqlError("read incident relationships", failure);
            }
        }

        @Override
        public org.openfoundry.foundation.spi.ActionExecution getActionExecution(String id) {
            assertOpen();
            try (var statement = connection.prepareStatement("SELECT * FROM of_action_executions WHERE tenant_id = ? AND execution_id = ?")) {
                statement.setString(1, context.tenantId());
                statement.setString(2, id);
                try (var row = statement.executeQuery()) {
                    if (!row.next()) return null;
                    var execution = readActionExecution(row);
                    if (!Objects.equals(execution.actorId(), context.actorId())) throw new SecurityException("Action execution belongs to another actor");
                    return execution;
                }
            } catch (SQLException failure) {
                throw sqlError("read action continuation", failure);
            }
        }

        @Override
        public void putActionExecution(org.openfoundry.foundation.spi.ActionExecution execution, long expectedVersion) {
            acquireWrite();
            if (!Objects.equals(execution.actorId(), context.actorId()) || execution.version() != expectedVersion + 1) {
                throw new IllegalArgumentException("Invalid execution identity or version");
            }
            var previous = getActionExecution(execution.id());
            if ((previous == null ? 0 : previous.version()) != expectedVersion) throw new IllegalStateException("Action execution version conflict");
            if (previous != null && !previous.action().equals(execution.action())) throw new IllegalArgumentException("Action identity cannot change");
            String sql = previous == null
                    ? "INSERT INTO of_action_executions (actor_id, action_name, version, status, available_at, state_json, tenant_id, execution_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                    : "UPDATE of_action_executions SET actor_id = ?, action_name = ?, version = ?, status = ?, available_at = ?, state_json = ? WHERE tenant_id = ? AND execution_id = ? AND version = ?";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, execution.actorId());
                statement.setString(2, execution.action());
                statement.setLong(3, execution.version());
                statement.setString(4, execution.status());
                statement.setTimestamp(5, execution.availableAt() == null ? null : timestamp(execution.availableAt()));
                statement.setString(6, json(execution.state()));
                statement.setString(7, context.tenantId());
                statement.setString(8, execution.id());
                if (previous != null) statement.setLong(9, expectedVersion);
                if (statement.executeUpdate() != 1) throw new IllegalStateException("Action execution version conflict");
            } catch (SQLException failure) {
                throw sqlError("write action continuation", failure);
            }
        }

        @Override
        public void appendAudit(AuditEntry audit) {
            assertOpen();
            lockWrites();
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
            lockWrites();
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
            lockWrites();
            try {
                activation.requireBinding(connection, transactionBinding, true);
                connection.commit();
                closed = true;
                connection.close();
            } catch (SQLException exception) {
                rollbackOnly = true;
                throw sqlError("commit transaction", exception);
            } catch (RuntimeException failure) {
                rollbackOnly = true;
                throw failure;
            }
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

        private void lockWrites() {
            try {
                activation.requireBinding(connection, transactionBinding, false);
                if (writeLocked) return;
                try (var statement = connection.prepareStatement("SELECT tenant_id FROM of_write_guards WHERE tenant_id = ? FOR UPDATE")) {
                    statement.setString(1, context.tenantId());
                    try (var row = statement.executeQuery()) {
                        if (!row.next()) throw new IllegalStateException("Tenant write guard missing");
                    }
                }
                activation.requireBinding(connection, transactionBinding, false);
                writeLocked = true;
            } catch (SQLException failure) {
                rollbackOnly = true;
                throw sqlError("check schema and lock tenant writes", failure);
            } catch (RuntimeException failure) {
                rollbackOnly = true;
                throw failure;
            }
        }

        private Map<String, Map<String, Object>> currentProperties(boolean link, String type) {
            String prefix = link ? "link" : "object";
            String sql = "SELECT " + prefix + "_id, properties_json FROM of_" + (link ? "links" : "objects")
                    + " WHERE tenant_id = ? AND " + prefix + "_type = ? AND deleted_at IS NULL";
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, context.tenantId());
                statement.setString(2, type);
                try (var rows = statement.executeQuery()) {
                    var values = new HashMap<String, Map<String, Object>>();
                    while (rows.next()) values.put(rows.getString(1), jsonMap(rows.getString(2)));
                    return values;
                }
            } catch (SQLException failure) { throw sqlError("read unique property scope", failure); }
        }

        private Instant effectiveTime(boolean link, String type, String id, Instant requested, Instant recorded) {
            if (requested != null) {
                try {
                    return TemporalHistory.effectiveAt(requested, recorded, readTemporalHistory(connection, context, link, type, id, null));
                } catch (SQLException failure) { throw sqlError("validate effective time", failure); }
            }
            String prefix = link ? "link" : "object";
            String sql = "SELECT * FROM of_" + prefix + "_history WHERE tenant_id = ? AND " + prefix + "_type = ? AND " + prefix + "_id = ? ORDER BY version DESC" + dialect.paginationClause();
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, context.tenantId()); statement.setString(2, type); statement.setString(3, id);
                statement.setInt(4, 1); statement.setInt(5, 0);
                try (var rows = statement.executeQuery()) {
                    return TemporalHistory.effectiveAt(null, recorded, rows.next() ? List.of(readHistory(rows, link)) : List.of());
                }
            } catch (SQLException failure) { throw sqlError("validate recorded time", failure); }
        }

        private void requireHistoricalEndpoint(EntityKey key, Instant effective, Instant recorded) {
            try {
                var snapshot = TemporalHistory.at(readTemporalHistory(connection, context, false, key.type(), key.id(), recorded), effective, recorded);
                if (!TemporalHistory.active(snapshot)) throw new IllegalArgumentException("Link endpoint did not exist at effective time: " + key);
            } catch (SQLException failure) { throw sqlError("validate historical endpoint", failure); }
        }

        private void enforceHistoricalCardinality(LinkTypeDefinition definition, EntityKey from, EntityKey to, Instant effective, Instant recorded) {
            if (definition.cardinality() == Cardinality.MANY_TO_MANY) return;
            try {
                var histories = TemporalHistory.group(readTemporalHistory(connection, context, true, definition.name(), null, recorded));
                for (var history : histories.values()) {
                    var first = history.getFirst();
                    boolean conflicting = switch (definition.cardinality()) {
                        case ONE_TO_ONE -> TemporalHistory.from(first).equals(from) || TemporalHistory.to(first).equals(to);
                        case ONE_TO_MANY -> TemporalHistory.to(first).equals(to);
                        case MANY_TO_ONE -> TemporalHistory.from(first).equals(from);
                        default -> false;
                    };
                    if (!conflicting) continue;
                    var boundaries = new ArrayList<Instant>();
                    boundaries.add(effective);
                    history.stream().map(HistorySnapshot::validFrom).filter(time -> !time.isBefore(effective)).forEach(boundaries::add);
                    if (boundaries.stream().anyMatch(time -> TemporalHistory.active(TemporalHistory.at(history, time, recorded)))) {
                        throw new IllegalStateException("Historical link cardinality overlap");
                    }
                }
            } catch (SQLException failure) { throw sqlError("historical link cardinality", failure); }
        }

        private void requireActiveObject(EntityKey key) {
            ObjectRecord object = findObject(key.type(), key.id());
            if (object == null || object.isDeleted()) throw new IllegalStateException("link endpoint is not active: " + key);
        }

        private void enforceCardinality(LinkTypeDefinition definition, EntityKey from, EntityKey to) {
            if (!definition.fromType().equals(from.type()) || !definition.toType().equals(to.type())) {
                throw new IllegalArgumentException("Link endpoint types do not match schema");
            }
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
                                         Instant validFrom, Instant validTo, Instant recordedAt, Map<String, Object> state, Map<String, Object> previous) throws SQLException {
            String sql = "INSERT INTO of_object_history (tenant_id, object_type, object_id, version, operation, valid_from, valid_to, recorded_at, transaction_id, action_id, actor_id, source_system, state_json, temporal_format) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, ?, 2)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, context.tenantId()); statement.setString(2, type); statement.setString(3, id); statement.setLong(4, version); statement.setString(5, operation.name());
                statement.setTimestamp(6, timestamp(validFrom)); setNullableTimestamp(statement, 7, validTo); statement.setTimestamp(8, timestamp(recordedAt)); statement.setString(9, transactionId); statement.setString(10, context.actorId()); statement.setString(11, json(state)); statement.executeUpdate();
            }
            recordFactSource(false, type, id, version, operation, previous, state, recordedAt);
        }

        private void insertLinkHistory(String type, String id, EntityKey from, EntityKey to, long version, EntityOperation operation,
                                       Instant validFrom, Instant validTo, Instant recordedAt, Map<String, Object> state, Map<String, Object> previous) throws SQLException {
            String sql = "INSERT INTO of_link_history (tenant_id, link_type, link_id, from_type, from_id, to_type, to_id, version, operation, valid_from, valid_to, recorded_at, transaction_id, action_id, actor_id, source_system, state_json, temporal_format) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, NULL, ?, 2)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, context.tenantId()); statement.setString(2, type); statement.setString(3, id); statement.setString(4, from.type()); statement.setString(5, from.id()); statement.setString(6, to.type()); statement.setString(7, to.id()); statement.setLong(8, version); statement.setString(9, operation.name());
                statement.setTimestamp(10, timestamp(validFrom)); setNullableTimestamp(statement, 11, validTo); statement.setTimestamp(12, timestamp(recordedAt)); statement.setString(13, transactionId); statement.setString(14, context.actorId()); statement.setString(15, json(state)); statement.executeUpdate();
            }
            recordFactSource(true, type, id, version, operation, previous, state, recordedAt);
        }

        private void recordFactSource(boolean relationship, String type, String id, long version, EntityOperation operation,
                                      Map<String, Object> previous, Map<String, Object> current, Instant now) throws SQLException {
            try {
                freezeSource(now);
                String prefix = relationship ? "link" : "object";
                try (var statement = connection.prepareStatement("UPDATE of_" + prefix + "_history SET action_id = ?, source_system = ?"
                        + " WHERE tenant_id = ? AND " + prefix + "_type = ? AND " + prefix + "_id = ? AND version = ?")) {
                    statement.setString(1, source.actionId());
                    statement.setString(2, source.sourceSystem());
                    statement.setString(3, context.tenantId());
                    statement.setString(4, type);
                    statement.setString(5, id);
                    statement.setLong(6, version);
                    if (statement.executeUpdate() != 1) throw new SQLException("Fact source history row is missing");
                }
                try (var statement = connection.prepareStatement("UPDATE of_" + (relationship ? "links" : "objects") + " SET last_action_id = ?"
                        + " WHERE tenant_id = ? AND " + prefix + "_type = ? AND " + prefix + "_id = ? AND version = ?")) {
                    statement.setString(1, source.actionId());
                    statement.setString(2, context.tenantId());
                    statement.setString(3, type);
                    statement.setString(4, id);
                    statement.setLong(5, version);
                    if (statement.executeUpdate() != 1) throw new SQLException("Fact source current row is missing");
                }
                var definitions = relationship ? requireLinkType(type).properties() : objectProperties(type);
                lineage.append(connection, context, new EntityKey(type, id), version, transactionId,
                        LineageValues.changes(definitions, previous, current, operation), source, now);
            } catch (RuntimeException | Error failure) {
                rollbackOnly = true;
                throw failure;
            }
        }

        private static void setNullableTimestamp(PreparedStatement statement, int index, Instant value) throws SQLException {
            if (value == null) statement.setTimestamp(index, null); else statement.setTimestamp(index, timestamp(value));
        }

        private RuntimeException sqlError(String operation, SQLException failure) {
            rollbackOnly = true;
            return JdbcStorageProvider.this.sqlError(operation, failure);
        }

        private void assertOpen() {
            if (closed) throw new IllegalStateException("transaction is closed");
            if (rollbackOnly) throw new IllegalStateException("transaction requires rollback after a database failure");
        }
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
