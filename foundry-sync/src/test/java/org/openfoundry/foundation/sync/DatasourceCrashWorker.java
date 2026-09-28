package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.openfoundry.foundation.sync.DatasourceRunnerTest.*;
import static org.openfoundry.foundation.sync.JdbcSourceConnectorTest.OPTIONS;

public final class DatasourceCrashWorker {
    public static void main(String[] arguments) throws Exception {
        var target = database(arguments[0]);
        var source = database(arguments[1]);
        String mode = arguments[2];
        var armed = new AtomicBoolean();
        var wrapped = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, values) -> {
            try {
                Object result = method.invoke(target, values);
                if (!(result instanceof Connection connection)) {
                    return result;
                }
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (ignored, operation, parameters) -> {
                    boolean commit = false;
                    if (armed.get() && operation.getName().equals("commit")) {
                        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM of_ingestion_receipts")) {
                            commit = rows.next() && rows.getLong(1) > 0;
                        }
                    }
                    if (commit && mode.equals("before")) {
                        Runtime.getRuntime().halt(101);
                    }
                    try {
                        Object outcome = operation.invoke(connection, parameters);
                        if (commit && mode.equals("after")) {
                            Runtime.getRuntime().halt(102);
                        }
                        return outcome;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
        try (var storage = new JdbcStorageProvider(wrapped, DatabaseDialect.h2())) {
            storage.applySchema(CONTEXT, SCHEMA);
            var registry = ConnectorRegistry.jdbc(configuration -> {
                if (arguments.length == 5) {
                    try {
                        // Both runners have loaded the same durable checkpoint before either source starts reading.
                        Files.writeString(Path.of(arguments[3]), "ready");
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                        while (!Files.exists(Path.of(arguments[4])) && System.nanoTime() < deadline) {
                            Thread.sleep(10);
                        }
                        if (!Files.exists(Path.of(arguments[4]))) {
                            throw new IllegalStateException("Source barrier was not released");
                        }
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                }
                return source;
            });
            armed.set(true);
            var result = new DatasourceRunner(storage, registry, ALLOW).runOnce(mapping(), CONTEXT, OPTIONS);
            System.out.println("RESULT:" + result.created() + ":" + result.replayed() + ":" + result.failures().size());
            if (!mode.equals("normal")) {
                throw new IllegalStateException("Crash boundary was not reached");
            }
        }
    }
}
