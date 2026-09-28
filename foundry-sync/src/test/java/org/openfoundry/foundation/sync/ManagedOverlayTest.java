package org.openfoundry.foundation.sync;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.openfoundry.foundation.sync.OverlayEngineTest.MAPPING;
import static org.openfoundry.foundation.sync.JdbcSourceConnectorTest.execute;

class ManagedOverlayTest {
    static final String MAPPING_YAML_JDBC = """
            datasource: People
            connector: jdbc
            connection: {url: x, table: people}
            mapping:
              objectType: Person
              primaryKey: {source: id, target: id, transform: "prefix('person-')"}
              properties: {name: {source: name}, age: {source: age}}
            sync: {mode: OVERLAY, cacheStrategy: TTL, cacheTTL: PT5M, writeback: false}
            """;

    @Test
    void usesManagedJdbcReadThroughAndDoesNotMaterializeFacts() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:overlay_" + System.nanoTime() + ";DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        execute(source, "CREATE TABLE people (id BIGINT PRIMARY KEY, name VARCHAR, updated_at TIMESTAMP NOT NULL)");
        execute(source, "INSERT INTO people VALUES (1,'Alice',TIMESTAMP '2026-01-01 00:00:00')");
        try (var managed = new ManagedOverlay(new MappingConfigParser().parse(MAPPING_YAML_JDBC), ConnectorRegistry.jdbc(connection -> source))) {
            var object = managed.get(Map.of("id", 1));
            assertEquals("person-1", object.id());
            assertEquals("Alice", object.properties().get("name"));
            assertEquals(1, managed.cacheSize());
        }
    }

    @Test
    void rejectsWritebackAndUnknownConnectorBeforeOpeningSource() {
        var registry = new ConnectorRegistry();
        assertThrows(IllegalArgumentException.class, () -> new ManagedOverlay(MAPPING, registry));
        var writeback = new MappingConfigParser().parse(MAPPING_YAML_JDBC.replace("writeback: false", "writeback: true"));
        assertThrows(UnsupportedOperationException.class, () -> new ManagedOverlay(writeback, registry));
    }
}
