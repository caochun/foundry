package org.openfoundry.foundation.spi;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelInvariantTest {
    @Test
    void rejectsBlankTenant() {
        assertThrows(IllegalArgumentException.class,
                () -> new RequestContext(" ", "actor", null));
    }

    @Test
    void linkValidityUsesHalfOpenInterval() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-02-01T00:00:00Z");
        LinkRecord link = new LinkRecord(
                "tenant", "BelongsTo", "link-1",
                new EntityKey("Person", "p-1"),
                new EntityKey("Organization", "org-1"),
                1, from, from, null, from, to, null, null, Map.of());

        assertTrue(link.isValidAt(from));
        assertTrue(!link.isValidAt(to));
    }

    @Test
    void preservesExplicitNullPropertyValues() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("nickname", null);
        ObjectRecord object = new ObjectRecord(
                "tenant", "Person", "p-1", 1,
                Instant.now(), Instant.now(), null, null, null,
                properties);

        assertTrue(object.properties().containsKey("nickname"));
    }
}
