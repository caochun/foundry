package org.openfoundry.foundation.actions;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.ActionParameter;
import org.openfoundry.foundation.spi.schema.ActionTypeDefinition;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.openfoundry.foundation.actions.ReceiptCrashWorker.*;

class TransactionalReceiptTest {
    @TempDir Path temporary;

    @Test
    void fileDatabaseReplaysOriginalGeneratedIdentityAfterProviderAndExecutorRestart() {
        var data = data("jdbc:h2:file:" + temporary.resolve("restart"));
        var first = provider(data);
        var result = executor().execute(MANIFEST, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Saved"), "key", first);
        var restarted = provider(data);
        var replay = executor().execute(MANIFEST, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Saved"), "key", restarted);
        assertEquals(result, replay);
        assertEquals(1, restarted.queryObjects(CONTEXT, "Item", QueryOptions.defaults()).size());
        assertEquals(1, count(data, "of_command_receipts"));
        assertEquals(1, count(data, "of_audit_records"));
        assertEquals(1, count(data, "of_outbox_events"));
        assertThrows(IllegalArgumentException.class, () -> executor().execute(MANIFEST, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Different"), "key", restarted));
    }

    @Test
    void concurrentExecutorsAndProvidersCommitOneCommand() throws Exception {
        var data = data("jdbc:h2:mem:command_concurrent;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        javax.sql.DataSource configured = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                javax.sql.DataSource.class.getClassLoader(), new Class<?>[]{javax.sql.DataSource.class}, (proxy, method, arguments) -> {
                    try {
                        Object value = method.invoke(data, arguments);
                        if (value instanceof java.sql.Connection connection) connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
                        return value;
                    } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
        var first = provider(configured);
        var second = provider(configured);
        try (var pool = Executors.newFixedThreadPool(6)) {
            var tasks = java.util.stream.IntStream.range(0, 12).<java.util.concurrent.Callable<ActionResult>>mapToObj(i ->
                    () -> executor().execute(MANIFEST, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Concurrent"), "key", i % 2 == 0 ? first : second)).toList();
            var results = pool.invokeAll(tasks);
            var expected = results.getFirst().get();
            for (var result : results) assertEquals(expected, result.get());
        }
        assertEquals(1, count(data, "of_objects"));
        assertEquals(1, count(data, "of_object_history"));
        assertEquals(1, count(data, "of_command_receipts"));
        assertEquals(1, count(data, "of_outbox_events"));
    }

    @Test
    void receiptInsertFailureRollsBackEffectsHistoryAndOutboxAndAllowsRetry() throws Exception {
        var data = data("jdbc:h2:mem:command_failure;DB_CLOSE_DELAY=-1");
        var storage = provider(data);
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE of_command_receipts ADD CONSTRAINT reject_receipt CHECK (actor_id <> 'operator')");
        }
        assertThrows(IllegalStateException.class, () -> executor().execute(MANIFEST, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Retry"), "key", storage));
        for (String table : List.of("of_objects", "of_object_history", "of_command_receipts", "of_audit_records", "of_outbox_events")) assertEquals(0, count(data, table), table);
        try (var connection = data.getConnection(); var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE of_command_receipts DROP CONSTRAINT reject_receipt");
        }
        assertTrue(executor().execute(MANIFEST, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Retry"), "key", storage).success());
        assertEquals(1, count(data, "of_objects"));
    }

    @Test
    void failedPreconditionDoesNotConsumeKeyAndRevocationStillRejectsReplay() {
        var data = data("jdbc:h2:mem:command_policy;DB_CLOSE_DELAY=-1");
        var storage = provider(data);
        var ready = new AtomicBoolean(false);
        var allowed = new AtomicBoolean(true);
        var manifest = new ActionManifest("Create", 1, false, List.of(new ActionManifest.Precondition("ready", "not ready")), MANIFEST.effects());
        var executor = new ActionExecutor((expression, values, actor) -> ready.get(), null, (context, actor, definition, values) -> allowed.get());
        assertFalse(executor.execute(manifest, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Waiting"), "key", storage).success());
        assertEquals(0, count(data, "of_command_receipts"));
        ready.set(true);
        var result = executor.execute(manifest, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Waiting"), "key", storage);
        ready.set(false);
        assertEquals(result, executor.execute(manifest, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Waiting"), "key", storage));
        allowed.set(false);
        assertThrows(SecurityException.class, () -> executor.execute(manifest, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Waiting"), "key", storage));
    }

    @Test
    void receiptsAreScopedByTenantAndActorAndCannotBeReadThroughAnotherContext() {
        var data = data("jdbc:h2:mem:command_scope;DB_CLOSE_DELAY=-1");
        var storage = provider(data);
        var a = executor().execute(MANIFEST, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Scope"), "key", storage);
        var otherActor = new ActionActor("another", Set.of());
        var b = executor().execute(MANIFEST, DEFINITION, RequestContext.system("tenant", "another"), otherActor, Map.of("name", "Scope"), "key", storage);
        var c = executor().execute(MANIFEST, DEFINITION, RequestContext.system("other", "operator"), ACTOR, Map.of("name", "Scope"), "key", storage);
        assertEquals(3, Set.of(a.actionId(), b.actionId(), c.actionId()).size());
        String key = ActionFingerprint.hash(List.of("tenant", "operator", "Create", "key"));
        try (var tx = storage.beginTransaction(RequestContext.system("tenant", "another"))) {
            assertThrows(SecurityException.class, () -> tx.getCommandReceipt(key));
        }
        try (var tx = storage.beginTransaction(RequestContext.system("other", "operator"))) {
            assertNull(tx.getCommandReceipt(key));
        }
    }

    @Test
    void authorizationIsRecheckedAfterWaitingForTheDatabaseWriteLock() throws Exception {
        var data = data("jdbc:h2:mem:command_wait;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        var storage = provider(data);
        var ready = new java.util.concurrent.CountDownLatch(1);
        var allowed = new AtomicBoolean(true);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        var executor = new ActionExecutor(ExpressionEvaluator.simple(), null, (context, actor, definition, values) -> {
            boolean decision = allowed.get();
            checks.incrementAndGet();
            ready.countDown();
            return decision;
        });
        try (var pool = Executors.newSingleThreadExecutor(); var blocker = storage.beginTransaction(CONTEXT)) {
            blocker.acquireWrite();
            var pending = pool.submit(() -> executor.execute(MANIFEST, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Denied after wait"), "waiting", provider(data)));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            allowed.set(false);
            blocker.commit();
            var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, error.getCause());
            assertEquals(2, checks.get(), "The first decision allowed; the check after waiting must reject");
        }
        assertEquals(0, count(data, "of_objects"));
        assertEquals(0, count(data, "of_command_receipts"));
    }

    @Test
    void multipleEffectsUseTransactionalVersionsAndPartialFailureRollsBack() {
        var data = data("jdbc:h2:mem:command_versions;DB_CLOSE_DELAY=-1");
        var storage = provider(data);
        ObjectRecord object;
        try (var tx = storage.beginTransaction(CONTEXT)) {
            object = tx.createObject("Item", "existing", Map.of("name", "Original"));
            tx.commit();
        }
        var type = new ActionTypeDefinition("RenameTwice", List.of(new ActionParameter("item", "Item", true)), "can_rename");
        var good = new ActionManifest("RenameTwice", 1, false, List.of(), List.of(
                new ActionManifest.UpdateObject("item", Map.of("name", "First")),
                new ActionManifest.UpdateObject("item", Map.of("name", "Second"))));
        var result = executor().execute(good, type, CONTEXT, ACTOR, Map.of("item", object), "twice", storage);
        assertTrue(result.success());
        assertEquals(3, storage.getObject(CONTEXT, "Item", "existing").version());
        assertEquals("Second", storage.getObject(CONTEXT, "Item", "existing").properties().get("name"));
        assertEquals(result, executor().execute(good, type, CONTEXT, ACTOR, Map.of("item", object), "twice", provider(data)));
        var current = storage.getObject(CONTEXT, "Item", "existing");
        var bad = new ActionManifest("RenameTwice", 2, false, List.of(), List.of(
                new ActionManifest.UpdateObject("item", Map.of("name", "Partial")),
                new ActionManifest.UpdateObject("item", Map.of("unknown", "Invalid"))));
        assertThrows(IllegalArgumentException.class, () -> executor().execute(bad, type, CONTEXT, ACTOR, Map.of("item", current), "failure", storage));
        assertEquals("Second", storage.getObject(CONTEXT, "Item", "existing").properties().get("name"));
        assertEquals(3, storage.getObject(CONTEXT, "Item", "existing").version());
        assertEquals(1, count(data, "of_command_receipts"));
    }

    @Test
    void separateJvmProcessesReplayOneCommandOverTheSameDatabase() throws Exception {
        var server = org.h2.tools.Server.createTcpServer("-tcpPort", "0", "-tcpDaemon", "-ifNotExists").start();
        var workers = new java.util.ArrayList<Process>();
        try {
            String url = "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/mem:process_receipts;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
            var data = data(url);
            provider(data);
            Path release = temporary.resolve("release");
            for (int i = 0; i < 2; i++) {
                workers.add(new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", System.getProperty("java.class.path"), ReceiptCrashWorker.class.getName(), url, "normal",
                        temporary.resolve("ready-" + i).toString(), release.toString())
                        .redirectErrorStream(true).redirectOutput(temporary.resolve("process-" + i + ".log").toFile()).start());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while ((!java.nio.file.Files.exists(temporary.resolve("ready-0")) || !java.nio.file.Files.exists(temporary.resolve("ready-1")))
                    && System.nanoTime() < deadline && workers.stream().allMatch(Process::isAlive)) Thread.sleep(10);
            assertTrue(java.nio.file.Files.exists(temporary.resolve("ready-0")) && java.nio.file.Files.exists(temporary.resolve("ready-1")), "Both processes must reach the command barrier");
            java.nio.file.Files.writeString(release, "go");
            var results = new java.util.ArrayList<String>();
            for (int i = 0; i < workers.size(); i++) {
                var worker = workers.get(i);
                assertTrue(worker.waitFor(30, TimeUnit.SECONDS));
                String output = java.nio.file.Files.readString(temporary.resolve("process-" + i + ".log"));
                assertEquals(0, worker.exitValue(), output);
                results.add(output.lines().filter(line -> line.startsWith("RESULT:")).findFirst().orElseThrow());
            }
            assertEquals(results.getFirst(), results.get(1));
            for (String table : List.of("of_objects", "of_object_history", "of_command_receipts", "of_audit_records", "of_outbox_events")) assertEquals(1, count(data, table), table);
        } finally {
            for (var worker : workers) if (worker.isAlive()) worker.destroyForcibly();
            server.stop();
        }
    }

    @Test
    void malformedCommittedReceiptCannotTriggerAnotherExecution() {
        var data = data("jdbc:h2:mem:command_bad_receipt;DB_CLOSE_DELAY=-1");
        var storage = provider(data);
        var parameters = Map.<String, Object>of("name", "Malformed result");
        String key = ActionFingerprint.hash(List.of("tenant", "operator", "Create", "bad-key"));
        String fingerprint = ActionFingerprint.hash(List.of(MANIFEST, DEFINITION, parameters));
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.putCommandReceipt(new CommandReceipt(key, "operator", "Create", fingerprint,
                    Map.of("format", 1.5, "success", true, "actionId", "invalid", "affected", List.of())));
            tx.commit();
        }
        assertThrows(IllegalStateException.class, () -> executor().execute(MANIFEST, DEFINITION, CONTEXT, ACTOR, parameters, "bad-key", storage));
        assertEquals(0, count(data, "of_objects"));
        assertEquals(1, count(data, "of_command_receipts"));
    }

    @Test
    void crashBeforeCommitLeavesNoPartialCommand() throws Exception {
        crash("before", 41, 0);
    }

    @Test
    void crashAfterCommitReplaysSavedResultInsteadOfRepeatingEffects() throws Exception {
        crash("after", 42, 1);
    }

    private void crash(String mode, int exit, int committed) throws Exception {
        String url = "jdbc:h2:file:" + temporary.resolve(mode) + ";WRITE_DELAY=0";
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ReceiptCrashWorker.class.getName(), url, mode)
                .redirectErrorStream(true).redirectOutput(temporary.resolve(mode + ".log").toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Crash worker must terminate");
            assertEquals(exit, process.exitValue(), java.nio.file.Files.readString(temporary.resolve(mode + ".log")));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        var data = data(url);
        var storage = provider(data);
        assertEquals(committed, count(data, "of_objects"));
        assertEquals(committed, count(data, "of_command_receipts"));
        String savedAction = null;
        if (committed == 1) {
            try (var tx = storage.beginTransaction(CONTEXT)) {
                savedAction = tx.getCommandReceipt(ActionFingerprint.hash(List.of("tenant", "operator", "Create", "crash-key"))).result().get("actionId").toString();
            }
        }
        var result = executor().execute(MANIFEST, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Crash fixture"), "crash-key", storage);
        var replay = executor().execute(MANIFEST, DEFINITION, CONTEXT, ACTOR, Map.of("name", "Crash fixture"), "crash-key", provider(data));
        assertEquals(result, replay);
        if (savedAction != null) assertEquals(savedAction, result.actionId(), "Return the committed response, not a newly manufactured result");
        for (String table : List.of("of_objects", "of_object_history", "of_command_receipts", "of_audit_records", "of_outbox_events")) assertEquals(1, count(data, table), table);
    }

    static JdbcDataSource data(String url) {
        var data = new JdbcDataSource(); data.setURL(url); return data;
    }
    static JdbcStorageProvider provider(javax.sql.DataSource data) {
        var provider = new JdbcStorageProvider(data, DatabaseDialect.h2()); provider.applySchema(CONTEXT, SCHEMA); return provider;
    }
    static int count(javax.sql.DataSource data, String table) {
        try (var connection = data.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next(); return rows.getInt(1);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
}
