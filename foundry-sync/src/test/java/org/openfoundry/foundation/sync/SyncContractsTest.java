package org.openfoundry.foundation.sync;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.Provenance;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.schema.ObjectTypeDefinition;
import org.openfoundry.foundation.spi.schema.OntologySchema;
import org.openfoundry.foundation.spi.schema.PropertyDefinition;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncContractsTest {
    @Test
    void mapsSourceRecordAndResolvesSourcePriority() {
        Instant now = Instant.now();
        Provenance provenance = new Provenance("hr", "person-1", "v1", "identity", now, "sync", "hash");
        SourceRecord source = new SourceRecord("hr", "person-1", "UPSERT", now,
                Map.of("person_id", "p-1", "name", "Alice"), provenance);
        MappedRecord mapped = new RecordMapper(new MappingConfig("Person", "person_id", Map.of("name", "name"))).map(source);
        assertEquals("p-1", mapped.key().id());

        ConflictResolver resolver = new ConflictResolver(ConflictResolver.Strategy.SOURCE_PRIORITY,
                Map.of(), Map.of("hr", 1, "legacy", 2));
        var resolution = resolver.resolve(Map.of("name", new ConflictResolver.IncomingValue("Alice", "hr", now, false)),
                Map.of("name", new ConflictResolver.ExistingValue("Alicia", "legacy", now, false)));
        assertEquals("Alice", resolution.accepted().get("name"));
    }

    @Test
    void explicitlyRankedSourcesStillPrecedeUnknownSourcesAtMaximumRank() {
        var resolver = new ConflictResolver(ConflictResolver.Strategy.SOURCE_PRIORITY, Map.of(), Map.of("known", Integer.MAX_VALUE));
        var time = Instant.parse("2030-01-01T00:00:00Z");
        var result = resolver.resolve(Map.of("name", new ConflictResolver.IncomingValue("Known", "known", time, false)),
                Map.of("name", new ConflictResolver.ExistingValue("Unknown", "unlisted", time.plusSeconds(1), false)));
        assertEquals("Known", result.accepted().get("name"));
    }

    @Test
    void materializesSourceRecordsThroughStorageTransactions() {
        InMemoryStorageProvider storage = new InMemoryStorageProvider();
        RequestContext context = RequestContext.system("tenant", "sync");
        storage.applySchema(context, new OntologySchema("example", "0.1.0",
                List.of(new ObjectTypeDefinition("Person", List.of(
                        new PropertyDefinition("id", "ID", true, true, true, true, false, true),
                        new PropertyDefinition("name", "String", true, false, false, false, false, false)))),
                List.of(), List.of()));
        Instant now = Instant.now();
        SourceRecord source = new SourceRecord("hr", "p-1", "UPSERT", now,
                Map.of("id", "p-1", "name", "Alice"),
                new Provenance("hr", "p-1", "1", "jdbc", now, "sync", null));
        Connector connector = new Connector() {
            public String name() { return "test"; }
            public Stream<SourceRecord> read(SourceQuery query) { return Stream.of(source); }
        };
        var result = new MaterializedSyncService(storage).withAuthorization((ctx, connectorName, config, target, tx) -> true).sync(connector,
                new SourceQuery("people", Map.of()),
                new MappingConfig("Person", "id", Map.of("id", "id", "name", "name")), context);
        assertEquals(1, result.created());
        assertEquals("Alice", storage.getObject(context, "Person", "p-1").properties().get("name"));
        var replay = new MaterializedSyncService(storage).withAuthorization((ctx, connectorName, config, target, tx) -> true).sync(connector, new SourceQuery("people", Map.of()),
                new MappingConfig("Person", "id", Map.of("id", "id", "name", "name")), context);
        assertTrue(replay.failures().isEmpty());
        assertEquals(0, replay.updated());
        assertEquals(1, storage.getObject(context, "Person", "p-1").version());
    }
}
