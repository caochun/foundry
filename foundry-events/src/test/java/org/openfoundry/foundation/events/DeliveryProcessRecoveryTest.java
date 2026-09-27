package org.openfoundry.foundation.events;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DeliveryProcessRecoveryTest {
    @TempDir Path temporary;

    @Test
    void outboxClaimRecoversAfterProcessExitAndRejectsItsOldToken() throws Exception {
        String url = fileUrl("outbox");
        var store = store(url);
        var event = new OutboxEvent("event", "tenant", "event", "subject", DeliveryTestClock.START, "tx", Map.of(), null);
        store.append(event);
        Path marker = temporary.resolve("claim-token");
        crash(url, "outbox-claim", marker, 31);
        var reopened = store(url);
        assertTrue(reopened.claim("tenant", 1, DeliveryTestClock.START.plusSeconds(9), Duration.ofSeconds(10)).isEmpty());
        var resumed = reopened.claim("tenant", 1, DeliveryTestClock.START.plusSeconds(10), Duration.ofSeconds(10)).getFirst();
        assertEquals(2, resumed.attempt());
        var stale = new OutboxClaim(event, Files.readString(marker), 1, DeliveryTestClock.START.plusSeconds(10));
        assertFalse(reopened.complete(stale, DeliveryTestClock.START.plusSeconds(11)));
        assertTrue(reopened.complete(resumed, DeliveryTestClock.START.plusSeconds(11)));
        assertTrue(reopened.pending("tenant", 1).isEmpty());
    }

    @Test
    void consumerExitAfterClaimBeforeCallbackDoesNotLoseTheEvent() throws Exception {
        recoverConsumer("consumer-before", 32, 0);
    }

    @Test
    void consumerExitAfterCallbackBeforeAcknowledgementMayRepeatButNeverPretendsCompletion() throws Exception {
        recoverConsumer("consumer-after", 33, 1);
    }

    @Test
    void independentJvmsCannotOwnTheSameUnexpiredOutboxClaim() throws Exception {
        var server = org.h2.tools.Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
        var workers = new ArrayList<Process>();
        try {
            String url = "jdbc:h2:tcp://localhost:" + server.getPort() + "/mem:delivery_race;DB_CLOSE_DELAY=-1";
            store(url).append(new OutboxEvent("event", "tenant", "event", "subject", DeliveryTestClock.START, "tx", Map.of(), null));
            Path release = temporary.resolve("go");
            for (int i = 0; i < 2; i++) {
                workers.add(process(url, "outbox-race", temporary.resolve("unused"), temporary.resolve("ready-" + i).toString(), release.toString())
                        .redirectOutput(temporary.resolve("worker-" + i + ".log").toFile()).start());
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while ((!Files.exists(temporary.resolve("ready-0")) || !Files.exists(temporary.resolve("ready-1")))
                    && System.nanoTime() < deadline && workers.stream().allMatch(Process::isAlive)) Thread.sleep(10);
            assertTrue(Files.exists(temporary.resolve("ready-0")) && Files.exists(temporary.resolve("ready-1")));
            Files.writeString(release, "go");
            var outputs = new ArrayList<String>();
            for (int i = 0; i < 2; i++) {
                assertTrue(workers.get(i).waitFor(25, TimeUnit.SECONDS));
                String output = Files.readString(temporary.resolve("worker-" + i + ".log"));
                assertEquals(0, workers.get(i).exitValue(), output);
                outputs.add(output.strip());
            }
            assertEquals(1, outputs.stream().filter(value -> value.endsWith("CLAIMED")).count(), outputs.toString());
            assertEquals(1, outputs.stream().filter(value -> value.endsWith("EMPTY")).count(), outputs.toString());
        } finally {
            workers.forEach(worker -> { if (worker.isAlive()) worker.destroyForcibly(); });
            server.stop();
        }
    }

    private void recoverConsumer(String mode, int exit, int previouslyDelivered) throws Exception {
        String url = fileUrl(mode);
        Path marker = temporary.resolve(mode + ".delivered");
        crash(url, mode, marker, exit);
        assertEquals(previouslyDelivered, Files.exists(marker) ? Files.readAllLines(marker).size() : 0);
        var clock = new DeliveryTestClock();
        var data = data(url);
        EventSink callback = event -> {
            try { Files.writeString(marker, "delivered\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
        };
        var recovered = new JdbcIdempotentEventSink(data, DatabaseDialect.h2(), "consumer", callback, clock, Duration.ofSeconds(10));
        assertEquals(EventDeliveryException.Reason.BUSY,
                assertThrows(EventDeliveryException.class, () -> recovered.publish(DeliveryCrashWorker.EVENT)).reason());
        clock.advance(10);
        recovered.publish(DeliveryCrashWorker.EVENT);
        new JdbcIdempotentEventSink(data, DatabaseDialect.h2(), "consumer", callback, clock, Duration.ofSeconds(10)).publish(DeliveryCrashWorker.EVENT);
        assertEquals(previouslyDelivered + 1, Files.readAllLines(marker).size());
    }

    private void crash(String url, String mode, Path marker, int expected) throws Exception {
        Path log = temporary.resolve(mode + ".log");
        var worker = process(url, mode, marker).redirectOutput(log.toFile()).start();
        try {
            assertTrue(worker.waitFor(25, TimeUnit.SECONDS), "Crash process must exit");
            assertEquals(expected, worker.exitValue(), Files.readString(log));
        } finally {
            if (worker.isAlive()) worker.destroyForcibly();
        }
    }

    private ProcessBuilder process(String url, String mode, Path marker, String... extra) {
        var args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), DeliveryCrashWorker.class.getName(), url, mode, marker.toString()));
        args.addAll(List.of(extra));
        return new ProcessBuilder(args).redirectErrorStream(true);
    }

    private String fileUrl(String name) {
        return "jdbc:h2:file:" + temporary.resolve(name) + ";WRITE_DELAY=0";
    }

    private static JdbcDataSource data(String url) {
        var data = new JdbcDataSource();
        data.setURL(url);
        return data;
    }

    private static JdbcEventStore store(String url) {
        var store = new JdbcEventStore(data(url), DatabaseDialect.h2());
        store.initialize();
        return store;
    }
}
