package org.openfoundry.foundation.storage.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.schema.OdlParser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SchemaRegistryRecoveryTest {
    @TempDir Path directory;

    @Test
    void crashBeforePhysicalCommitLeavesNoVersionOrAdvancedHead() throws Exception {
        crash("before", 41, 0);
    }

    @Test
    void crashAfterPhysicalCommitPreservesBothVersionAndHead() throws Exception {
        crash("after", 42, 1);
    }

    private void crash(String mode, int exitCode, int expectedVersions) throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve(mode) + ";WRITE_DELAY=0";
        var worker = process(url, mode, directory.resolve(mode + ".log")).start();
        try {
            assertTrue(worker.waitFor(40, TimeUnit.SECONDS));
            assertEquals(exitCode, worker.exitValue(), Files.readString(directory.resolve(mode + ".log")));
            var registry = new JdbcSchemaRegistry(SchemaRegistryPersistenceTest.data(url), DatabaseDialect.h2());
            assertEquals(expectedVersions, registry.currentVersion());
            var schema = new OdlParser().parse(SchemaRegistryWorker.ODL);
            assertEquals(1, registry.applyIfChanged(schema, null).version());
            assertEquals(schema, registry.current());
            assertEquals(1, registry.history().size());
        } finally { if (worker.isAlive()) worker.destroyForcibly(); }
    }

    @Test
    void twoIndependentJvmsCannotBothApplyAgainstVersionZero() throws Exception {
        var server = org.h2.tools.Server.createTcpServer("-tcpPort", "0", "-tcpDaemon", "-ifNotExists").start();
        var workers = new ArrayList<Process>();
        try {
            String url = "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/mem:registry_race;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000";
            var registry = new JdbcSchemaRegistry(SchemaRegistryPersistenceTest.data(url), DatabaseDialect.h2());
            assertEquals(0, registry.currentVersion());
            Path release = directory.resolve("release");
            for (int index = 0; index < 2; index++) {
                workers.add(process(url, "race", directory.resolve("race-" + index + ".log"), directory.resolve("ready-" + index).toString(), release.toString()).start());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while ((!Files.exists(directory.resolve("ready-0")) || !Files.exists(directory.resolve("ready-1")))
                    && workers.stream().allMatch(Process::isAlive) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(directory.resolve("ready-0")) && Files.exists(directory.resolve("ready-1")), "Both JVMs must reach the barrier");
            Files.writeString(release, "go");
            var outcomes = new ArrayList<Integer>();
            for (int index = 0; index < 2; index++) {
                var worker = workers.get(index);
                assertTrue(worker.waitFor(40, TimeUnit.SECONDS));
                String output = Files.readString(directory.resolve("race-" + index + ".log"));
                assertTrue(worker.exitValue() == 0 || worker.exitValue() == 3, output);
                outcomes.add(worker.exitValue());
            }
            assertEquals(List.of(0, 3), outcomes.stream().sorted().toList());
            assertEquals(1, registry.currentVersion());
            assertEquals(new OdlParser().parse(SchemaRegistryWorker.ODL), registry.current());
        } finally {
            for (var worker : workers) if (worker.isAlive()) worker.destroyForcibly();
            server.stop();
        }
    }

    private ProcessBuilder process(String url, String mode, Path log, String... barrier) {
        var args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), SchemaRegistryWorker.class.getName(), url, mode));
        args.addAll(List.of(barrier));
        return new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile());
    }
}
