package org.openfoundry.foundation.sync;

import javax.sql.DataSource;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.openfoundry.foundation.sync.RelationshipIngestionTest.*;

public final class RelationshipCrashWorker {
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
                    if (commit && mode.equals("before")) Runtime.getRuntime().halt(91);
                    try {
                        Object outcome = operation.invoke(connection, parameters);
                        if (commit && mode.equals("after")) Runtime.getRuntime().halt(92);
                        return outcome;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        var fixture = open(wrapped);
        if (args.length == 4) {
            Files.writeString(Path.of(args[2]), "ready");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(Path.of(args[3])) && System.nanoTime() < deadline) Thread.sleep(10);
            if (!Files.exists(Path.of(args[3]))) throw new IllegalStateException("Barrier not released");
        }
        armed.set(true);
        var result = fixture.run(MAPPING, record(1, "u1", Map.of("note", "Durable")));
        System.out.println("RESULT:" + result.created() + ":" + result.replayed() + ":" + result.relationshipChanges().getOrDefault("created", 0) + ":" + result.failures().size());
        if (!mode.equals("normal")) throw new IllegalStateException("Crash boundary was not reached");
    }
}
