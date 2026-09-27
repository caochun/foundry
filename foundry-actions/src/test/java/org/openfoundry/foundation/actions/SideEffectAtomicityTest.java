package org.openfoundry.foundation.actions;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.openfoundry.foundation.actions.SideEffectCrashWorker.*;

class SideEffectAtomicityTest {
    @Test
    void failureToPersistContinuationRollsBackBusinessHistoryAndReceiptBeforeAnyCallback() throws Exception {
        var data = new JdbcDataSource();
        data.setURL("jdbc:h2:mem:execution_atomic_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        DataSource faulty = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
            try {
                Object value = method.invoke(data, args);
                if (value instanceof Connection connection) {
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (ignored, operation, parameters) -> {
                        try {
                            Object outcome = operation.invoke(connection, parameters);
                            if (operation.getName().equals("prepareStatement") && parameters[0].toString().startsWith("INSERT INTO of_action_executions")) {
                                var statement = (PreparedStatement) outcome;
                                return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class}, (unused, query, values) -> {
                                    if (query.getName().equals("executeUpdate")) throw new SQLException("Injected continuation failure", "40001");
                                    try { return query.invoke(statement, values); }
                                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                                });
                            }
                            return outcome;
                        } catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
                }
                return value;
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
        var clock = Clock.fixed(START, ZoneOffset.UTC);
        var storage = new JdbcStorageProvider(faulty, DatabaseDialect.h2(), clock);
        storage.applySchema(CONTEXT, SCHEMA);
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "item", Map.of("name", "BEFORE"));
            tx.commit();
        }
        var calls = new AtomicInteger();
        var executor = executor(invocation -> calls.incrementAndGet(), clock);
        assertThrows(IllegalStateException.class, () -> executor.execute(MANIFEST, DEFINITION, CONTEXT, ACTOR,
                Map.of("item", storage.getObject(CONTEXT, "Item", "item")), "key", storage));
        assertEquals(0, calls.get());
        assertEquals(1, storage.getObject(CONTEXT, "Item", "item").version());
        assertEquals("BEFORE", storage.getObject(CONTEXT, "Item", "item").properties().get("name"));
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            for (String table : List.of("of_action_executions", "of_command_receipts", "of_audit_records", "of_outbox_events")) {
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                    rows.next();
                    assertEquals(0, rows.getInt(1), table);
                }
            }
        }
        var recovered = new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
        recovered.applySchema(CONTEXT, SCHEMA);
        assertTrue(executor.execute(MANIFEST, DEFINITION, CONTEXT, ACTOR,
                Map.of("item", recovered.getObject(CONTEXT, "Item", "item")), "key", recovered).success());
        assertEquals(1, calls.get());
    }
}
