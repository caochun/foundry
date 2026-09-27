package org.openfoundry.foundation.storage.jdbc;

import org.h2.jdbcx.JdbcDataSource;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.schema.SchemaVersionConflictException;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;

/** Isolated process used for physical commit crashes and cross-JVM registry contention. */
public final class SchemaRegistryWorker {
    static final String ODL = """
            extend schema @namespace(name: "process-registry", version: "1.0.0")
            type Item @objectType { id: ID! @primary name: String }
            """;

    public static void main(String[] args) throws Exception {
        var delegate = new JdbcDataSource();
        delegate.setURL(args[0]);
        delegate.setUser("sa");
        String mode = args[1];
        DataSource data = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, values) -> {
            try {
                Object result = method.invoke(delegate, values);
                if (!(result instanceof Connection connection)) return result;
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (ignored, operation, arguments) -> {
                    if (operation.getName().equals("commit") && mode.equals("before")) Runtime.getRuntime().halt(41);
                    try {
                        Object returned = operation.invoke(connection, arguments);
                        if (operation.getName().equals("commit") && mode.equals("after")) Runtime.getRuntime().halt(42);
                        return returned;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        if (args.length == 4) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(args[2]), "ready");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
            while (!java.nio.file.Files.exists(java.nio.file.Path.of(args[3])) && System.nanoTime() < deadline) Thread.sleep(10);
            if (!java.nio.file.Files.exists(java.nio.file.Path.of(args[3]))) throw new IllegalStateException("Registry race barrier was not released");
        }
        var registry = new JdbcSchemaRegistry(data, DatabaseDialect.h2());
        try {
            registry.apply(new OdlParser().parse(ODL), null, 0);
            if (!mode.equals("race")) throw new IllegalStateException("Commit crash injection did not execute");
        } catch (SchemaVersionConflictException conflict) {
            if (!mode.equals("race")) throw conflict;
            System.exit(3);
        }
    }
}
