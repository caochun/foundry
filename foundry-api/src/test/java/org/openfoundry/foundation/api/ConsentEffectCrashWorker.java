package org.openfoundry.foundation.api;

import org.h2.jdbcx.JdbcDataSource;
import org.openfoundry.foundation.actions.*;
import org.openfoundry.foundation.storage.jdbc.*;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.openfoundry.foundation.api.ConsentEffectTest.*;

/** Terminates a separate JVM at actual JDBC commit boundaries, without finally/close. */
public final class ConsentEffectCrashWorker {
    static final Instant START = Instant.parse("2030-01-01T00:00:00Z");
    static final ActionManifest MANIFEST = new ActionManifestParser().parse("action: Register\nversion: 1\neffects:\n" + CREATE + GRANT + """
            sideEffects:
              - name: notify
                type: event
                config: {type: created}
                retries: 1
                retryDelay: PT0S
            rollback: {onSideEffectFailure: ROLLBACK_ALL}
            """);

    public static void main(String[] args) {
        var source = new JdbcDataSource();
        source.setURL(args[0]);
        String mode = args[1];
        var armed = new AtomicBoolean();
        var data = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, values) -> {
            try {
                Object result = method.invoke(source, values);
                if (!(result instanceof Connection connection)) return result;
                return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (ignored, operation, params) -> {
                    boolean boundary = false;
                    if (armed.get() && operation.getName().equals("commit")) {
                        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT status FROM of_action_executions")) {
                            boundary = rows.next() && rows.getString(1).equals(mode.contains("compensation") ? "ROLLED_BACK" : "PENDING");
                        }
                    }
                    if (boundary && mode.startsWith("before")) Runtime.getRuntime().halt(71);
                    try {
                        Object outcome = operation.invoke(connection, params);
                        if (boundary && mode.startsWith("after")) Runtime.getRuntime().halt(72);
                        return outcome;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        var clock = Clock.fixed(START, ZoneOffset.UTC);
        var storage = new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
        storage.applySchema(CONTEXT, SCHEMA);
        var consent = new JdbcConsentStore(data, DatabaseDialect.h2(), clock);
        var executor = new ActionExecutor().withAuthorization((ctx, actor, definition, parameters) -> true)
                .withConsentStore(consent, PURPOSE, Set.of("Person"), Set.of(PURPOSE))
                .withSideEffects(invocation -> { throw new IllegalStateException("delivery failed"); }, clock, Duration.ofSeconds(10));
        armed.set(true);
        executor.execute(MANIFEST, SCHEMA.actionTypes().getFirst(), CONTEXT, ACTOR,
                Map.of("id", "new", "name", "New", "consent", true), "crash", storage);
        throw new IllegalStateException("Requested crash boundary not reached");
    }
}
