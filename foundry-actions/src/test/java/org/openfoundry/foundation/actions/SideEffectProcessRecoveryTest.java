package org.openfoundry.foundation.actions;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.openfoundry.foundation.actions.SideEffectCrashWorker.*;

class SideEffectProcessRecoveryTest {
    @TempDir Path temporary;

    @Test
    void crashAfterBusinessCommitResumesSideEffectsWithoutRepeatingBusinessWrites() throws Exception {
        crashAndRecover("after-effects", 41, false, 0);
    }

    @Test
    void crashAfterCallbackBeforeCompletionRetriesWithStableExternalIdentity() throws Exception {
        crashAndRecover("after-callback", 42, false, 1);
    }

    @Test
    void crashBeforeCompensationCommitRollsItBackAndResumesTheWholeCompensation() throws Exception {
        crashAndRecover("during-compensation", 43, true, 0);
    }

    @Test
    void crashAfterCompensationCommitDoesNotRepeatIt() throws Exception {
        crashAndRecover("after-compensation", 44, true, 0);
    }

    @Test
    void independentJvmsShareOneBusinessExecutionAndOneUnexpiredSideEffectClaim() throws Exception {
        var server = org.h2.tools.Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
        var workers = new ArrayList<Process>();
        try {
            String url = "jdbc:h2:tcp://localhost:" + server.getPort() + "/mem:side_effect_race;DB_CLOSE_DELAY=-1";
            seed(url);
            Path marker = temporary.resolve("deliveries");
            Path release = temporary.resolve("go");
            for (int i = 0; i < 2; i++) {
                workers.add(process(url, "normal", marker, temporary.resolve("ready-" + i).toString(), release.toString())
                        .redirectOutput(temporary.resolve("worker-" + i + ".log").toFile()).start());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while ((!Files.exists(temporary.resolve("ready-0")) || !Files.exists(temporary.resolve("ready-1")))
                    && System.nanoTime() < deadline && workers.stream().allMatch(Process::isAlive)) Thread.sleep(10);
            assertTrue(Files.exists(temporary.resolve("ready-0")) && Files.exists(temporary.resolve("ready-1")));
            Files.writeString(release, "go");
            var ids = new ArrayList<String>();
            for (int i = 0; i < 2; i++) {
                assertTrue(workers.get(i).waitFor(25, TimeUnit.SECONDS));
                String output = Files.readString(temporary.resolve("worker-" + i + ".log"));
                assertEquals(0, workers.get(i).exitValue(), output);
                ids.add(output.lines().filter(line -> line.startsWith("RESULT:")).findFirst().orElseThrow().split(":")[1]);
            }
            assertEquals(ids.getFirst(), ids.getLast());
            assertEquals(1, Files.readAllLines(marker).size());
            var storage = provider(url, START.plusSeconds(20));
            assertEquals(2, storage.getObject(CONTEXT, "Item", "item").version());
            try (var tx = storage.beginTransaction(CONTEXT)) {
                assertEquals("COMPLETED", tx.getActionExecution(ids.getFirst()).status());
            }
        } finally {
            workers.forEach(worker -> { if (worker.isAlive()) worker.destroyForcibly(); });
            server.stop();
        }
    }

    private void crashAndRecover(String mode, int exit, boolean rollback, int alreadySent) throws Exception {
        String url = "jdbc:h2:file:" + temporary.resolve(mode) + ";WRITE_DELAY=0";
        seed(url);
        Path marker = temporary.resolve(mode + ".delivered");
        Path log = temporary.resolve(mode + ".log");
        var worker = process(url, mode, marker).redirectOutput(log.toFile()).start();
        try {
            assertTrue(worker.waitFor(25, TimeUnit.SECONDS));
            assertEquals(exit, worker.exitValue(), Files.readString(log));
        } finally {
            if (worker.isAlive()) worker.destroyForcibly();
        }
        assertEquals(alreadySent, Files.exists(marker) ? Files.readAllLines(marker).size() : 0);
        var clock = Clock.fixed(START.plusSeconds(20), ZoneOffset.UTC);
        var storage = provider(url, clock.instant());
        assertEquals(mode.equals("after-compensation") ? 3 : 2, storage.getObject(CONTEXT, "Item", "item").version());
        SideEffectHandler handler = invocation -> {
            try { Files.writeString(marker, invocation.idempotencyKey() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        };
        var executor = executor(handler, clock);
        var result = executor.execute(MANIFEST, DEFINITION, CONTEXT, ACTOR,
                Map.of("item", storage.getObject(CONTEXT, "Item", "item")), "key", storage);
        assertEquals(rollback ? "ROLLED_BACK" : "COMPLETED", result.status());
        assertEquals(rollback ? "BEFORE" : "AFTER", storage.getObject(CONTEXT, "Item", "item").properties().get("name"));
        assertEquals(rollback ? 3 : 2, storage.getObject(CONTEXT, "Item", "item").version());
        assertEquals(result, executor.execute(MANIFEST, DEFINITION, CONTEXT, ACTOR,
                Map.of("item", storage.getObject(CONTEXT, "Item", "item")), "key", storage));
        int expectedDeliveries = rollback ? 0 : alreadySent + 1;
        assertEquals(expectedDeliveries, Files.exists(marker) ? Files.readAllLines(marker).size() : 0);
        if (expectedDeliveries > 0) assertEquals(1, Files.readAllLines(marker).stream().distinct().count());
        assertTrue(storage.pendingActions(CONTEXT, clock.instant(), 10).isEmpty());
    }

    private void seed(String url) {
        var storage = provider(url, START);
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.createObject("Item", "item", Map.of("name", "BEFORE"));
            tx.commit();
        }
    }

    private static JdbcStorageProvider provider(String url, java.time.Instant now) {
        var data = new JdbcDataSource();
        data.setURL(url);
        var provider = new JdbcStorageProvider(data, DatabaseDialect.h2(), Clock.fixed(now, ZoneOffset.UTC));
        provider.applySchema(CONTEXT, SCHEMA);
        return provider;
    }

    private ProcessBuilder process(String url, String mode, Path marker, String... extra) {
        var args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), SideEffectCrashWorker.class.getName(), url, mode, marker.toString()));
        args.addAll(List.of(extra));
        return new ProcessBuilder(args).redirectErrorStream(true);
    }
}
