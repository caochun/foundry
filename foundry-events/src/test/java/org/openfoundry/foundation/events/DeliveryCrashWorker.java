package org.openfoundry.foundation.events;

import org.h2.jdbcx.JdbcDataSource;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Independent process: committed claims survive Runtime.halt at exact callback boundaries. */
public final class DeliveryCrashWorker {
    static final CloudEvent EVENT = ConsumerRecoveryTest.event("tenant", "source", "event", Map.of("value", 1));

    public static void main(String[] args) throws Exception {
        var data = new JdbcDataSource();
        data.setURL(args[0]);
        String mode = args[1];
        Path marker = Path.of(args[2]);
        var clock = new DeliveryTestClock();
        if (mode.startsWith("outbox")) {
            var store = new JdbcEventStore(data, DatabaseDialect.h2());
            store.initialize();
            if (mode.equals("outbox-race")) {
                Files.writeString(Path.of(args[3]), "ready");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
                while (!Files.exists(Path.of(args[4])) && System.nanoTime() < deadline) Thread.sleep(10);
                if (!Files.exists(Path.of(args[4]))) throw new IllegalStateException("Barrier not released");
            }
            var claims = store.claim("tenant", 1, clock.instant(), Duration.ofSeconds(10));
            if (mode.equals("outbox-race")) {
                System.out.println(claims.isEmpty() ? "EMPTY" : "CLAIMED");
                return;
            }
            Files.writeString(marker, claims.getFirst().token());
            Runtime.getRuntime().halt(31);
        }
        var sink = new JdbcIdempotentEventSink(data, DatabaseDialect.h2(), "consumer", event -> {
            if (mode.equals("consumer-after")) {
                try { Files.writeString(marker, "delivered\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
                catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
            }
            Runtime.getRuntime().halt(mode.equals("consumer-after") ? 33 : 32);
        }, clock, Duration.ofSeconds(10));
        sink.publish(EVENT);
        throw new IllegalStateException("Crash injection was not reached");
    }
}
