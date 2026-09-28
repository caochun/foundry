package org.openfoundry.foundation.sync;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.storage.jdbc.DatabaseDialect;
import org.openfoundry.foundation.storage.jdbc.JdbcStorageProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.openfoundry.foundation.sync.RestDatasourceTest.*;
import static org.openfoundry.foundation.sync.RestSourceConnectorTest.*;

class RestProcessRecoveryTest {
    @TempDir Path directory;

    @TestFactory
    Stream<DynamicTest> httpResumeAcrossPhysicalCommitBoundaries() {
        return Stream.of("before", "after").map(mode -> DynamicTest.dynamicTest(mode, () -> {
            try (var server = new Server(exchange -> {
                String after = query(exchange.getRequestURI().getRawQuery()).get("after");
                return "r1".equals(after) ? "[" + event(2, "p2", "INSERT") + "]" : "[" + event(1, "p1", "INSERT") + "," + event(2, "p2", "INSERT") + "]";
            })) {
                String url = "jdbc:h2:file:" + directory.resolve(mode) + ";WRITE_DELAY=0";
                var definition = mapping(server, DatasourceMapping.Mode.POLLING, INCREMENTAL);
                Path log = directory.resolve(mode + ".log");
                var worker = new ProcessBuilder(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                        System.getProperty("java.class.path"), RestCrashWorker.class.getName(), url, definition.connection().url(), mode))
                        .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                try {
                    assertTrue(worker.waitFor(25, TimeUnit.SECONDS));
                    assertEquals(mode.equals("before") ? 111 : 112, worker.exitValue(), Files.readString(log));
                } finally {
                    if (worker.isAlive()) worker.destroyForcibly();
                }
                try (var storage = new JdbcStorageProvider(data(url), DatabaseDialect.h2())) {
                    storage.applySchema(CTX, SCHEMA);
                    var runner = new DatasourceRunner(storage, ConnectorRegistry.rest(), ALLOW);
                    if (mode.equals("before")) assertNull(runner.checkpoint(definition, CTX));
                    else assertEquals(1, runner.checkpoint(definition, CTX).sequence());
                    var resumed = runner.runOnce(definition, CTX, OPTIONS);
                    assertTrue(resumed.failures().isEmpty(), resumed.failures().toString());
                    assertEquals(mode.equals("before") ? 2 : 1, resumed.created());
                    assertEquals(2, runner.checkpoint(definition, CTX).sequence());
                    assertEquals(1, storage.getObject(CTX, "Person", "p1").version());
                }
            }
        }));
    }
}
