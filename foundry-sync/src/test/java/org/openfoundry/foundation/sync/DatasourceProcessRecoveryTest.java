package org.openfoundry.foundation.sync;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.openfoundry.foundation.sync.DatasourceRunnerTest.*;
import static org.openfoundry.foundation.sync.JdbcSourceConnectorTest.*;

class DatasourceProcessRecoveryTest {
    @TempDir Path directory;

    @TestFactory
    Stream<DynamicTest> resumesSourceQueryAfterPhysicalCommitBoundary() {
        return Stream.of("before", "after").map(mode -> DynamicTest.dynamicTest(mode, () -> {
            String targetUrl = "jdbc:h2:file:" + directory.resolve(mode + "-target") + ";WRITE_DELAY=0";
            String sourceUrl = "jdbc:h2:file:" + directory.resolve(mode + "-source") + ";DATABASE_TO_LOWER=TRUE;WRITE_DELAY=0";
            seed(sourceUrl);
            Path log = directory.resolve(mode + ".log");
            Process worker = process(targetUrl, sourceUrl, mode).redirectOutput(log.toFile()).start();
            try {
                assertTrue(worker.waitFor(25, TimeUnit.SECONDS));
                assertEquals(mode.equals("before") ? 101 : 102, worker.exitValue(), Files.readString(log));
            } finally {
                if (worker.isAlive()) {
                    worker.destroyForcibly();
                }
            }
            try (var recovered = new JdbcStorageProvider(database(targetUrl), DatabaseDialect.h2())) {
                recovered.applySchema(CONTEXT, SCHEMA);
                var runner = new DatasourceRunner(recovered, ConnectorRegistry.jdbc(configuration -> database(sourceUrl)), ALLOW);
                if (mode.equals("before")) {
                    assertNull(runner.checkpoint(mapping(), CONTEXT));
                    assertNull(recovered.getObject(CONTEXT, "Person", "1"));
                } else {
                    assertEquals(1, runner.checkpoint(mapping(), CONTEXT).sequence());
                    assertEquals(1, recovered.getObject(CONTEXT, "Person", "1").version());
                }
                var result = runner.runOnce(mapping(), CONTEXT, OPTIONS);
                assertTrue(result.failures().isEmpty(), result.failures().toString());
                assertEquals(mode.equals("before") ? 3 : 2, result.created());
                assertEquals(0, result.replayed(), "The source resumes from the committed cursor instead of restarting the scan");
                assertEquals(3, runner.checkpoint(mapping(), CONTEXT).sequence());
                assertEquals(1, recovered.getObject(CONTEXT, "Person", "1").version());
            }
        }));
    }

    @Test
    void concurrentSourceReadersShareExactlyTheCommittedEvents() throws Exception {
        var server = org.h2.tools.Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
        var workers = new ArrayList<Process>();
        try {
            String base = "jdbc:h2:tcp://localhost:" + server.getPort() + "/mem:";
            String target = base + "poll_target;DB_CLOSE_DELAY=-1";
            String source = base + "poll_source;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
            seed(source);
            try (var storage = new JdbcStorageProvider(database(target), DatabaseDialect.h2())) {
                storage.applySchema(CONTEXT, SCHEMA);
            }
            Path release = directory.resolve("release");
            for (int i = 0; i < 2; i++) {
                workers.add(process(target, source, "normal", directory.resolve("ready-" + i).toString(), release.toString())
                        .redirectOutput(directory.resolve("worker-" + i + ".log").toFile()).start());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while ((!Files.exists(directory.resolve("ready-0")) || !Files.exists(directory.resolve("ready-1")))
                    && System.nanoTime() < deadline && workers.stream().allMatch(Process::isAlive)) {
                Thread.sleep(10);
            }
            assertTrue(Files.exists(directory.resolve("ready-0")) && Files.exists(directory.resolve("ready-1")));
            Files.writeString(release, "go");
            int created = 0;
            int replayed = 0;
            for (int i = 0; i < 2; i++) {
                Process worker = workers.get(i);
                assertTrue(worker.waitFor(25, TimeUnit.SECONDS));
                String log = Files.readString(directory.resolve("worker-" + i + ".log"));
                assertEquals(0, worker.exitValue(), log);
                String[] result = log.lines().filter(line -> line.startsWith("RESULT:")).findFirst().orElseThrow().split(":");
                created += Integer.parseInt(result[1]);
                replayed += Integer.parseInt(result[2]);
                assertEquals("0", result[3], log);
            }
            assertEquals(3, created);
            assertEquals(3, replayed);
            try (var storage = new JdbcStorageProvider(database(target), DatabaseDialect.h2())) {
                storage.applySchema(CONTEXT, SCHEMA);
                var runner = new DatasourceRunner(storage, ConnectorRegistry.jdbc(configuration -> database(source)), ALLOW);
                assertEquals(3, runner.checkpoint(mapping(), CONTEXT).sequence());
                assertEquals(1, storage.getObject(CONTEXT, "Person", "1").version());
            }
        } finally {
            workers.stream().filter(Process::isAlive).forEach(Process::destroyForcibly);
            server.stop();
        }
    }

    private void seed(String url) throws Exception {
        var source = database(url);
        execute(source, "CREATE TABLE people (id BIGINT PRIMARY KEY, name VARCHAR, updated_at TIMESTAMP NOT NULL)");
        execute(source, "INSERT INTO people VALUES (1,'First',TIMESTAMP '2026-01-01 00:00:00'),(2,'Second',TIMESTAMP '2026-01-01 00:00:00'),(10,'Tenth',TIMESTAMP '2026-01-01 00:00:00')");
        if (!url.contains("tcp:")) {
            execute(source, "SHUTDOWN");
        }
    }

    private ProcessBuilder process(String... arguments) {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                System.getProperty("java.class.path"), DatasourceCrashWorker.class.getName()));
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command).redirectErrorStream(true);
    }
}
