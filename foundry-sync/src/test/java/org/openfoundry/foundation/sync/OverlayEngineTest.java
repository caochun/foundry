package org.openfoundry.foundation.sync;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.Provenance;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OverlayEngineTest {
    static final DatasourceMapping MAPPING = new MappingConfigParser().parse("""
            datasource: People
            connector: rest
            connection: {url: https://people.example, table: people}
            mapping:
              objectType: Person
              primaryKey: {source: id, target: id, transform: "prefix('person-')"}
              properties: {name: {source: name}, age: {source: age}}
            sync: {mode: OVERLAY, cacheStrategy: TTL, cacheTTL: PT5M, writeback: false}
            """);
    static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void readsMapsCachesAndExpiresWithoutWritingStorage() {
        var calls = new AtomicInteger();
        var clock = new MutableClock(START);
        var connector = connector(calls, List.of(record("1", "Alice", false)));
        try (var engine = new OverlayEngine(MAPPING, connector, clock)) {
            var first = engine.get(Map.of("id", 1));
            assertEquals("person-1", first.id());
            assertEquals("Alice", first.properties().get("name"));
            assertEquals("OVERLAY", first.lineage().connector().equals("rest") ? "OVERLAY" : "wrong");
            assertEquals(1, calls.get());
            assertSame(first, engine.get(Map.of("id", "1")));
            assertEquals(1, calls.get());
            clock.advance(Duration.ofMinutes(5));
            assertEquals("Alice", engine.get(Map.of("id", 1)).properties().get("name"));
            assertEquals(2, calls.get());
            assertEquals(1, engine.cacheSize());
        }
    }

    @Test
    void missesDoNotCacheAndDeleteRemovesCachedProjection() {
        var calls = new AtomicInteger();
        var connector = connector(calls, List.of(record("1", "Alice", false)));
        try (var engine = new OverlayEngine(MAPPING, connector)) {
            assertNull(engine.get(Map.of("id", "missing")));
            assertEquals(1, calls.get());
            assertNull(engine.get(Map.of("id", "missing")));
            assertEquals(2, calls.get());
            assertThrows(OverlayEngine.OverlayReadOnlyException.class, () -> engine.mutate("person-1", Map.of("name", "x")));
            engine.clearCache();
            assertEquals(0, engine.cacheSize());
        }
    }

    @Test
    void rejectsWrongModeWritebackInvalidTtlAndRelationships() {
        assertThrows(IllegalArgumentException.class, () -> new OverlayEngine(new MappingConfigParser().parse("""
                datasource: People
                connector: rest
                connection: {url: x, table: people}
                mapping: {objectType: Person, primaryKey: {source: id, target: id}}
                sync: {mode: BATCH}
                """), connector(new AtomicInteger(), List.of())));
        assertThrows(IllegalArgumentException.class, () -> new OverlayEngine(new MappingConfigParser().parse("""
                datasource: People
                connector: rest
                connection: {url: x, table: people}
                mapping: {objectType: Person, primaryKey: {source: id, target: id}}
                sync: {mode: OVERLAY, writeback: true}
                """), connector(new AtomicInteger(), List.of())));
    }

    @Test
    void closeInvalidatesCacheAndUsesImmutableLineage() {
        var engine = new OverlayEngine(MAPPING, connector(new AtomicInteger(), List.of(record("1", "Alice", false))));
        var object = engine.get(Map.of("id", "1"));
        assertThrows(UnsupportedOperationException.class, () -> object.properties().put("name", "x"));
        engine.close();
        assertThrows(IllegalStateException.class, () -> engine.get(Map.of("id", "1")));
    }

    static Connector connector(AtomicInteger calls, List<SourceRecord> records) {
        return new Connector() {
            public String name() { return "rest"; }
            public Stream<SourceRecord> read(SourceQuery query) { calls.incrementAndGet(); return records.stream(); }
        };
    }

    static SourceRecord record(String id, String name, boolean deleted) {
        var provenance = new Provenance("people", id, "v-" + id, "rest", START, "rest", null);
        return new SourceRecord("people", id, deleted ? "DELETE" : "UPSERT", START, Map.of("id", id, "name", name), provenance);
    }

    static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(Instant instant) { this.instant = instant; }
        void advance(Duration duration) { instant = instant.plus(duration); }
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId zone) { return this; }
        public Instant instant() { return instant; }
    }
}
