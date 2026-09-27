package org.openfoundry.foundation.actions;

import org.h2.jdbcx.JdbcDataSource;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.schema.*;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Independent JVM used at original commit, callback and compensation commit boundaries. */
public final class SideEffectCrashWorker {
    static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    static final RequestContext CONTEXT = RequestContext.system("tenant", "actor");
    static final ActionActor ACTOR = new ActionActor("actor", Set.of("admin"));
    static final ActionTypeDefinition DEFINITION = new ActionTypeDefinition("Work", List.of(new ActionParameter("item", "Item", true)), "can_work");
    static final OntologySchema SCHEMA = new OntologySchema("continuations", "1", List.of(new ObjectTypeDefinition("Item", List.of(
            new PropertyDefinition("id", "ID", true, true, false, false, false, true),
            new PropertyDefinition("name", "String", true, false, false, false, false, false)))), List.of(), List.of(DEFINITION));
    static final ActionManifest MANIFEST = new ActionManifest("Work", 1, false, List.of(),
            List.of(new ActionManifest.UpdateObject("item", Map.of("name", "AFTER"))), ActionManifest.RollbackPolicy.ROLLBACK_ALL,
            List.of(new ActionManifest.SideEffect("notify", "event", Map.of("type", "example.changed", "data", Map.of("id", "item.id")), 1, Duration.ZERO)));

    public static void main(String[] args) throws Exception {
        var data = new JdbcDataSource();
        data.setURL(args[0]);
        String mode = args[1];
        var armed = new AtomicBoolean();
        DataSource wrapped = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, values) -> {
            try {
                Object result = method.invoke(data, values);
                if (result instanceof Connection connection) {
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (ignored, operation, params) -> {
                        boolean committing = operation.getName().equals("commit") && armed.get();
                        boolean rollback = false;
                        if (committing && mode.contains("compensation")) {
                            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT status FROM of_action_executions")) {
                                rollback = rows.next() && rows.getString(1).equals("ROLLED_BACK");
                            }
                        }
                        if (rollback && mode.equals("during-compensation")) Runtime.getRuntime().halt(43);
                        try {
                            Object outcome = operation.invoke(connection, params);
                            if (committing && mode.equals("after-effects")) Runtime.getRuntime().halt(41);
                            if (rollback && mode.equals("after-compensation")) Runtime.getRuntime().halt(44);
                            return outcome;
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
                }
                return result;
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        Clock clock = Clock.fixed(START, ZoneOffset.UTC);
        var storage = new JdbcStorageProvider(wrapped, DatabaseDialect.h2(), clock);
        storage.applySchema(CONTEXT, SCHEMA);
        if (args.length == 5) {
            Files.writeString(Path.of(args[3]), "ready");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
            while (!Files.exists(Path.of(args[4])) && System.nanoTime() < deadline) Thread.sleep(10);
            if (!Files.exists(Path.of(args[4]))) throw new IllegalStateException("Barrier not released");
        }
        armed.set(true);
        SideEffectHandler handler = invocation -> {
            if (mode.contains("compensation")) throw new IllegalStateException("destination rejected");
            try { Files.writeString(Path.of(args[2]), invocation.idempotencyKey() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            if (mode.equals("after-callback")) Runtime.getRuntime().halt(42);
        };
        var result = executor(handler, clock).execute(MANIFEST, DEFINITION, CONTEXT, ACTOR,
                Map.of("item", storage.getObject(CONTEXT, "Item", "item")), "key", storage);
        System.out.println("RESULT:" + result.actionId() + ":" + result.status());
        if (!mode.equals("normal")) throw new IllegalStateException("Crash boundary was not reached");
    }

    static ActionExecutor executor(SideEffectHandler handler, Clock clock) {
        return new ActionExecutor().withAuthorization((context, actor, definition, values) -> true)
                .withSideEffects(handler, clock, Duration.ofSeconds(10));
    }
}
