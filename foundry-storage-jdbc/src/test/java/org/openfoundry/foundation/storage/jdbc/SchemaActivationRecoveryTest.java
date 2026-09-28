package org.openfoundry.foundation.storage.jdbc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SchemaActivationRecoveryTest {
    @TempDir Path directory;

    @Test
    void processExitBeforeActivationCommitPreservesTheOldDeployment() throws Exception { crash("before", 41, 1); }

    @Test
    void processExitAfterActivationCommitPreservesTheNewDeployment() throws Exception { crash("after", 42, 2); }

    private void crash(String mode, int exit, int version) throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve(mode) + ";WRITE_DELAY=0";
        var data = SchemaRegistryPersistenceTest.data(url);
        var initial = SchemaActivationTest.provider(data, SchemaActivationTest.BASE);
        SchemaActivationTest.create(initial, "original", Map.of("name", "Original"));
        var worker = process(url, mode, directory.resolve(mode + ".log")).start();
        try {
            assertTrue(worker.waitFor(40, TimeUnit.SECONDS));
            assertEquals(exit, worker.exitValue(), Files.readString(directory.resolve(mode + ".log")));
            var expected = version == 1 ? SchemaActivationTest.BASE : SchemaActivationTest.ADDED;
            var resumed = SchemaActivationTest.provider(data, expected);
            assertEquals(version, resumed.boundSchemaVersion());
            assertEquals(version, new JdbcSchemaRegistry(data, DatabaseDialect.h2(), JdbcSchemaActivation.REGISTRY_KEY, Clock.systemUTC()).currentVersion());
            assertEquals(version, SchemaActivationTest.count(data, "of_schema_activations"));
            assertEquals(1, resumed.getObject(SchemaActivationTest.CONTEXT, "Node", "original").version());
            assertEquals(1, SchemaActivationTest.count(data, "of_object_history"));
        } finally { if (worker.isAlive()) worker.destroyForcibly(); }
    }

    @Test
    void anIndependentJvmBoundBeforeActivationCannotWriteAfterTheSwitch() throws Exception {
        var server = org.h2.tools.Server.createTcpServer("-tcpPort", "0", "-tcpDaemon", "-ifNotExists").start();
        Process worker = null;
        try {
            String url = "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/mem:activation_process;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000";
            var data = SchemaRegistryPersistenceTest.data(url);
            var storage = SchemaActivationTest.provider(data, SchemaActivationTest.BASE);
            Path ready = directory.resolve("ready"), release = directory.resolve("release");
            worker = process(url, "stale", directory.resolve("stale.log"), ready.toString(), release.toString()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
            while (!Files.exists(ready) && worker.isAlive() && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(ready), Files.readString(directory.resolve("stale.log")));
            storage.activateSchema(SchemaActivationTest.CONTEXT, SchemaActivationTest.ADDED, null, 1);
            Files.writeString(release, "go");
            assertTrue(worker.waitFor(30, TimeUnit.SECONDS));
            assertEquals(3, worker.exitValue(), Files.readString(directory.resolve("stale.log")));
            assertNull(storage.getObject(SchemaActivationTest.CONTEXT, "Node", "stale-worker"));
            assertEquals(0, SchemaActivationTest.count(data, "of_objects"));
            assertEquals(2, SchemaActivationTest.count(data, "of_schema_activations"));
        } finally {
            if (worker != null && worker.isAlive()) worker.destroyForcibly();
            server.stop();
        }
    }

    private ProcessBuilder process(String url, String mode, Path log, String... barrier) {
        var args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"),
                SchemaActivationWorker.class.getName(), url, mode));
        args.addAll(List.of(barrier));
        return new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(log.toFile());
    }
}
