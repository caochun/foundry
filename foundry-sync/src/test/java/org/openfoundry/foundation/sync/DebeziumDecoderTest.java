package org.openfoundry.foundation.sync;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DebeziumDecoderTest {
    static final DebeziumDecoder.Binding BINDING = new DebeziumDecoder.Binding("dbserver.public.people", "db", "public", "people");
    static final String SOURCE = "{\"db\":\"db\",\"schema\":\"public\",\"table\":\"people\",\"ts_ms\":1767225600000}";

    @Test
    void decodesCreateUpdateDeleteAndStablePosition() {
        var decoder = new DebeziumDecoder("People", "id", BINDING);
        var create = decoder.decode(message(4, "{\"id\":\"p1\"}", "{\"op\":\"c\",\"source\":" + SOURCE + ",\"after\":{\"id\":\"p1\",\"name\":\"A\"}}"));
        assertFalse(create.tombstone());
        assertEquals("INSERT", create.record().operation());
        assertEquals("p1", create.record().sourceRecordId());
        assertEquals("cdc_", create.record().position().partition().substring(0, 4));
        assertEquals(4, create.record().position().sequence());
        var update = decoder.decode(message(5, "{\"id\":\"p1\"}", "{\"op\":\"u\",\"source\":" + SOURCE + ",\"before\":{\"id\":\"p1\"},\"after\":{\"id\":\"p1\",\"name\":\"B\"}}"));
        assertEquals("UPDATE", update.record().operation());
        var delete = decoder.decode(message(6, "{\"id\":\"p1\"}", "{\"op\":\"d\",\"source\":" + SOURCE + ",\"before\":{\"id\":\"p1\"},\"after\":null}"));
        assertEquals("DELETE", delete.record().operation());
        assertEquals(Map.of("id", "p1"), delete.record().data());
        assertNotEquals(create.record().position().eventId(), update.record().position().eventId());
    }

    @Test
    void decodesSnapshotReadAndTombstoneWithoutCreatingADelete() {
        var decoder = new DebeziumDecoder("People", "id", BINDING);
        var read = decoder.decode(message(1, "{\"id\":7}", "{\"schema\":null,\"payload\":{\"op\":\"r\",\"source\":" + SOURCE + ",\"after\":{\"id\":7,\"name\":\"A\"}}}"));
        assertEquals("UPSERT", read.record().operation());
        assertEquals("7", read.record().sourceRecordId());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), read.record().provenance().producedAt());
        assertTrue(decoder.decode(message(2, "{\"id\":7}", null)).tombstone());
    }

    @Test
    void decodesConnectDecimalTimeAndNestedStructExactly() {
        var decoder = new DebeziumDecoder("People", "id", BINDING);
        String schema = "{\"type\":\"struct\",\"fields\":[{\"field\":\"id\",\"type\":\"string\"},{\"field\":\"amount\",\"type\":\"bytes\",\"name\":\"org.apache.kafka.connect.data.Decimal\",\"parameters\":{\"scale\":\"4\"}},{\"field\":\"dob\",\"type\":\"int32\",\"name\":\"io.debezium.time.Date\"}],\"optional\":false}";
        String envelope = "{\"schema\":null,\"payload\":{\"op\":\"c\",\"source\":" + SOURCE + ",\"after\":{\"id\":\"x\",\"amount\":\"AeJA\",\"dob\":20454}}}";
        var result = decoder.decode(message(1, "{\"id\":\"x\"}", "{\"schema\":" + schema + ",\"payload\":{\"op\":\"c\",\"source\":" + SOURCE + ",\"after\":{\"id\":\"x\",\"amount\":\"AeJA\",\"dob\":20454}}}"));
        assertEquals("12.3456", result.record().data().get("amount").toString());
        assertEquals("2026-01-01", result.record().data().get("dob"));
    }

    @Test
    void rejectsWrongTopicSourceKeyMismatchDuplicateAndUnsafeValues() {
        var decoder = new DebeziumDecoder("People", "id", BINDING);
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(new CdcTransport.Message("other", 0, 1, Instant.now(), "{}", "{}")));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(message(1, "{\"id\":\"p1\"}", "{\"op\":\"c\",\"source\":{\"db\":\"db\",\"schema\":\"public\",\"table\":\"other\"},\"after\":{\"id\":\"p1\"}}")));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(message(1, "{\"id\":\"p1\"}", "{\"op\":\"c\",\"source\":" + SOURCE + ",\"after\":{\"id\":\"p2\"}}")));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(message(1, "{\"id\":\"p1\",\"id\":\"p2\"}", "{}")));
        assertThrows(IllegalArgumentException.class, () -> decoder.decode(message(1, "{\"id\":\"p1\"}", "{\"op\":\"c\",\"source\":" + SOURCE + ",\"after\":{\"id\":\"p1\"}} trailing")));
    }

    static CdcTransport.Message message(long offset, String key, String value) {
        return new CdcTransport.Message(BINDING.topic(), 0, offset, Instant.parse("2026-01-01T00:00:00Z"), key, value);
    }
}
