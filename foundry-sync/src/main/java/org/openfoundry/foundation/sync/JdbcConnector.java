package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.Provenance;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Read-only JDBC connector. The query is supplied by a trusted connector configuration. */
public final class JdbcConnector implements Connector {
    private final String name;
    private final String sourceSystem;
    private final DataSource dataSource;

    public JdbcConnector(String name, String sourceSystem, DataSource dataSource) {
        this.name = name;
        this.sourceSystem = sourceSystem;
        this.dataSource = dataSource;
    }

    @Override
    public String name() { return name; }

    @Override
    public Stream<SourceRecord> read(SourceQuery query) {
        try (Connection connection = dataSource.getConnection();
             var statement = connection.prepareStatement(query.resource());
             ResultSet result = statement.executeQuery()) {
            List<SourceRecord> records = new ArrayList<>();
            ResultSetMetaData metadata = result.getMetaData();
            while (result.next()) {
                Map<String, Object> data = new LinkedHashMap<>();
                for (int i = 1; i <= metadata.getColumnCount(); i++) data.put(metadata.getColumnLabel(i), result.getObject(i));
                Object sourceId = data.values().stream().findFirst().orElse(null);
                if (sourceId == null) throw new IllegalStateException("JDBC source row has no first-column identity");
                Instant observed = Instant.now();
                records.add(new SourceRecord(sourceSystem, String.valueOf(sourceId), "UPSERT", observed, data,
                        new Provenance(sourceSystem, String.valueOf(sourceId), null, "jdbc", observed, name, null)));
            }
            return records.stream();
        } catch (Exception exception) {
            throw new IllegalStateException("JDBC connector failed: " + name, exception);
        }
    }
}
