package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.openfoundry.foundation.sync.RestDatasourceTest.*;
import static org.openfoundry.foundation.sync.RestSourceConnectorTest.*;

public final class RestCrashWorker {
    public static void main(String[] arguments) throws Exception {
        var target = data(arguments[0]);
        var armed = new AtomicBoolean();
        var wrapped = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, values) -> {
            try {
                Object result = method.invoke(target, values);
                if (!(result instanceof Connection connection)) return result;
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (ignored, operation, parameters) -> {
                    boolean commit = false;
                    if (armed.get() && operation.getName().equals("commit")) {
                        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM of_ingestion_receipts")) {
                            commit = rows.next() && rows.getLong(1) > 0;
                        }
                    }
                    if (commit && arguments[2].equals("before")) Runtime.getRuntime().halt(111);
                    try {
                        Object outcome = operation.invoke(connection, parameters);
                        if (commit && arguments[2].equals("after")) Runtime.getRuntime().halt(112);
                        return outcome;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        try (var storage = new JdbcStorageProvider(wrapped, DatabaseDialect.h2())) {
            storage.applySchema(CTX, SCHEMA);
            var definition = new DatasourceMapping("People", "rest", new DatasourceMapping.Connection(arguments[1], "people", INCREMENTAL),
                    new MappingConfig("Person", new KeyMapping("id", "id", null), Map.of("name", new PropertyMapping("name")), java.util.List.of()),
                    new DatasourceMapping.Sync(DatasourceMapping.Mode.POLLING, null, ConflictResolver.Strategy.SOURCE_PRIORITY, null, null, null, false));
            armed.set(true);
            var result = new DatasourceRunner(storage, ConnectorRegistry.rest(), ALLOW).runOnce(definition, CTX, OPTIONS);
            throw new IllegalStateException("Crash boundary not reached: " + result);
        }
    }
}
