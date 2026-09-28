package org.openfoundry.foundation.storage.jdbc;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SchemaActivationWorker {
    public static void main(String[] args) throws Exception {
        var delegate = SchemaRegistryPersistenceTest.data(args[0]);
        String mode = args[1];
        var armed = new AtomicBoolean(false);
        DataSource data = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, values) -> {
            try {
                Object result = method.invoke(delegate, values);
                if (!(result instanceof Connection connection)) return result;
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (ignored, operation, arguments) -> {
                    if (armed.get() && operation.getName().equals("commit") && mode.equals("before")) Runtime.getRuntime().halt(41);
                    try {
                        Object returned = operation.invoke(connection, arguments);
                        if (armed.get() && operation.getName().equals("commit") && mode.equals("after")) Runtime.getRuntime().halt(42);
                        return returned;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        var storage = new JdbcStorageProvider(data, DatabaseDialect.h2());
        storage.applySchema(SchemaActivationTest.CONTEXT, SchemaActivationTest.BASE);
        if (mode.equals("stale")) {
            try (var tx = storage.beginTransaction(SchemaActivationTest.CONTEXT)) {
                java.nio.file.Files.writeString(java.nio.file.Path.of(args[2]), "ready");
                long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
                while (!java.nio.file.Files.exists(java.nio.file.Path.of(args[3])) && System.nanoTime() < deadline) Thread.sleep(10);
                if (!java.nio.file.Files.exists(java.nio.file.Path.of(args[3]))) throw new IllegalStateException("Activation barrier not released");
                tx.createObject("Node", "stale-worker", Map.of("name", "Stale"));
                tx.commit();
            } catch (org.openfoundry.foundation.spi.SchemaVersionMismatchException expected) { System.exit(3); }
            return;
        }
        armed.set(true);
        storage.activateSchema(SchemaActivationTest.CONTEXT, SchemaActivationTest.ADDED, null, 1);
        throw new IllegalStateException("Activation commit crash did not execute");
    }
}
