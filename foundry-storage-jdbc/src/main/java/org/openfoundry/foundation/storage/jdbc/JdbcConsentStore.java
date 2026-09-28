package org.openfoundry.foundation.storage.jdbc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfoundry.foundation.spi.*;

import javax.sql.DataSource;
import java.sql.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;

/** Consent decisions and their successful audit entries commit atomically. */
public final class JdbcConsentStore implements ConsentStore {
    private final DataSource data;
    private final DatabaseDialect dialect;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(JsonNumbers.module());
    private volatile boolean initialized;

    public JdbcConsentStore(DataSource data, DatabaseDialect dialect) { this(data, dialect, Clock.systemUTC()); }
    public JdbcConsentStore(DataSource data, DatabaseDialect dialect, Clock clock) {
        this.data = Objects.requireNonNull(data); this.dialect = Objects.requireNonNull(dialect); this.clock = Objects.requireNonNull(clock);
    }

    @Override public ConsentSnapshot snapshot(RequestContext context, EntityKey subject) {
        initialize();
        try (var connection = data.getConnection()) { return snapshot(connection, context, subject); }
        catch (SQLException failure) { throw new IllegalStateException("Cannot read consent", failure); }
    }
    @Override public ConsentSnapshot snapshot(RequestContext context, EntityKey subject, Transaction transaction) {
        if (transaction != null && !context.equals(transaction.context())) throw new SecurityException("Consent context must match the enclosing transaction");
        if (transaction == null) return snapshot(context, subject);
        if (!initialized) throw new IllegalStateException("Initialize consent storage before starting ontology transactions");
        if (!(transaction instanceof JdbcTransactionAccess access) || access.transactionDataSource() != data) {
            throw new IllegalStateException("Transactional JDBC consent requires the ontology transaction's DataSource and connection");
        }
        try { return snapshot(access.transactionConnection(), context, subject); }
        catch (SQLException failure) { throw new IllegalStateException("Cannot read transactional consent", failure); }
    }
    private ConsentSnapshot snapshot(Connection connection, RequestContext context, EntityKey subject) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT s.revision, s.opted_out, r.record_seq, r.purpose, r.decision, r.recorded_at, r.recorded_by, r.evidence
                FROM of_consent_subjects s LEFT JOIN of_consent_records r
                  ON s.tenant_id = r.tenant_id AND s.subject_type = r.subject_type AND s.subject_id = r.subject_id
                WHERE s.tenant_id = ? AND s.subject_type = ? AND s.subject_id = ? ORDER BY r.record_seq
                """)) {
            subject(statement, context, subject);
            var records = new ArrayList<ConsentRecord>();
            long revision = 0;
            boolean optedOut = false;
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    revision = rows.getLong(1); optedOut = rows.getBoolean(2);
                    if (rows.getObject(3) != null) {
                        long sequence = rows.getLong(3);
                        if (sequence > revision) throw new IllegalStateException("Consent history exceeds its revision");
                        records.add(new ConsentRecord(subject, rows.getString(4), ConsentRecord.Decision.valueOf(rows.getString(5)), sequence,
                                Instant.parse(rows.getString(6)), rows.getString(7), rows.getString(8)));
                    }
                }
            }
            return new ConsentSnapshot(revision, optedOut, records);
        }
    }

    @Override public ConsentRecord record(RequestContext context, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence) {
        requireActor(context);
        // Validate before allocating persistent metadata.
        new ConsentRecord(subject, purpose, decision, 1, clock.instant(), context.actorId(), evidence);
        prepareSubject(context, subject);
        return transaction(connection -> append(connection, context, subject, purpose, decision, evidence, null));
    }
    @Override public void prepareTransaction(RequestContext context, EntityKey subject, Transaction transaction) {
        if (!initialized || !(transaction instanceof JdbcTransactionAccess access) || access.transactionDataSource() != data) {
            throw new IllegalStateException("Transactional consent requires an initialized store and the same JDBC DataSource");
        }
        if (!context.equals(transaction.context())) throw new SecurityException("Consent context must match the enclosing transaction");
        try { ensureSubjectInTransaction(access.transactionConnection(), context, subject); lock(access.transactionConnection(), context, subject); }
        catch (SQLException failure) { throw new IllegalStateException("Cannot enlist consent subject", failure); }
    }
    @Override public ConsentRecord record(RequestContext context, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence, Transaction transaction) {
        return transactionalRecord(context, subject, purpose, decision, evidence, null, transaction);
    }
    @Override public ConsentRecord restore(RequestContext context, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence, long expectedRevision, Transaction transaction) {
        return transactionalRecord(context, subject, purpose, decision, evidence, expectedRevision, transaction);
    }
    private ConsentRecord transactionalRecord(RequestContext context, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence, Long expectedRevision, Transaction transaction) {
        requireActor(context);
        new ConsentRecord(subject, purpose, decision, 1, clock.instant(), context.actorId(), evidence);
        if (!initialized || !(transaction instanceof JdbcTransactionAccess access) || access.transactionDataSource() != data) {
            throw new IllegalStateException("Transactional consent requires an initialized store and the same JDBC DataSource");
        }
        if (!context.equals(transaction.context())) throw new SecurityException("Consent context must match the enclosing transaction");
        try {
            var connection = access.transactionConnection();
            ensureSubjectInTransaction(connection, context, subject);
            return append(connection, context, subject, purpose, decision, evidence, expectedRevision);
        } catch (SQLException failure) { throw new IllegalStateException("Transactional consent failed", failure); }
    }
    private void ensureSubjectInTransaction(Connection connection, RequestContext context, EntityKey subject) throws SQLException {
        if (exists(connection, context, subject)) return;
        var savepoint = connection.setSavepoint();
        try (var statement = connection.prepareStatement("INSERT INTO of_consent_subjects (tenant_id, subject_type, subject_id, revision, opted_out) VALUES (?, ?, ?, 0, FALSE)")) {
            subject(statement, context, subject); statement.executeUpdate();
        } catch (SQLException conflict) {
            connection.rollback(savepoint);
            if (conflict.getSQLState() == null || !conflict.getSQLState().startsWith("23") || !exists(connection, context, subject)) throw conflict;
        } finally { connection.releaseSavepoint(savepoint); }
    }
    private ConsentRecord append(Connection connection, RequestContext context, EntityKey subject, String purpose, ConsentRecord.Decision decision, String evidence, Long expectedRevision) throws SQLException {
        long previous = lock(connection, context, subject);
        if (expectedRevision != null && previous != expectedRevision) throw new IllegalStateException("Consent compensation version conflict");
        long revision = previous + 1;
        var record = new ConsentRecord(subject, purpose, decision, revision, clock.instant(), context.actorId(), evidence);
        try (var statement = connection.prepareStatement("""
                INSERT INTO of_consent_records (tenant_id, subject_type, subject_id, record_seq, purpose, decision, recorded_at, recorded_by, evidence)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            subject(statement, context, subject);
            statement.setLong(4, revision); statement.setString(5, purpose); statement.setString(6, decision.name());
            statement.setString(7, record.recordedAt().toString()); statement.setString(8, context.actorId()); statement.setString(9, evidence);
            statement.executeUpdate();
        }
        advance(connection, context, subject, revision, null);
        var detail = new LinkedHashMap<String, Object>(); detail.put("decision", decision.name()); detail.put("evidence", evidence);
        audit(connection, context, subject, purpose, "RECORD", "SUCCESS", detail);
        return record;
    }
    @Override public void setOptOut(RequestContext context, EntityKey subject, boolean optedOut, String reason) {
        requireActor(context);
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Opt-out reason is required");
        prepareSubject(context, subject);
        transaction(connection -> {
            long revision = lock(connection, context, subject) + 1;
            advance(connection, context, subject, revision, optedOut);
            audit(connection, context, subject, null, "OPT_OUT", "SUCCESS", Map.of("optedOut", optedOut, "reason", reason));
            return null;
        });
    }
    @Override public void audit(RequestContext context, EntityKey subject, String purpose, String operation, String outcome, Map<String, Object> detail) {
        requireActor(context);
        initialize();
        transaction(connection -> { audit(connection, context, subject, purpose, operation, outcome, detail); return null; });
    }
    private void audit(Connection connection, RequestContext context, EntityKey subject, String purpose, String operation, String outcome, Map<String, Object> detail) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO of_consent_audit (tenant_id, subject_type, subject_id, audit_id, purpose, operation, outcome, actor_id, trace_id, recorded_at, detail_json)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            subject(statement, context, subject); statement.setString(4, UUID.randomUUID().toString()); statement.setString(5, purpose);
            statement.setString(6, operation); statement.setString(7, outcome); statement.setString(8, context.actorId()); statement.setString(9, context.traceId());
            statement.setString(10, clock.instant().toString());
            try { statement.setString(11, mapper.writeValueAsString(detail)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalArgumentException("Invalid consent audit detail", failure); }
            statement.executeUpdate();
        }
    }
    @Override public List<ConsentAudit> auditHistory(RequestContext context, EntityKey subject) {
        initialize();
        try (var connection = data.getConnection(); var statement = connection.prepareStatement("SELECT * FROM of_consent_audit WHERE tenant_id = ? AND subject_type = ? AND subject_id = ?")) {
            subject(statement, context, subject);
            var result = new ArrayList<ConsentAudit>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> detail;
                    try { detail = mapper.readValue(rows.getString("detail_json"), new TypeReference<>() {}); }
                    catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException("Invalid consent audit JSON", failure); }
                    result.add(new ConsentAudit(rows.getString("audit_id"), subject, rows.getString("purpose"), rows.getString("operation"), rows.getString("outcome"),
                            rows.getString("actor_id"), rows.getString("trace_id"), Instant.parse(rows.getString("recorded_at")), detail));
                }
            }
            return result.stream().sorted(Comparator.comparing(ConsentAudit::recordedAt).thenComparing(ConsentAudit::id)).toList();
        } catch (SQLException failure) { throw new IllegalStateException("Cannot read consent audit", failure); }
    }
    private long lock(Connection connection, RequestContext context, EntityKey subject) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT revision FROM of_consent_subjects WHERE tenant_id = ? AND subject_type = ? AND subject_id = ? FOR UPDATE")) {
            subject(statement, context, subject);
            try (var row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalStateException("Consent subject guard missing");
                long revision = row.getLong(1);
                if (revision == Long.MAX_VALUE) throw new IllegalStateException("Consent revision exhausted");
                return revision;
            }
        }
    }
    private void advance(Connection connection, RequestContext context, EntityKey subject, long revision, Boolean optOut) throws SQLException {
        try (var statement = connection.prepareStatement("UPDATE of_consent_subjects SET revision = ?" + (optOut == null ? "" : ", opted_out = ?") + " WHERE tenant_id = ? AND subject_type = ? AND subject_id = ?")) {
            int index = 1; statement.setLong(index++, revision);
            if (optOut != null) statement.setBoolean(index++, optOut);
            statement.setString(index++, context.tenantId()); statement.setString(index++, subject.type()); statement.setString(index, subject.id());
            if (statement.executeUpdate() != 1) throw new IllegalStateException("Consent subject guard missing");
        }
    }
    private void prepareSubject(RequestContext context, EntityKey subject) {
        initialize();
        try (var connection = data.getConnection()) {
            connection.setAutoCommit(true);
            if (exists(connection, context, subject)) return;
            try (var statement = connection.prepareStatement("INSERT INTO of_consent_subjects (tenant_id, subject_type, subject_id, revision, opted_out) VALUES (?, ?, ?, 0, FALSE)")) {
                subject(statement, context, subject); statement.executeUpdate();
            } catch (SQLException competing) {
                if (competing.getSQLState() == null || !competing.getSQLState().startsWith("23") || !exists(connection, context, subject)) throw competing;
            }
        } catch (SQLException failure) { throw new IllegalStateException("Cannot initialize consent subject", failure); }
    }
    private boolean exists(Connection connection, RequestContext context, EntityKey subject) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT revision FROM of_consent_subjects WHERE tenant_id = ? AND subject_type = ? AND subject_id = ?")) {
            subject(statement, context, subject); try (var row = statement.executeQuery()) { return row.next(); }
        }
    }
    private static void subject(PreparedStatement statement, RequestContext context, EntityKey subject) throws SQLException {
        statement.setString(1, context.tenantId()); statement.setString(2, subject.type()); statement.setString(3, subject.id());
    }
    private <T> T transaction(Work<T> work) {
        try (var connection = data.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); connection.setAutoCommit(false);
            try { T result = work.run(connection); connection.commit(); return result; }
            catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        } catch (SQLException failure) { throw new IllegalStateException("Consent transaction failed", failure); }
    }
    @Override public synchronized void initialize() {
        if (initialized) return;
        try (var connection = data.getConnection()) {
            connection.setAutoCommit(true);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS of_consent_subjects (tenant_id VARCHAR(255) NOT NULL, subject_type VARCHAR(255) NOT NULL, subject_id VARCHAR(512) NOT NULL, revision BIGINT NOT NULL, opted_out BOOLEAN NOT NULL, PRIMARY KEY(tenant_id,subject_type,subject_id))");
                statement.execute("CREATE TABLE IF NOT EXISTS of_consent_records (tenant_id VARCHAR(255) NOT NULL, subject_type VARCHAR(255) NOT NULL, subject_id VARCHAR(512) NOT NULL, record_seq BIGINT NOT NULL, purpose VARCHAR(255) NOT NULL, decision VARCHAR(16) NOT NULL, recorded_at VARCHAR(64) NOT NULL, recorded_by VARCHAR(255) NOT NULL, evidence " + dialect.textType() + ", PRIMARY KEY(tenant_id,subject_type,subject_id,record_seq))");
                statement.execute("CREATE TABLE IF NOT EXISTS of_consent_audit (tenant_id VARCHAR(255) NOT NULL, subject_type VARCHAR(255) NOT NULL, subject_id VARCHAR(512) NOT NULL, audit_id VARCHAR(64) PRIMARY KEY, purpose VARCHAR(255), operation VARCHAR(64) NOT NULL, outcome VARCHAR(32) NOT NULL, actor_id VARCHAR(255) NOT NULL, trace_id VARCHAR(255), recorded_at VARCHAR(64) NOT NULL, detail_json " + dialect.textType() + " NOT NULL)");
                statement.execute("CREATE INDEX IF NOT EXISTS idx_of_consent_audit_subject ON of_consent_audit (tenant_id,subject_type,subject_id)");
            }
            initialized = true;
        } catch (SQLException failure) { throw new IllegalStateException("Cannot initialize consent store", failure); }
    }
    private static void requireActor(RequestContext context) { if (context.actorId() == null) throw new SecurityException("Consent mutation requires an authenticated recorder"); }
    @FunctionalInterface private interface Work<T> { T run(Connection connection) throws SQLException; }
}
