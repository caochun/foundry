package org.openfoundry.foundation.sync;

import org.h2.jdbcx.JdbcDataSource;
import org.openfoundry.foundation.spi.RequestContext;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.openfoundry.foundation.sync.MaterializedIngestionTest.*;

/** Independent process terminated at the physical ingestion commit boundary. */
public final class IngestionCrashWorker {
    public static void main(String[] args) throws Exception {
        var source = data(args[0]);
        String mode = args[1];
        var armed = new AtomicBoolean();
        var wrapped = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, values) -> {
            try {
                Object result = method.invoke(source, values);
                if (!(result instanceof Connection connection)) return result;
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (ignored, operation, parameters) -> {
                    boolean commit = false;
                    if (armed.get() && operation.getName().equals("commit")) {
                        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM of_ingestion_receipts")) {
                            commit = rows.next() && rows.getLong(1) > 0;
                        }
                    }
                    if (commit && mode.equals("before")) Runtime.getRuntime().halt(81);
                    try {
                        Object outcome = operation.invoke(connection, parameters);
                        if (commit && mode.equals("after")) Runtime.getRuntime().halt(82);
                        return outcome;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        var f = fixture(wrapped);
        if (args.length > 3) {
            Files.writeString(Path.of(args[3]), "ready");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(Path.of(args[4])) && System.nanoTime() < deadline) Thread.sleep(10);
            if (!Files.exists(Path.of(args[4]))) throw new IllegalStateException("Barrier was not released");
        }
        armed.set(true);
        String actor = args.length > 2 ? args[2] : "worker";
        var result = f.service.sync(connector("hr", List.of(record("hr", "a", 1, Map.of("name", "Durable")))),
                new SourceQuery("people", Map.of()), MAPPING, RequestContext.system("tenant", actor));
        System.out.println("RESULT:" + result.created() + ":" + result.replayed() + ":" + result.failures().size());
        if (!mode.equals("normal")) throw new IllegalStateException("Crash boundary was not reached");
    }
}
