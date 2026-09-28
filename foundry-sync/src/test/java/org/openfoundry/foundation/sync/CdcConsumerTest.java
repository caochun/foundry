package org.openfoundry.foundation.sync;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;
import org.openfoundry.foundation.spi.schema.OntologySchema;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.openfoundry.foundation.sync.DebeziumDecoderTest.*;

class CdcConsumerTest {
    static final RequestContext CTX = RequestContext.system("tenant", "cdc");
    static final OntologySchema SCHEMA = new OdlParser().parse("extend schema @namespace(name:\"cdc\",version:\"1\") type Person @objectType { id: ID! @primary name: String! }");
    static final DatasourceMapping MAPPING = new MappingConfigParser().parse("""
            datasource: People
            connector: kafka
            connection: {url: '${KAFKA}', table: people}
            mapping:
              objectType: Person
              primaryKey: {source: id, target: id}
              properties: {name: {source: name}}
            sync: {mode: CDC, conflictResolution: ACTION_PRIORITY}
            """);
    static final DebeziumDecoder.Binding BINDING = new DebeziumDecoder.Binding("dbserver.public.people", "db", "public", "people");
    static final SyncAuthorizer ALLOW = (context, connector, mapping, target, tx) -> true;

    @Test
    void appliesEventsAndAcknowledgesOnlyAfterCommit() {
        var storage = new InMemoryStorageProvider();
        storage.applySchema(CTX, SCHEMA);
        var transport = new FakeTransport(List.of(
                DebeziumDecoderTest.message(1, "{\"id\":\"p1\"}", "{\"op\":\"c\",\"source\":" + SOURCE + ",\"after\":{\"id\":\"p1\",\"name\":\"A\"}}"),
                DebeziumDecoderTest.message(2, "{\"id\":\"p2\"}", "{\"op\":\"c\",\"source\":" + SOURCE + ",\"after\":{\"id\":\"p2\",\"name\":\"B\"}}"),
                DebeziumDecoderTest.message(3, "{\"id\":\"p2\"}", null)));
        try (var consumer = new CdcConsumer(storage, MAPPING, BINDING, ALLOW)) {
            var result = consumer.consume(transport, CTX);
            assertTrue(result.failures().isEmpty(), result.failures().toString());
            assertEquals(2, result.created());
            assertEquals(1, consumer.stats().tombstones());
            assertEquals(3, transport.acknowledged.size());
            assertEquals(1, storage.getObject(CTX, "Person", "p2").version());
            assertEquals(2, consumer.checkpoint("broker-1", 0, CTX).sequence());
        }
    }

    @Test
    void stopsAtFailureAndRedeliveryReplaysCommittedFirstEventWithoutSkipping() {
        var storage = new InMemoryStorageProvider();
        storage.applySchema(CTX, SCHEMA);
        var failing = new FakeTransport(List.of(
                DebeziumDecoderTest.message(1, "{\"id\":\"p1\"}", "{\"op\":\"c\",\"source\":" + SOURCE + ",\"after\":{\"id\":\"p1\",\"name\":\"A\"}}"),
                DebeziumDecoderTest.message(2, "{\"id\":\"p2\"}", "{\"op\":\"c\",\"source\":" + SOURCE + ",\"after\":{\"id\":\"p2\",\"name\":null}}"),
                DebeziumDecoderTest.message(3, "{\"id\":\"p3\"}", "{\"op\":\"c\",\"source\":" + SOURCE + ",\"after\":{\"id\":\"p3\",\"name\":\"C\"}}")));
        try (var consumer = new CdcConsumer(storage, MAPPING, BINDING, ALLOW)) {
            var first = consumer.consume(failing, CTX);
            assertEquals(1, first.created());
            assertEquals(1, first.failures().size());
            assertEquals(List.of(1L), failing.acknowledged.stream().map(CdcTransport.Message::offset).toList());
            assertEquals(1, consumer.checkpoint("broker-1", 0, CTX).sequence());
        }
        var retry = new FakeTransport(List.of(
                DebeziumDecoderTest.message(2, "{\"id\":\"p2\"}", "{\"op\":\"c\",\"source\":" + SOURCE + ",\"after\":{\"id\":\"p2\",\"name\":\"B\"}}"),
                DebeziumDecoderTest.message(3, "{\"id\":\"p3\"}", "{\"op\":\"c\",\"source\":" + SOURCE + ",\"after\":{\"id\":\"p3\",\"name\":\"C\"}}")));
        try (var consumer = new CdcConsumer(storage, MAPPING, BINDING, ALLOW)) {
            var result = consumer.consume(retry, CTX);
            assertTrue(result.failures().isEmpty(), result.failures().toString());
            assertEquals(2, result.created());
            assertEquals(List.of(2L, 3L), retry.acknowledged.stream().map(CdcTransport.Message::offset).toList());
        }
    }

    @Test
    void authorizationOrMappingFailureDoesNotAcknowledgeAndCanBeClosed() throws Exception {
        var storage = new InMemoryStorageProvider();
        storage.applySchema(CTX, SCHEMA);
        var denied = new FakeTransport(List.of(DebeziumDecoderTest.message(1, "{\"id\":\"p1\"}", "{\"op\":\"c\",\"source\":" + SOURCE + ",\"after\":{\"id\":\"p1\",\"name\":\"A\"}}")));
        try (var consumer = new CdcConsumer(storage, MAPPING, BINDING, (context, connector, mapping, target, tx) -> target == null)) {
            var result = consumer.consume(denied, CTX);
            assertEquals(0, result.created());
            assertEquals(1, result.failures().size());
            assertEquals(0, denied.acknowledged.size());
            assertEquals("FAILED", consumer.stats().status());
        }
        var paused = new FakeTransport(List.of(DebeziumDecoderTest.message(1, "{\"id\":\"p1\"}", "{\"op\":\"c\",\"source\":" + SOURCE + ",\"after\":{\"id\":\"p1\",\"name\":\"A\"}}")));
        try (var consumer = new CdcConsumer(storage, MAPPING, BINDING, ALLOW)) {
            consumer.pause();
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var future = executor.submit(() -> consumer.consume(paused, CTX));
            Thread.sleep(50);
            assertFalse(future.isDone());
            consumer.resume();
            assertTrue(future.get(2, TimeUnit.SECONDS).failures().isEmpty());
            executor.close();
        }
    }

    static final class FakeTransport implements CdcTransport {
        final List<CdcTransport.Message> messages;
        final List<CdcTransport.Message> acknowledged = new ArrayList<>();
        FakeTransport(List<CdcTransport.Message> messages) { this.messages = new ArrayList<>(messages); }
        public String identity() { return "broker-1"; }
        public Set<Integer> partitions() { return Set.of(0); }
        public Session open(Map<Integer, Long> offsets) {
            long after = offsets.getOrDefault(0, -1L);
            var pending = messages.stream().filter(message -> message.offset() > after).iterator();
            return new Session() {
                CdcTransport.Message current;
                public CdcTransport.Message next() { if (current != null) throw new IllegalStateException(); if (!pending.hasNext()) return null; return current = pending.next(); }
                public void acknowledge(CdcTransport.Message message) { if (current == null || !current.equals(message)) throw new IllegalArgumentException(); acknowledged.add(message); current = null; }
                public void close() { current = null; }
            };
        }
    }
}
