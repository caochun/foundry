package org.openfoundry.foundation.api;

import org.h2.jdbcx.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.security.*;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.*;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class FieldLineageTest {
    @TempDir Path directory;
    static final RequestContext CTX = RequestContext.system("tenant", "operator");
    static final SecurityPrincipal PRINCIPAL = new SecurityPrincipal("operator", "tenant", Set.of());
    static final EntityKey PERSON = new EntityKey("Person", "a");
    static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name:"lineage",version:"1")
            type Person @objectType { id: ID! @primary name: String! note: String secret: String @sensitive payload: JSON }
            type Related @linkType(from:"Person",to:"Person",cardinality:MANY_TO_MANY) { id: ID! @primary note: String }
            type Rename @actionType(permission:"can_rename") { person: Person! @param name: String! @param }
            """);
    static final ActionManifest RENAME = new ActionManifest("Rename", 1, false, List.of(),
            List.of(new ActionManifest.UpdateObject("person", Map.of("name", "params.name"))));

    @TestFactory
    Stream<DynamicTest> lineageAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "direct origin and independent object relationship chains", this::origins),
                test(provider, "Action identity is attached to facts history and lineage", this::actions),
                test(provider, "same-value Action assertions acquire explicit provenance", this::reassertion),
                test(provider, "compensation appends evidence and preserves the original assertion", this::compensation),
                test(provider, "null absence and deleted lifecycle remain distinguishable", this::presence),
                test(provider, "observations preserve fact versions and freeze transaction origin", this::observations),
                test(provider, "transaction rollback removes facts and lineage together", this::rollback),
                test(provider, "tenant scope and stable sequence pagination", this::paging),
                test(provider, "hidden fields are excluded before lineage pagination", this::visibility),
                test(provider, "Consent protects lineage and relationship endpoints", this::consent),
                test(provider, "lineage reads keep the application schema binding", this::binding),
                test(provider, "real HTTP lineage requests enforce field visibility", this::http),
                test(provider, "lineage requires explicit metadata access and hides source pointers", this::metadataAccess)));
    }

    private DynamicTest test(String provider, String name, Consumer<Fixture> check) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> check.accept(fixture(provider.equals("memory") ? null : data("jdbc:h2:mem:lineage_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"))));
    }

    private void origins(Fixture f) {
        var rows = f.lineage(PERSON, "name");
        assertEquals(1, rows.size());
        assertEquals(MutationSource.Kind.DIRECT, rows.getFirst().source().kind());
        assertEquals("operator", rows.getFirst().actorId());
        assertEquals(LineageValues.hash(true, "Before"), rows.getFirst().valueHash());
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.mutationSource(new MutationSource(MutationSource.Kind.SYNC, "hr", "run-1", f.clock.instant(), Map.of("sourcePointer", "row-a", "mappingVersion", "1")));
            tx.createLink("Related", "a", PERSON, new EntityKey("Person", "b"), Map.of("note", "Imported"));
            tx.commit();
        }
        var link = f.lineage(new EntityKey("Related", "a"), "note").getFirst();
        assertEquals("hr", link.source().name());
        assertEquals("hr", f.storage.getEntityHistory(CTX, new EntityKey("Related", "a")).getFirst().sourceSystem());
        assertEquals(1, f.lineage(PERSON, "name").size());
        assertTrue(f.storage.getLineage(CTX, new EntityKey("Person", "missing"), LineageQuery.defaults()).isEmpty());
    }

    private void actions(Fixture f) {
        var app = f.app(RENAME, invocation -> fail(), null);
        var input = Map.<String, Object>of("person", "a", "name", "Changed");
        var result = app.execute(RENAME, CTX, PRINCIPAL, input, "rename");
        var row = f.lineage(PERSON, "name").getFirst();
        assertEquals(MutationSource.Kind.ACTION, row.source().kind());
        assertEquals(result.actionId(), row.source().operationId());
        assertEquals("Rename", row.source().name());
        assertEquals("EXECUTION", row.source().details().get("phase"));
        assertEquals(2, row.entityVersion());
        assertEquals(result.actionId(), f.storage.getObject(CTX, "Person", "a").lastActionId());
        assertEquals(result.actionId(), f.storage.getEntityHistory(CTX, PERSON).getLast().actionId());
        assertEquals(result, app.execute(RENAME, CTX, PRINCIPAL, input, "rename"));
        assertEquals(2, f.lineage(PERSON, "name").size());
    }

    private void reassertion(Fixture f) {
        var app = f.app(RENAME, invocation -> fail(), null);
        assertTrue(app.execute(RENAME, CTX, PRINCIPAL, Map.of("person", "a", "name", "Before"), "same-value").success());
        var rows = f.lineage(PERSON, "name");
        assertEquals(2, rows.size());
        assertEquals(rows.getFirst().valueHash(), rows.getLast().valueHash());
        assertEquals(MutationSource.Kind.ACTION, rows.getFirst().source().kind());
    }

    private void compensation(Fixture f) {
        var action = new ActionManifest(RENAME.action(), 1, false, List.of(), RENAME.effects(), ActionManifest.RollbackPolicy.ROLLBACK_ALL,
                List.of(new ActionManifest.SideEffect("notify", "event", Map.of("type", "renamed"), 1, Duration.ZERO)));
        var app = f.app(action, invocation -> { throw new IllegalStateException("delivery failed"); }, null);
        var result = app.execute(action, CTX, PRINCIPAL, Map.of("person", "a", "name", "Changed"), "undo");
        assertEquals("ROLLED_BACK", result.status());
        var rows = f.lineage(PERSON, "name");
        assertEquals(3, rows.size());
        assertEquals(List.of("COMPENSATION", "EXECUTION", "DIRECT"), rows.stream().map(row -> row.source().details().getOrDefault("phase", "DIRECT")).toList());
        assertEquals(rows.getFirst().valueHash(), rows.getLast().valueHash());
        assertEquals(result.actionId(), rows.getFirst().source().operationId());
        assertEquals(result, app.execute(action, CTX, PRINCIPAL, Map.of("person", "a", "name", "Changed"), "undo"));
        assertEquals(3, f.lineage(PERSON, "name").size());
        var same = app.execute(action, CTX, PRINCIPAL, Map.of("person", "b", "name", "Other"), "same-undo");
        assertEquals("ROLLED_BACK", same.status());
        assertEquals("COMPENSATION", f.lineage(new EntityKey("Person", "b"), "name").getFirst().source().details().get("phase"));
    }

    private void presence(Fixture f) {
        var patch = new LinkedHashMap<String, Object>();
        patch.put("note", null);
        try (var tx = f.storage.beginTransaction(CTX)) { tx.updateObject("Person", "a", patch, 1); tx.commit(); }
        var cleared = f.lineage(PERSON, "note").getFirst();
        assertTrue(cleared.valuePresent());
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.restoreObjectProperties("Person", "a", Map.of("name", "Before", "secret", "private", "payload", Map.of("x", 1)), 2);
            tx.commit();
        }
        var missing = f.lineage(PERSON, "note").getFirst();
        assertFalse(missing.valuePresent());
        assertNotEquals(cleared.valueHash(), missing.valueHash());
        try (var tx = f.storage.beginTransaction(CTX)) { tx.deleteObject("Person", "a", 3); tx.commit(); }
        assertFalse(f.lineage(PERSON, "name").getFirst().valuePresent());
        try (var tx = f.storage.beginTransaction(CTX)) { assertFalse(tx.latestLineage(PERSON).get("_entity").valuePresent()); }
    }

    private void observations(Fixture f) {
        var input = new ArrayList<Object>(List.of("external-input"));
        var origin = new MutationSource(MutationSource.Kind.FUNCTION, "derive", "job", f.clock.instant().minusSeconds(50), Map.of("inputRefs", input, "functionVersion", "1"));
        input.add("mutated");
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.mutationSource(origin);
            tx.recordProvenance(PERSON, 1, Set.of("name"));
            assertEquals(origin, tx.latestLineage(PERSON).get("name").source());
            assertThrows(IllegalStateException.class, () -> tx.mutationSource(MutationSource.direct("other", f.clock.instant())));
            tx.commit();
        }
        assertEquals(1, f.storage.getObject(CTX, "Person", "a").version());
        var row = f.lineage(PERSON, "name").getFirst();
        assertEquals(List.of("external-input"), row.source().details().get("inputRefs"));
        assertThrows(UnsupportedOperationException.class, () -> row.source().details().put("forged", true));
        assertEquals(1, row.entityVersion());
        assertTrue(row.source().producedAt().isBefore(f.lineage(PERSON, "name").getLast().source().producedAt()));
    }

    private void rollback(Fixture f) {
        var before = f.storage.getLineage(CTX, PERSON, LineageQuery.defaults());
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.mutationSource(new MutationSource(MutationSource.Kind.SYNC, "hr", "failed", f.clock.instant(), Map.of()));
            tx.updateObject("Person", "a", Map.of("name", "Uncommitted"), 1);
            assertEquals("hr", tx.latestLineage(PERSON).get("name").source().name());
        }
        assertEquals("Before", f.storage.getObject(CTX, "Person", "a").properties().get("name"));
        assertEquals(before, f.storage.getLineage(CTX, PERSON, LineageQuery.defaults()));
    }

    private void paging(Fixture f) {
        for (int i = 1; i <= 3; i++) {
            try (var tx = f.storage.beginTransaction(CTX)) { tx.updateObject("Person", "a", Map.of("name", "Value" + i), i); tx.commit(); }
        }
        var first = f.storage.getLineage(CTX, PERSON, new LineageQuery("name", 2, null));
        var second = f.storage.getLineage(CTX, PERSON, new LineageQuery("name", 2, first.getLast().sequence()));
        assertEquals(List.of(4L, 3L), first.stream().map(FieldProvenance::entityVersion).toList());
        assertEquals(List.of(2L, 1L), second.stream().map(FieldProvenance::entityVersion).toList());
        assertTrue(f.storage.getLineage(RequestContext.system("other", "operator"), PERSON, LineageQuery.defaults()).isEmpty());
        assertTrue(f.storage.getLineage(CTX, PERSON, new LineageQuery(null, 0, null)).isEmpty());
    }

    private void visibility(Fixture f) {
        for (int i = 1; i <= 130; i++) {
            try (var tx = f.storage.beginTransaction(CTX)) { tx.updateObject("Person", "a", Map.of("secret", "hidden" + i), i); tx.commit(); }
        }
        var rows = f.app(RENAME, invocation -> fail(), null).lineage(CTX, PRINCIPAL, PERSON, new LineageQuery(null, 1, null));
        assertEquals(1, rows.size());
        assertFalse(rows.getFirst().field().equals("secret"));
        assertFalse(rows.getFirst().field().startsWith("_"));
        assertThrows(SecurityException.class, () -> f.app(RENAME, invocation -> fail(), null).lineage(CTX, PRINCIPAL, PERSON, new LineageQuery("secret", 1, null)));
        f.denied.add(PERSON);
        assertTrue(f.app(RENAME, invocation -> fail(), null).lineage(CTX, PRINCIPAL, PERSON, LineageQuery.defaults()).isEmpty());
    }

    private void consent(Fixture f) {
        var service = new ConsentService(f.consent, f.authorization(), new ConsentConfiguration(Set.of("Person"), "PURPOSE"));
        var app = f.app(RENAME, invocation -> fail(), service);
        assertTrue(app.lineage(CTX, PRINCIPAL, PERSON, LineageQuery.defaults()).isEmpty());
        f.consent.record(CTX, PERSON, "PURPOSE", ConsentRecord.Decision.GRANT, "approved");
        assertFalse(app.lineage(CTX, PRINCIPAL, PERSON, LineageQuery.defaults()).isEmpty());
        f.consent.record(CTX, PERSON, "PURPOSE", ConsentRecord.Decision.DENY, "withdrawn");
        assertTrue(app.lineage(CTX, PRINCIPAL, PERSON, LineageQuery.defaults()).isEmpty());
    }

    private void binding(Fixture f) {
        var app = f.app(RENAME, invocation -> fail(), null);
        var schema = new OntologySchema(SCHEMA.namespace(), "2", SCHEMA.objectTypes(), SCHEMA.linkTypes(), SCHEMA.actionTypes());
        if (f.storage instanceof JdbcStorageProvider jdbc) jdbc.activateSchema(CTX, schema, null, jdbc.boundSchemaVersion());
        else f.storage.applySchema(CTX, schema);
        assertThrows(SchemaVersionMismatchException.class, () -> app.lineage(CTX, PRINCIPAL, PERSON, LineageQuery.defaults()));
    }

    private void metadataAccess(Fixture f) {
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.mutationSource(new MutationSource(MutationSource.Kind.SYNC, "hr", "run", f.clock.instant(),
                    Map.of("sourcePointer", "sensitive-external-identity", "sourceVersion", "opaque-private-token", "mappingVersion", "v1")));
            tx.updateObject("Person", "a", Map.of("name", "Visible"), 1);
            tx.commit();
        }
        var limited = new ApplicationService(f.storage, new AuthorizationService((principal, relation, key) -> !relation.equals("can_view_lineage")),
                new ActionExecutor(), SCHEMA, Map.of("Rename", RENAME), Map.of());
        assertNotNull(limited.getObject(CTX, PRINCIPAL, "Person", "a"));
        assertThrows(SecurityException.class, () -> limited.lineage(CTX, PRINCIPAL, PERSON, LineageQuery.defaults()));
        var publicRow = f.app(RENAME, invocation -> fail(), null).lineage(CTX, PRINCIPAL, PERSON, new LineageQuery("name", 1, null)).getFirst();
        assertEquals(Map.of("mappingVersion", "v1"), publicRow.source().details());
        assertEquals("sensitive-external-identity", f.lineage(PERSON, "name").getFirst().source().details().get("sourcePointer"));
    }

    private void http(Fixture f) {
        try (var server = new JdkRestServer(0, new RestApiRouter(f.app(RENAME, invocation -> fail(), null)), () -> new ApiRequestContext(CTX, PRINCIPAL));
             var client = java.net.http.HttpClient.newHttpClient()) {
            server.start();
            String base = "http://localhost:" + server.port() + "/api/v1/Person/a/lineage";
            var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "?field=name&limit=1")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("DIRECT"));
            var hidden = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "?field=secret")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(403, hidden.statusCode());
            var invalid = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "?after=1")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode());
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    @Test
    void fileRecoveryAndLegacyFieldsDoNotInventSources() throws Exception {
        var data = data("jdbc:h2:file:" + directory.resolve("lineage"));
        var f = fixture(data);
        actions(f);
        var restored = new JdbcStorageProvider(data, DatabaseDialect.h2(), f.clock);
        restored.applySchema(CTX, SCHEMA);
        assertEquals(f.storage.getLineage(CTX, PERSON, LineageQuery.defaults()), restored.getLineage(CTX, PERSON, LineageQuery.defaults()));
        try (var connection = data.getConnection(); var statement = connection.createStatement()) { statement.executeUpdate("DELETE FROM of_field_lineage"); }
        try (var tx = restored.beginTransaction(CTX)) { tx.updateObject("Person", "a", Map.of("name", "Modern"), 2); tx.commit(); }
        assertTrue(restored.getLineage(CTX, PERSON, new LineageQuery("secret", 10, null)).isEmpty());
        assertEquals(1, restored.getLineage(CTX, PERSON, new LineageQuery("name", 10, null)).size());
    }

    @Test
    void jdbcLineageWriteFailureCannotCommitTheFact() throws Exception {
        var data = data("jdbc:h2:mem:lineage_failure;DB_CLOSE_DELAY=-1");
        var f = fixture(data);
        var before = f.storage.getLineage(CTX, PERSON, LineageQuery.defaults());
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER reject_lineage BEFORE INSERT ON of_field_lineage FOR EACH ROW CALL 'org.openfoundry.foundation.api.FieldLineageTest$RejectLineage'");
        }
        try (var tx = f.storage.beginTransaction(CTX)) {
            assertThrows(IllegalStateException.class, () -> tx.updateObject("Person", "a", Map.of("name", "Rejected"), 1));
            assertThrows(IllegalStateException.class, tx::commit);
        }
        assertEquals(1, f.storage.getObject(CTX, "Person", "a").version());
        assertEquals(1, f.storage.getEntityHistory(CTX, PERSON).size());
        assertEquals(before, f.storage.getLineage(CTX, PERSON, LineageQuery.defaults()));
    }

    @Test
    void singleConnectionSupportsTransactionLineageReadsAndObservations() {
        var pool = JdbcConnectionPool.create("jdbc:h2:mem:lineage_pool;DB_CLOSE_DELAY=-1", "sa", "");
        pool.setMaxConnections(1);
        pool.setLoginTimeout(2);
        try { observations(fixture(pool)); }
        finally { pool.dispose(); }
    }

    public static final class RejectLineage implements org.h2.api.Trigger {
        @Override public void fire(java.sql.Connection connection, Object[] oldRow, Object[] newRow) throws java.sql.SQLException {
            throw new java.sql.SQLException("Injected lineage failure");
        }
    }

    static JdbcDataSource data(String url) { var data = new JdbcDataSource(); data.setURL(url); return data; }
    static Fixture fixture(DataSource data) {
        var clock = new TestClock();
        StorageProvider storage = data == null ? new InMemoryStorageProvider(clock) : new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
        ConsentStore consent = data == null ? new InMemoryConsentStore(clock) : new JdbcConsentStore(data, DatabaseDialect.h2(), clock);
        storage.applySchema(CTX, SCHEMA);
        try (var tx = storage.beginTransaction(CTX)) {
            tx.createObject("Person", "a", Map.of("name", "Before", "secret", "private", "payload", Map.of("x", 1)));
            tx.createObject("Person", "b", Map.of("name", "Other"));
            tx.commit();
        }
        return new Fixture(storage, consent, clock);
    }
    static final class Fixture {
        final StorageProvider storage;
        final ConsentStore consent;
        final TestClock clock;
        final Set<EntityKey> denied = new HashSet<>();
        Fixture(StorageProvider storage, ConsentStore consent, TestClock clock) { this.storage = storage; this.consent = consent; this.clock = clock; }
        AuthorizationService authorization() { return new AuthorizationService((principal, relation, key) -> !denied.contains(key)); }
        ApplicationService app(ActionManifest action, SideEffectHandler handler, ConsentService policy) {
            return new ApplicationService(storage, authorization(), new ActionExecutor().withSideEffects(handler, clock, Duration.ofSeconds(10)),
                    SCHEMA, Map.of(action.action(), action), Map.of(), AuthorizationMode.STRICT_RESOURCES, policy);
        }
        List<FieldProvenance> lineage(EntityKey key, String field) { return storage.getLineage(CTX, key, new LineageQuery(field, 100, null)); }
    }
    static final class TestClock extends Clock {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
