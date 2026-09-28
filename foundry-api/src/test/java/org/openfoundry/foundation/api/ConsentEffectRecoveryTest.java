package org.openfoundry.foundation.api;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.actions.ActionExecutor;
import org.openfoundry.foundation.spi.ConsentRecord;
import org.openfoundry.foundation.storage.jdbc.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.openfoundry.foundation.api.ConsentEffectTest.*;
import static org.openfoundry.foundation.api.ConsentEffectCrashWorker.*;

class ConsentEffectRecoveryTest {
    @TempDir Path directory;

    @TestFactory
    Stream<DynamicTest> durableConsentAndCompensation() {
        return Stream.of("before-effects", "after-effects", "before-compensation", "after-compensation")
                .map(mode -> DynamicTest.dynamicTest(mode, () -> recover(mode)));
    }

    private void recover(String mode) throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve(mode) + ";WRITE_DELAY=0";
        Path log = directory.resolve(mode + ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), ConsentEffectCrashWorker.class.getName(), url, mode)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(process.waitFor(25, TimeUnit.SECONDS), "Worker timeout");
            assertEquals(mode.startsWith("before") ? 71 : 72, process.exitValue(), Files.readString(log));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        var data = new JdbcDataSource();
        data.setURL(url);
        var clock = Clock.fixed(START.plusSeconds(30), ZoneOffset.UTC);
        var storage = new JdbcStorageProvider(data, DatabaseDialect.h2(), clock);
        storage.applySchema(CONTEXT, SCHEMA);
        var consent = new JdbcConsentStore(data, DatabaseDialect.h2(), clock);
        int committedRecords = mode.equals("before-effects") ? 0 : mode.equals("after-compensation") ? 2 : 1;
        assertEquals(committedRecords, consent.snapshot(CONTEXT, SUBJECT).records().size());
        assertEquals(committedRecords, consent.auditHistory(CONTEXT, SUBJECT).size());
        var object = storage.getObject(CONTEXT, "Person", "new");
        if (committedRecords == 0) assertNull(object);
        else assertEquals(mode.equals("after-compensation"), object.isDeleted());
        var executor = new ActionExecutor().withAuthorization((ctx, actor, definition, parameters) -> true)
                .withConsentStore(consent, PURPOSE, Set.of("Person"), Set.of(PURPOSE))
                .withSideEffects(invocation -> { throw new IllegalStateException("delivery failed"); }, clock, Duration.ofSeconds(10));
        var parameters = Map.<String, Object>of("id", "new", "name", "New", "consent", true);
        var result = executor.execute(MANIFEST, SCHEMA.actionTypes().getFirst(), CONTEXT, ACTOR, parameters, "crash", storage);
        assertEquals("ROLLED_BACK", result.status());
        assertTrue(storage.getObject(CONTEXT, "Person", "new").isDeleted());
        assertEquals(List.of(ConsentRecord.Decision.GRANT, ConsentRecord.Decision.DENY),
                consent.snapshot(CONTEXT, SUBJECT).records().stream().map(ConsentRecord::decision).toList());
        assertEquals(2, consent.auditHistory(CONTEXT, SUBJECT).size());
        assertEquals(result, executor.execute(MANIFEST, SCHEMA.actionTypes().getFirst(), CONTEXT, ACTOR, parameters, "crash", storage));
        assertEquals(2, consent.snapshot(CONTEXT, SUBJECT).records().size());
        assertTrue(storage.pendingActions(CONTEXT, clock.instant(), 10).isEmpty());
    }
}
