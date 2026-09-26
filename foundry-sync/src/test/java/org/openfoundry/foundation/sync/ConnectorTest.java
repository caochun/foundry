package org.openfoundry.foundation.sync;

import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectorTest {
    @Test
    void readsJdbcRowsAsSourceRecords() throws Exception {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:source_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE people (id VARCHAR(20), name VARCHAR(50))");
            statement.execute("INSERT INTO people VALUES ('p-1', 'Alice')");
        }
        var connector = new JdbcConnector("jdbc", "hr", source);
        SourceRecord record = connector.read(new SourceQuery("SELECT id, name FROM people", java.util.Map.of())).findFirst().orElseThrow();
        assertEquals("p-1", record.sourceRecordId());
        assertEquals("Alice", record.data().get("NAME"));
    }

    @Test
    void readsRestJsonAsSourceRecords() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/people", exchange -> {
            byte[] body = "[{\"id\":\"p-1\",\"name\":\"Alice\"}]".getBytes();
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var connector = new RestConnector("rest", "directory");
            SourceRecord record = connector.read(new SourceQuery("http://localhost:" + server.getAddress().getPort() + "/people", java.util.Map.of())).findFirst().orElseThrow();
            assertEquals("p-1", record.sourceRecordId());
        } finally { server.stop(0); }
    }
}
