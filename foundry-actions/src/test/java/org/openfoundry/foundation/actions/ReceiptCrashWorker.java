package org.openfoundry.foundation.actions;

import org.h2.jdbcx.JdbcDataSource;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.schema.*;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** A real child process used to stop the JVM before/after the physical command commit. */
public final class ReceiptCrashWorker {
    static final RequestContext CONTEXT = RequestContext.system("tenant", "operator");
    static final ActionActor ACTOR = new ActionActor("operator", Set.of());
    static final ActionTypeDefinition DEFINITION = new ActionTypeDefinition("Create", List.of(new ActionParameter("name", "String", true)), "can_create");
    static final ActionManifest MANIFEST = new ActionManifest("Create", 1, false, List.of(),
            List.of(new ActionManifest.CreateObject("Item", "generated", Map.of("name", "params.name"))));
    static final OntologySchema SCHEMA = new OntologySchema("receipts", "0.1.0", List.of(new ObjectTypeDefinition("Item", List.of(
            new PropertyDefinition("id", "ID", true, true, false, false, false, true),
            new PropertyDefinition("name", "String", true, false, false, false, false, false)))), List.of(), List.of(DEFINITION));

    public static void main(String[] args) throws Exception {
        var delegate = new JdbcDataSource();
        delegate.setURL(args[0]);
        var armed = new AtomicBoolean(false);
        DataSource wrapped = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, parameters) -> {
            try {
                Object value = method.invoke(delegate, parameters);
                if (value instanceof Connection connection) {
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (ignored, operation, values) -> {
                        if (operation.getName().equals("commit") && armed.get() && args[1].equals("before")) Runtime.getRuntime().halt(41);
                        try {
                            Object result = operation.invoke(connection, values);
                            if (operation.getName().equals("commit") && armed.get() && args[1].equals("after")) Runtime.getRuntime().halt(42);
                            return result;
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
                }
                return value;
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        var storage = new JdbcStorageProvider(wrapped, DatabaseDialect.h2());
        storage.applySchema(CONTEXT, SCHEMA);
        armed.set(true);
        if (args.length == 4) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(args[2]), "ready");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
            while (!java.nio.file.Files.exists(java.nio.file.Path.of(args[3])) && System.nanoTime() < deadline) Thread.sleep(10);
            if (!java.nio.file.Files.exists(java.nio.file.Path.of(args[3]))) throw new IllegalStateException("Command barrier was not released");
        }
        var result = executor().execute(MANIFEST, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Crash fixture"), "crash-key", storage);
        if (args[1].equals("normal")) {
            System.out.println("RESULT:" + result.actionId());
            return;
        }
        throw new IllegalStateException("Crash injection did not execute");
    }

    static ActionExecutor executor() {
        return new ActionExecutor(ExpressionEvaluator.simple(), null, (context, actor, definition, values) -> true);
    }
}
