package org.openfoundry.foundation.storage.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LegacyTemporalHistoryTest {
    private static final RequestContext CONTEXT = RequestContext.system("tenant", "upgrade-test");
    private static final Instant CREATED = Instant.parse("2030-01-01T00:00:00Z");
    private static final Instant UPGRADED = CREATED.plusSeconds(10);
    private static final EntityKey PERSON = new EntityKey("Person", "p");
    private static final EntityKey ORG = new EntityKey("Organization", "a");
    private static final PropertyDefinition ID = new PropertyDefinition("id", "ID", true, true, true, true, false, true);
    private static final OntologySchema SCHEMA = new OntologySchema("legacy", "0.1.0",
            List.of(new ObjectTypeDefinition("Person", List.of(ID)), new ObjectTypeDefinition("Organization", List.of(ID))),
            List.of(new LinkTypeDefinition("BelongsTo", "Person", "Organization", Cardinality.MANY_TO_ONE, List.of(ID))), List.of());

    @Test
    void upgradePreservesRawHistoryAndCurrentOperationsButDoesNotGuessOldValidTimes() throws Exception {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:legacy_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        var original = provider(data, CREATED);
        original.applySchema(CONTEXT, SCHEMA);
        try (var tx = original.beginTransaction(CONTEXT)) {
            tx.createObject("Person", "p", Map.of("name", "before"));
            tx.createObject("Organization", "a", Map.of());
            tx.createLink("BelongsTo", "edge", PERSON, ORG, Map.of());
            tx.commit();
        }
        var originalHistory = original.getEntityHistory(CONTEXT, PERSON);
        var originalLink = original.getEntityHistory(CONTEXT, new EntityKey("BelongsTo", "edge"));
        try (var connection = data.getConnection(); var sql = connection.createStatement()) {
            // Reproduce the pre-upgrade table shape, with no format discriminator.
            sql.execute("ALTER TABLE of_object_history DROP COLUMN temporal_format");
            sql.execute("ALTER TABLE of_link_history DROP COLUMN temporal_format");
        }
        var upgraded = provider(data, UPGRADED);
        upgraded.applySchema(CONTEXT, SCHEMA);
        upgraded.applySchema(CONTEXT, SCHEMA);
        assertEquals(originalHistory, upgraded.getEntityHistory(CONTEXT, PERSON));
        assertEquals(originalLink, upgraded.getEntityHistory(CONTEXT, new EntityKey("BelongsTo", "edge")));
        assertEquals("before", upgraded.getObjectAtVersion(CONTEXT, "Person", "p", 1).state().get("name"));
        var error = assertThrows(TemporalHistoryUnavailableException.class,
                () -> upgraded.getObjectAtTime(CONTEXT, "Person", "p", CREATED, UPGRADED));
        assertEquals("TEMPORAL_HISTORY_MIGRATION_REQUIRED", error.code());
        assertThrows(TemporalHistoryUnavailableException.class,
                () -> upgraded.getLinkAtTime(CONTEXT, "BelongsTo", "edge", CREATED, UPGRADED));
        assertThrows(TemporalHistoryUnavailableException.class,
                () -> upgraded.queryObjects(CONTEXT, "Person", new QueryOptions(10, 0, CREATED, UPGRADED, false)));
        try (var tx = upgraded.beginTransaction(CONTEXT)) {
            tx.updateObject("Person", "p", Map.of("name", "after"), 1);
            tx.deleteLink("BelongsTo", "edge", 1);
            tx.createLink("BelongsTo", "new-edge", PERSON, ORG, Map.of());
            tx.createObject("Person", "new", Map.of("name", "new"));
            tx.commit();
        }
        assertEquals("after", upgraded.getObject(CONTEXT, "Person", "p").properties().get("name"));
        assertEquals("new", upgraded.getObjectAtTime(CONTEXT, "Person", "new", UPGRADED, UPGRADED).state().get("name"));
        assertEquals(2, upgraded.getEntityHistory(CONTEXT, PERSON).size());
        assertEquals(originalHistory.getFirst(), upgraded.getEntityHistory(CONTEXT, PERSON).getFirst());
        try (var tx = upgraded.beginTransaction(CONTEXT)) {
            assertThrows(TemporalHistoryUnavailableException.class, () -> tx.updateObject("Person", "p", Map.of("name", "guessed"), 2, UPGRADED));
        }
        assertEquals("after", upgraded.getObject(CONTEXT, "Person", "p").properties().get("name"));
        try (var connection = data.getConnection(); var sql = connection.createStatement();
             var rows = sql.executeQuery("SELECT temporal_format FROM of_object_history WHERE object_type='Person' AND object_id='p' ORDER BY version")) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1));
            assertTrue(rows.next());
            assertEquals(2, rows.getInt(1));
        }
    }

    @Test
    void newTemporalRowsSurviveProviderRestartWithDeletionAndEarlierQueriesIntact() {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:restart_time_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        var first = provider(data, CREATED);
        first.applySchema(CONTEXT, SCHEMA);
        try (var tx = first.beginTransaction(CONTEXT)) {
            tx.createObject("Person", "p", Map.of("name", "before"));
            tx.createObject("Organization", "a", Map.of());
            tx.createLink("BelongsTo", "edge", PERSON, ORG, Map.of());
            tx.commit();
        }
        var second = provider(data, UPGRADED);
        second.applySchema(CONTEXT, SCHEMA);
        try (var tx = second.beginTransaction(CONTEXT)) {
            tx.deleteLink("BelongsTo", "edge", 1, CREATED.plusSeconds(5));
            tx.updateObject("Person", "p", Map.of("name", "late"), 1, CREATED.plusSeconds(5));
            tx.commit();
        }
        var third = provider(data, UPGRADED.plusSeconds(5));
        third.applySchema(CONTEXT, SCHEMA);
        assertEquals(EntityOperation.DELETED, third.getLinkAtTime(CONTEXT, "BelongsTo", "edge", UPGRADED, UPGRADED).operation());
        assertEquals("before", third.getObjectAtTime(CONTEXT, "Person", "p", CREATED, UPGRADED).state().get("name"));
        assertEquals("late", third.getObjectAtTime(CONTEXT, "Person", "p", CREATED.plusSeconds(5), UPGRADED).state().get("name"));
        assertEquals("before", third.getObjectAtTime(CONTEXT, "Person", "p", UPGRADED, CREATED).state().get("name"));
    }

    private JdbcStorageProvider provider(JdbcDataSource data, Instant now) {
        return new JdbcStorageProvider(data, DatabaseDialect.h2(), Clock.fixed(now, ZoneOffset.UTC));
    }
}
