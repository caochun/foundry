package org.openfoundry.foundation.storage.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.schema.*;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.SchemaVersionMismatchException;
import org.openfoundry.foundation.spi.schema.*;
import org.openfoundry.foundation.validation.PropertyValidator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.*;

/** Database-wide activation is separate from registration. Activation freezes commits, not in-flight mutations. */
final class JdbcSchemaActivation {
    static final String REGISTRY_KEY = "storage";
    private final DataSource data;
    private final DatabaseDialect dialect;
    private final Clock clock;
    private final JdbcSchemaRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PropertyValidator validator = new PropertyValidator();
    private boolean initialized;

    JdbcSchemaActivation(DataSource data, DatabaseDialect dialect, Clock clock) {
        this.data = data;
        this.dialect = dialect;
        this.clock = clock;
        this.registry = new JdbcSchemaRegistry(data, dialect, REGISTRY_KEY, clock);
    }

    Binding apply(RequestContext context, OntologySchema candidate, MigrationPlan plan, Integer expectedActive, boolean attachOnly) {
        new SchemaCompiler().compile(candidate);
        if (expectedActive != null && expectedActive < 0) throw new IllegalArgumentException("Negative expected active schema version");
        initialize();
        String fingerprint = SchemaFingerprint.of(candidate);
        try (var connection = data.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                var head = lockHead(connection);
                if (expectedActive != null && expectedActive != head.version()) throw new SchemaVersionConflictException(expectedActive, head.version());
                var versions = registry.historyInTransaction(connection);
                SchemaVersion previous = null;
                if (head.version() == 0) {
                    try (var check = connection.createStatement(); var row = check.executeQuery("SELECT activation_id FROM of_schema_activations")) {
                        if (row.next()) throw new IllegalStateException("Empty activation head has existing activation history");
                    }
                }
                if (head.version() > 0) {
                    previous = versions.stream().filter(version -> version.version() == head.version()).findFirst()
                            .orElseThrow(() -> new IllegalStateException("Active schema version is missing from the registry"));
                    verifyActivation(connection, head, previous);
                    if (fingerprint.equals(head.fingerprint())) {
                        connection.commit();
                        return new Binding(previous, head.id(), fingerprint);
                    }
                    if (attachOnly) throw new SchemaDriftException();
                    if (new SchemaDiffer().diff(previous.schema(), candidate).classification() == MigrationClass.BREAKING
                            && (plan == null || !plan.approved())) {
                        throw new SchemaValidationException(List.of("breaking activation requires an approved migration plan"));
                    }
                }
                validateData(connection, candidate);
                var registered = registry.applyInTransaction(connection, candidate, plan);
                String id = UUID.randomUUID().toString();
                try (var insert = connection.prepareStatement("""
                        INSERT INTO of_schema_activations
                        (activation_id, schema_version, previous_version, fingerprint, activated_at, actor_id, request_tenant, migration_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, id);
                    insert.setInt(2, registered.version());
                    insert.setInt(3, head.version());
                    insert.setString(4, fingerprint);
                    insert.setString(5, clock.instant().toString());
                    insert.setString(6, context.actorId());
                    insert.setString(7, context.tenantId());
                    insert.setString(8, plan == null ? null : json(plan));
                    insert.executeUpdate();
                }
                try (var update = connection.prepareStatement("UPDATE of_active_schema SET schema_version = ?, activation_id = ?, fingerprint = ? WHERE singleton_id = 1")) {
                    update.setInt(1, registered.version());
                    update.setString(2, id);
                    update.setString(3, fingerprint);
                    if (update.executeUpdate() != 1) throw new IllegalStateException("Active schema head missing");
                }
                connection.commit();
                return new Binding(registered, id, fingerprint);
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) { throw new IllegalStateException("Schema activation failed", failure); }
    }

    void requireBinding(Connection connection, Binding binding, boolean commitFence) throws SQLException {
        var head = readHead(connection, commitFence);
        if (binding == null || head.version() != binding.version().version()
                || !Objects.equals(head.id(), binding.id()) || !Objects.equals(head.fingerprint(), binding.fingerprint())) {
            throw new SchemaVersionMismatchException();
        }
    }

    private void verifyActivation(Connection connection, Head head, SchemaVersion version) throws SQLException {
        if (!SchemaFingerprint.of(version.schema()).equals(head.fingerprint())) throw new IllegalStateException("Active schema fingerprint mismatch");
        try (var statement = connection.prepareStatement("SELECT schema_version, fingerprint FROM of_schema_activations WHERE activation_id = ?")) {
            statement.setString(1, head.id());
            try (var row = statement.executeQuery()) {
                if (!row.next() || row.getInt(1) != head.version() || !head.fingerprint().equals(row.getString(2))) {
                    throw new IllegalStateException("Schema activation evidence is missing or inconsistent");
                }
            }
        }
    }

    private Head lockHead(Connection connection) throws SQLException {
        return readHead(connection, true);
    }

    private Head readHead(Connection connection, boolean lock) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT schema_version, activation_id, fingerprint FROM of_active_schema WHERE singleton_id = 1" + (lock ? " FOR UPDATE" : ""));
             var row = statement.executeQuery()) {
            if (!row.next()) throw new IllegalStateException("Active schema head missing");
            int version = row.getInt(1);
            if (version < 0 || version == 0 && (row.getString(2) != null || row.getString(3) != null)) throw new IllegalStateException("Invalid active schema head");
            return new Head(version, row.getString(2), row.getString(3));
        }
    }

    private void validateData(Connection connection, OntologySchema schema) throws SQLException {
        var objects = new HashMap<String, ObjectTypeDefinition>();
        schema.objectTypes().forEach(type -> objects.put(type.name(), type));
        var links = new HashMap<String, LinkTypeDefinition>();
        schema.linkTypes().forEach(type -> links.put(type.name(), type));
        var endpoints = new HashSet<List<String>>();
        var unique = new HashSet<List<String>>();
        try (var statement = connection.prepareStatement("SELECT tenant_id, object_type, object_id, properties_json, deleted_at FROM of_objects");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                String tenant = rows.getString(1), type = rows.getString(2), id = rows.getString(3);
                endpoints.add(List.of(tenant, type, id));
                if (rows.getObject(5) != null) continue;
                var definition = objects.get(type);
                if (definition == null) throw new IllegalArgumentException("Candidate schema omits an active object type: " + type);
                var values = validator.validateExisting(schema, definition.properties(), definition.constraints(), id, properties(rows.getString(4)));
                checkUnique(schema, "object", tenant, type, definition.properties(), values, unique);
            }
        }
        var cardinality = new HashSet<List<String>>();
        try (var statement = connection.prepareStatement("SELECT tenant_id, link_type, link_id, from_type, from_id, to_type, to_id, properties_json FROM of_links WHERE deleted_at IS NULL AND valid_to IS NULL");
             var rows = statement.executeQuery()) {
            while (rows.next()) {
                String tenant = rows.getString(1), type = rows.getString(2);
                var definition = links.get(type);
                if (definition == null) throw new IllegalArgumentException("Candidate schema omits an active relationship type: " + type);
                if (!definition.fromType().equals(rows.getString(4)) || !definition.toType().equals(rows.getString(6))
                        || !endpoints.contains(List.of(tenant, rows.getString(4), rows.getString(5)))
                        || !endpoints.contains(List.of(tenant, rows.getString(6), rows.getString(7)))) {
                    throw new IllegalArgumentException("Existing relationship endpoints do not match candidate schema: " + type);
                }
                var values = validator.validateExisting(schema, definition.properties(), definition.constraints(), rows.getString(3), properties(rows.getString(8)));
                checkUnique(schema, "link", tenant, type, definition.properties(), values, unique);
                if (definition.cardinality() == Cardinality.ONE_TO_ONE || definition.cardinality() == Cardinality.MANY_TO_ONE) {
                    if (!cardinality.add(List.of(tenant, type, "from", rows.getString(5)))) throw new IllegalArgumentException("Existing relationship violates outbound cardinality: " + type);
                }
                if (definition.cardinality() == Cardinality.ONE_TO_ONE || definition.cardinality() == Cardinality.ONE_TO_MANY) {
                    if (!cardinality.add(List.of(tenant, type, "to", rows.getString(7)))) throw new IllegalArgumentException("Existing relationship violates inbound cardinality: " + type);
                }
            }
        }
    }

    private void checkUnique(OntologySchema schema, String kind, String tenant, String type, List<PropertyDefinition> fields,
                             Map<String, Object> values, Set<List<String>> seen) {
        for (var field : fields) {
            if (!field.unique() || field.primary() || values.get(field.name()) == null) continue;
            if (!seen.add(List.of(kind, tenant, type, field.name(), PropertyValues.uniqueKey(schema, field, values.get(field.name()))))) {
                throw new PropertyValidationException("SCHEMA_UNIQUE_CONFLICT", field.name());
            }
        }
    }

    private synchronized void initialize() {
        if (initialized) return;
        registry.initialize();
        try (var connection = data.getConnection()) {
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS of_active_schema (singleton_id INTEGER PRIMARY KEY, schema_version INTEGER NOT NULL, activation_id VARCHAR(64), fingerprint VARCHAR(64))");
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS of_schema_activations (
                          activation_id VARCHAR(64) PRIMARY KEY, schema_version INTEGER NOT NULL, previous_version INTEGER NOT NULL,
                          fingerprint VARCHAR(64) NOT NULL, activated_at VARCHAR(64) NOT NULL,
                          actor_id VARCHAR(255), request_tenant VARCHAR(255), migration_json %s
                        )
                        """.formatted(dialect.textType()));
            }
            if (!headExists(connection)) {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("INSERT INTO of_active_schema (singleton_id, schema_version) VALUES (1, 0)");
                } catch (SQLException competing) {
                    if (competing.getSQLState() == null || !competing.getSQLState().startsWith("23") || !headExists(connection)) throw competing;
                }
            }
            initialized = true;
        } catch (SQLException failure) { throw new IllegalStateException("Cannot initialize schema activation", failure); }
    }

    private boolean headExists(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var row = statement.executeQuery("SELECT singleton_id FROM of_active_schema WHERE singleton_id = 1")) { return row.next(); }
    }

    private Map<String, Object> properties(String json) {
        try { return mapper.readValue(json, new TypeReference<Map<String, Object>>() {}); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalArgumentException("Invalid stored schema activation input", failure); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalArgumentException("Cannot serialize migration approval", failure); }
    }

    record Binding(SchemaVersion version, String id, String fingerprint) {
        OntologySchema schema() { return version.schema(); }
    }
    private record Head(int version, String id, String fingerprint) {}
}
