package org.openfoundry.foundation.sync;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.spi.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.openfoundry.foundation.sync.RelationshipIngestionTest.*;

class RelationshipProcessRecoveryTest {
    @TempDir Path directory;

    @TestFactory
    Stream<DynamicTest> physicalCommitBoundaries() {
        return Stream.of("before", "after").map(mode -> DynamicTest.dynamicTest(mode, () -> {
            String url = "jdbc:h2:file:" + directory.resolve(mode) + ";WRITE_DELAY=0";
            var source = data(url);
            fixture(source);
            Path log = directory.resolve(mode + ".log");
            var process = process(url, mode).redirectOutput(log.toFile()).start();
            try {
                assertTrue(process.waitFor(25, TimeUnit.SECONDS));
                assertEquals(mode.equals("before") ? 91 : 92, process.exitValue(), Files.readString(log));
            } finally { if (process.isAlive()) process.destroyForcibly(); }
            var recovered = open(source);
            if (mode.equals("before")) {
                assertNull(recovered.storage.getObject(CTX, "Person", "p"));
                assertTrue(recovered.links("Member").isEmpty());
                assertTrue(recovered.storage.getRelationshipAssertions(CTX, scope("Member"), 100, null).isEmpty());
                assertNull(recovered.service.checkpoint("hr", MAPPING, "p0", CTX));
            } else {
                assertEquals(1, recovered.links("Member").size());
                assertEquals(1, recovered.storage.getRelationshipAssertions(CTX, scope("Member"), 100, null).size());
                assertEquals(1, recovered.service.checkpoint("hr", MAPPING, "p0", CTX).sequence());
            }
            var result = recovered.run(MAPPING, record(1, "u1", Map.of("note", "Durable")));
            assertTrue(result.failures().isEmpty(), result.failures().toString());
            assertEquals(mode.equals("before") ? 1 : 0, result.created());
            assertEquals(mode.equals("after") ? 1 : 0, result.replayed());
            assertEquals(1, recovered.links("Member").size());
            assertEquals(1, recovered.links("Member").getFirst().version());
            assertEquals(1, recovered.storage.getRelationshipAssertions(CTX, scope("Member"), 100, null).size());
            assertEquals(1, recovered.service.checkpoint("hr", MAPPING, "p0", CTX).version());
            recovered.denied.add(new EntityKey("Unit", "u1"));
            assertEquals(1, recovered.run(MAPPING, record(1, "u1", Map.of("note", "Durable"))).failures().size());
        }));
    }

    @Test
    void concurrentProcessesCreateOneRelationshipAndOneCheckpoint() throws Exception {
        var server = org.h2.tools.Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
        var workers = new ArrayList<Process>();
        try {
            String url = "jdbc:h2:tcp://localhost:" + server.getPort() + "/mem:relationship_race;DB_CLOSE_DELAY=-1";
            var source = data(url);
            fixture(source);
            Path release = directory.resolve("release");
            for (int index = 0; index < 2; index++) {
                workers.add(process(url, "normal", directory.resolve("ready-" + index).toString(), release.toString())
                        .redirectOutput(directory.resolve("worker-" + index + ".log").toFile()).start());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while ((!Files.exists(directory.resolve("ready-0")) || !Files.exists(directory.resolve("ready-1")))
                    && System.nanoTime() < deadline && workers.stream().allMatch(Process::isAlive)) Thread.sleep(10);
            assertTrue(Files.exists(directory.resolve("ready-0")) && Files.exists(directory.resolve("ready-1")));
            Files.writeString(release, "go");
            int objects = 0, links = 0, replays = 0;
            for (int index = 0; index < 2; index++) {
                assertTrue(workers.get(index).waitFor(25, TimeUnit.SECONDS));
                String log = Files.readString(directory.resolve("worker-" + index + ".log"));
                assertEquals(0, workers.get(index).exitValue(), log);
                var values = log.lines().filter(line -> line.startsWith("RESULT:")).findFirst().orElseThrow().split(":");
                objects += Integer.parseInt(values[1]); replays += Integer.parseInt(values[2]); links += Integer.parseInt(values[3]);
                assertEquals("0", values[4], log);
            }
            assertEquals(1, objects); assertEquals(1, links); assertEquals(1, replays);
            var recovered = open(source);
            assertEquals(1, recovered.links("Member").size());
            assertEquals(1, recovered.storage.getRelationshipAssertions(CTX, scope("Member"), 100, null).size());
            assertEquals(1, recovered.service.checkpoint("hr", MAPPING, "p0", CTX).version());
        } finally {
            workers.forEach(worker -> { if (worker.isAlive()) worker.destroyForcibly(); });
            server.stop();
        }
    }

    private static ProcessBuilder process(String... arguments) {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", System.getProperty("java.class.path"), RelationshipCrashWorker.class.getName()));
        command.addAll(Arrays.asList(arguments));
        return new ProcessBuilder(command).redirectErrorStream(true);
    }
}
