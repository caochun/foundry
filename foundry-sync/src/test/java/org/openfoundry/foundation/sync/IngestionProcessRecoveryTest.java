package org.openfoundry.foundation.sync;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.spi.LineageQuery;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.openfoundry.foundation.sync.MaterializedIngestionTest.*;

class IngestionProcessRecoveryTest {
    @TempDir Path directory;

    @TestFactory
    Stream<DynamicTest> crashBoundaries() {
        return Stream.of("before", "after").map(mode -> DynamicTest.dynamicTest(mode, () -> {
            String url = "jdbc:h2:file:" + directory.resolve(mode) + ";WRITE_DELAY=0";
            var source = data(url);
            fixture(source);
            Path log = directory.resolve(mode + ".log");
            var worker = process(url, mode, "worker").redirectOutput(log.toFile()).start();
            try {
                assertTrue(worker.waitFor(25, TimeUnit.SECONDS));
                assertEquals(mode.equals("before") ? 81 : 82, worker.exitValue(), Files.readString(log));
            } finally { if (worker.isAlive()) worker.destroyForcibly(); }
            var recovered = fixture(source);
            if (mode.equals("before")) {
                assertNull(recovered.person("a"));
                assertNull(recovered.checkpoint("hr"));
                assertTrue(recovered.storage.getLineage(CTX, PERSON, LineageQuery.defaults()).isEmpty());
            } else {
                assertEquals(1, recovered.person("a").version());
                assertEquals(1, recovered.checkpoint("hr").sequence());
                assertEquals(1, recovered.storage.getLineage(CTX, PERSON, new LineageQuery("name", 100, null)).size());
            }
            var outcome = recovered.run("hr", List.of(record("hr", "a", 1, Map.of("name", "Durable"))));
            assertTrue(outcome.failures().isEmpty(), outcome.failures().toString());
            assertEquals(mode.equals("before") ? 1 : 0, outcome.created());
            assertEquals(mode.equals("after") ? 1 : 0, outcome.replayed());
            assertEquals(1, recovered.person("a").version());
            assertEquals(1, recovered.checkpoint("hr").version());
            assertEquals(1, recovered.storage.getLineage(CTX, PERSON, new LineageQuery("name", 100, null)).size());
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                for (String table : List.of("of_ingestion_receipts", "of_ingestion_checkpoints", "of_audit_records", "of_outbox_events")) {
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(rows.next()); assertEquals(1, rows.getInt(1), table); }
                }
            }
        }));
    }

    @Test
    void independentWorkersApplyOneSourceEventOnce() throws Exception {
        var server = org.h2.tools.Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
        var workers = new ArrayList<Process>();
        try {
            String url = "jdbc:h2:tcp://localhost:" + server.getPort() + "/mem:ingestion_race;DB_CLOSE_DELAY=-1";
            var source = data(url);
            fixture(source);
            Path release = directory.resolve("release");
            for (int index = 0; index < 2; index++) {
                workers.add(process(url, "normal", "worker-" + index, directory.resolve("ready-" + index).toString(), release.toString())
                        .redirectOutput(directory.resolve("worker-" + index + ".log").toFile()).start());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while ((!Files.exists(directory.resolve("ready-0")) || !Files.exists(directory.resolve("ready-1")))
                    && System.nanoTime() < deadline && workers.stream().allMatch(Process::isAlive)) Thread.sleep(10);
            assertTrue(Files.exists(directory.resolve("ready-0")) && Files.exists(directory.resolve("ready-1")));
            Files.writeString(release, "go");
            int created = 0;
            int replayed = 0;
            for (int index = 0; index < 2; index++) {
                var worker = workers.get(index);
                assertTrue(worker.waitFor(25, TimeUnit.SECONDS));
                String log = Files.readString(directory.resolve("worker-" + index + ".log"));
                assertEquals(0, worker.exitValue(), log);
                var values = log.lines().filter(line -> line.startsWith("RESULT:")).findFirst().orElseThrow().split(":");
                created += Integer.parseInt(values[1]);
                replayed += Integer.parseInt(values[2]);
                assertEquals("0", values[3], log);
            }
            assertEquals(1, created);
            assertEquals(1, replayed);
            var result = fixture(source);
            assertEquals(1, result.person("a").version());
            assertEquals(1, result.checkpoint("hr").version());
            assertEquals(1, result.storage.getLineage(CTX, PERSON, new LineageQuery("name", 100, null)).size());
        } finally {
            workers.forEach(worker -> { if (worker.isAlive()) worker.destroyForcibly(); });
            server.stop();
        }
    }

    private static ProcessBuilder process(String... arguments) {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), IngestionCrashWorker.class.getName()));
        command.addAll(Arrays.asList(arguments));
        return new ProcessBuilder(command).redirectErrorStream(true);
    }
}
