package org.openfoundry.foundation.sync;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class MappingExecutionTest {
    static final RequestContext CTX = RequestContext.system("tenant", "importer");
    static final OntologySchema SCHEMA = new OdlParser().parse("""
            extend schema @namespace(name:"mapping",version:"1")
            type Person @objectType { id: ID! @primary name: String! note: String age: Int born: Date payload: JSON }
            type Unit @objectType { id: ID! @primary name: String! }
            type Member @linkType(from:"Person",to:"Unit",cardinality:MANY_TO_ONE) { id: ID! @primary weight: Int }
            """);
    static final String YAML = """
            datasource: People
            connector: jdbc
            connection: {url: '${PEOPLE_DB}', table: people}
            mapping:
              objectType: Person
              primaryKey: {source: source_id, target: id, transform: "prefix('person-')"}
              properties:
                name: {source: last, transform: "concat(first, ' ', last)"}
                age: {source: age_text, transform: "parseInt()"}
                born: {source: birth_text, transform: "parseDate('dd/MM/yyyy')"}
            sync: {mode: BATCH}
            """;

    @TestFactory
    Stream<DynamicTest> materializedMappings() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "transforms are applied before validation and provenance", this::materializes),
                test(provider, "transform errors stop before later checkpoints", this::failure),
                test(provider, "custom versions bind receipts and checkpoints", this::customVersion),
                test(provider, "unknown custom functions fail before source reads", this::missingFunction),
                test(provider, "primary targets are bound before source reads", this::primaryTarget),
                test(provider, "one source can fill differently transformed target fields", this::duplicateSource),
                test(provider, "relation declarations are applied with object ingestion", this::relationships)));
    }

    private DynamicTest test(String provider, String name, Consumer<Fixture> verify) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> {
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider();
            else {
                var data = new JdbcDataSource(); data.setURL("jdbc:h2:mem:map_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
                storage = new JdbcStorageProvider(data, DatabaseDialect.h2());
            }
            storage.applySchema(CTX, SCHEMA);
            verify.accept(new Fixture(storage));
        });
    }

    private void materializes(Fixture f) {
        var mapping = new MappingConfigParser().parse(YAML).mapping();
        var source = record(1, Map.of("source_id", "1", "first", "Ada", "last", "Lovelace", "age_text", "36 years", "birth_text", "10/12/1815"));
        var result = f.run(mapping, List.of(source));
        assertEquals(1, result.created(), result.failures().toString());
        var object = f.storage.getObject(CTX, "Person", "person-1");
        assertEquals(Map.of("name", "Ada Lovelace", "age", 36, "born", "1815-12-10"), object.properties());
        var lineage = f.storage.getLineage(CTX, object.key(), new LineageQuery("name", 1, null)).getFirst();
        assertEquals(new RecordMapper(mapping).fingerprint(), lineage.source().details().get("mappingVersion"));
        assertEquals(1, f.run(mapping, List.of(source)).replayed());
        assertEquals(1, f.storage.getObject(CTX, "Person", "person-1").version());
    }

    private void failure(Fixture f) {
        var mapping = new MappingConfigParser().parse(YAML).mapping();
        var first = record(1, Map.of("source_id", "1", "first", "First", "last", "Person", "birth_text", "01/01/2000"));
        var invalid = record(2, Map.of("source_id", "2", "first", "Bad", "last", "Date", "birth_text", "31/02/2000"));
        var later = record(3, Map.of("source_id", "3", "first", "Later", "last", "Person"));
        var result = f.run(mapping, List.of(first, invalid, later));
        assertEquals(1, result.created());
        assertEquals(1, result.failures().size());
        assertEquals(1, f.service.checkpoint("people", mapping, "p0", CTX).sequence());
        assertNull(f.storage.getObject(CTX, "Person", "person-2"));
        assertNull(f.storage.getObject(CTX, "Person", "person-3"));
    }

    private void customVersion(Fixture f) {
        var config = new MappingConfig("Person", new KeyMapping("id", "id", null), Map.of("name", new PropertyMapping("name", "custom('canonical')")), List.of());
        var v1 = new TransformRegistry().with("canonical", "1", (value, source) -> value.toString().toUpperCase(Locale.ROOT));
        f.service = f.service.withTransforms(v1);
        var source = record(1, Map.of("id", "a", "name", "alice"));
        assertEquals(1, f.run(config, List.of(source)).created());
        assertEquals("ALICE", f.storage.getObject(CTX, "Person", "a").properties().get("name"));
        var changed = f.service.withTransforms(new TransformRegistry().with("canonical", "2", (value, row) -> value.toString().toUpperCase(Locale.ROOT)));
        assertThrows(IllegalStateException.class, () -> changed.checkpoint("people", config, "p0", CTX));
        var replay = changed.sync(connector(List.of(source), null), new SourceQuery("people", Map.of()), config, CTX);
        assertEquals(1, replay.failures().size());
        assertEquals(1, f.storage.getObject(CTX, "Person", "a").version());
        assertEquals(1, f.run(config, List.of(source)).replayed());
    }

    private void missingFunction(Fixture f) {
        var mapping = new MappingConfigParser().parse(YAML.replace("concat(first, ' ', last)", "custom('missing')")).mapping();
        var read = new AtomicBoolean();
        assertThrows(IllegalArgumentException.class, () -> f.service.sync(connector(List.of(), read), new SourceQuery("people", Map.of()), mapping, CTX));
        assertFalse(read.get());
    }

    private void primaryTarget(Fixture f) {
        var mapping = new MappingConfigParser().parse(YAML.replace("target: id", "target: name")).mapping();
        var read = new AtomicBoolean();
        assertThrows(IllegalArgumentException.class, () -> f.service.sync(connector(List.of(), read), new SourceQuery("people", Map.of()), mapping, CTX));
        assertFalse(read.get());
    }

    private void duplicateSource(Fixture f) {
        var mapping = new MappingConfig("Person", new KeyMapping("id", "id", null),
                Map.of("name", new PropertyMapping("value", "toUpper()"), "note", new PropertyMapping("value", "toLower()")), List.of());
        var result = f.run(mapping, List.of(record(1, Map.of("id", "a", "value", "Mixed"))));
        assertTrue(result.failures().isEmpty(), result.failures().toString());
        assertEquals(Map.of("name", "MIXED", "note", "mixed"), f.storage.getObject(CTX, "Person", "a").properties());
    }

    private void relationships(Fixture f) {
        var mapping = new MappingConfig("Person", new KeyMapping("id", "id", null), Map.of("name", new PropertyMapping("name")),
                List.of(new LinkMapping("Member", "Unit", new KeyMapping("unit", "id", "prefix('unit-')"), Map.of("weight", new PropertyMapping("score", "parseInt()")))));
        MappingSchemaValidator.validate(mapping, SCHEMA);
        var source = record(1, Map.of("id", "a", "name", "Name", "unit", "1", "score", "2"));
        var link = new RecordMapper(mapping).map(source).links().getFirst();
        assertEquals(new EntityKey("Unit", "unit-1"), link.target());
        assertEquals(Map.of("weight", 2), link.properties());
        try (var tx = f.storage.beginTransaction(CTX)) {
            tx.createObject("Unit", "unit-1", Map.of("name", "Unit"));
            tx.commit();
        }
        var applied = f.run(mapping, List.of(source));
        assertTrue(applied.failures().isEmpty(), applied.failures().toString());
        assertEquals(Map.of("created", 1), applied.relationshipChanges());
        assertNotNull(f.storage.getObject(CTX, "Person", "a"));
        var wrong = new MappingConfig("Person", mapping.primaryKey(), mapping.properties(), List.of(new LinkMapping("Member", "Person", new KeyMapping("unit", "id", null), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> MappingSchemaValidator.validate(wrong, SCHEMA));
    }

    static SourceRecord record(long index, Map<String, Object> data) {
        return new SourceRecord("people", "row-" + index, "UPSERT", Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index), data, null,
                new SourcePosition("p0", "event-" + index, index, "cursor-" + index));
    }
    private static Connector connector(List<SourceRecord> rows, AtomicBoolean read) {
        return new Connector() {
            public String name() { return "people"; }
            public Stream<SourceRecord> read(SourceQuery query) { if (read != null) read.set(true); return rows.stream(); }
        };
    }
    private static final class Fixture {
        final StorageProvider storage;
        MaterializedSyncService service;
        Fixture(StorageProvider storage) {
            this.storage = storage;
            service = new MaterializedSyncService(storage).withAuthorization((ctx, connector, mapping, target, tx) -> true);
        }
        MaterializedSyncService.SyncResult run(MappingConfig mapping, List<SourceRecord> rows) { return service.sync(connector(rows, null), new SourceQuery("people", Map.of()), mapping, CTX); }
    }
}
