package org.openfoundry.foundation.pack;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.openfoundry.foundation.schema.OdlParser;
import org.openfoundry.foundation.schema.SchemaCompiler;
import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.storage.jdbc.*;
import org.openfoundry.foundation.storage.memory.InMemoryStorageProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class PackSeederTest {
    @TempDir Path temporary;
    private static final RequestContext CONTEXT = RequestContext.system("tenant", "bootstrap");
    private static final String ODL = """
            extend schema @namespace(name: "seeds", version: "1.0.0")
            type Item @objectType { id: ID! @primary name: String! state: String @default(value: "NEW") createdAt: DateTime! @readonly }
            type Related @linkType(from: "Item", to: "Item", cardinality: MANY_TO_MANY) { id: ID! @primary }
            """;

    @TestFactory
    Stream<DynamicTest> seedTransactionsAcrossProviders() {
        return Stream.of("memory", "jdbc").flatMap(provider -> Stream.of(
                test(provider, "replay preserves later business edits and isolates tenants", this::replay),
                test(provider, "forward references and failure roll back the whole bootstrap", this::rollback),
                test(provider, "changed definitions are migrations and new files reuse old references", this::evolution),
                test(provider, "two concurrent bootstraps commit the same seeds once", this::concurrent)));
    }

    private DynamicTest test(String provider, String name, Consumer<StorageProvider> verify) {
        return DynamicTest.dynamicTest(provider + " " + name, () -> {
            StorageProvider storage;
            if (provider.equals("memory")) storage = new InMemoryStorageProvider();
            else storage = provider("jdbc:h2:mem:seeds_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            storage.applySchema(CONTEXT, new SchemaCompiler().compile(new OdlParser().parse(ODL)).schema());
            try { verify.accept(storage); }
            finally { if (storage instanceof AutoCloseable resource) resource.close(); }
        });
    }

    private void replay(StorageProvider storage) {
        var bundle = bundle(List.of(batch("seed.yaml", List.of(object("a", "A"), object("b", "B")), List.of(link("a", "b")))));
        var first = new PackSeeder().apply(CONTEXT, bundle, storage);
        assertEquals(2, first.createdObjects());
        assertEquals(1, first.createdLinks());
        var key = first.references().get("seeds:a");
        var item = storage.getObject(CONTEXT, key.type(), key.id());
        assertEquals("NEW", item.properties().get("state"));
        assertNotNull(item.properties().get("createdAt"));
        try (var tx = storage.beginTransaction(CONTEXT)) {
            tx.updateObject(key.type(), key.id(), Map.of("name", "EDITED"), item.version());
            tx.commit();
        }
        var replay = new PackSeeder().apply(CONTEXT, bundle, storage);
        assertEquals(0, replay.createdObjects());
        assertEquals(0, replay.createdLinks());
        assertEquals(first.references(), replay.references());
        assertEquals("EDITED", storage.getObject(CONTEXT, key.type(), key.id()).properties().get("name"));
        var other = RequestContext.system("other-tenant", "bootstrap");
        assertEquals(2, new PackSeeder().apply(other, bundle, storage).createdObjects());
        assertEquals("A", storage.getObject(other, key.type(), key.id()).properties().get("name"));
        assertThrows(SecurityException.class, () -> new PackSeeder().apply(RequestContext.system("tenant", "another-actor"), bundle, storage));
    }

    private void rollback(StorageProvider storage) {
        var invalid = bundle(List.of(batch("first.yaml", List.of(object("a", "A")), List.of(link("a", "b"))),
                batch("second.yaml", List.of(object("b", "B")), List.of(link("a", "missing")))));
        assertThrows(RuntimeException.class, () -> new PackSeeder().apply(CONTEXT, invalid, storage));
        assertTrue(storage.queryObjects(CONTEXT, "Item", QueryOptions.defaults()).isEmpty());
        var valid = bundle(List.of(batch("first.yaml", List.of(object("a", "A")), List.of(link("a", "b"))),
                batch("second.yaml", List.of(object("b", "B")), List.of())));
        var result = new PackSeeder().apply(CONTEXT, valid, storage);
        assertEquals(2, result.createdObjects());
        assertEquals(1, result.createdLinks());
        var key = result.references().get("seeds:a");
        assertEquals(1, storage.getEntityHistory(CONTEXT, key).size());
    }

    private void evolution(StorageProvider storage) {
        var initial = batch("initial.yaml", List.of(object("a", "A")), List.of());
        var seeded = new PackSeeder().apply(CONTEXT, bundle(List.of(initial)), storage);
        var altered = batch("initial.yaml", List.of(object("a", "Changed")), List.of());
        assertThrows(IllegalStateException.class, () -> new PackSeeder().apply(CONTEXT, bundle(List.of(altered)), storage));
        assertEquals("A", storage.getObject(CONTEXT, "Item", seeded.references().get("seeds:a").id()).properties().get("name"));
        var added = batch("added.yaml", List.of(object("b", "B")), List.of(link("seeds:a", "b")));
        var extended = new PackSeeder().apply(CONTEXT, bundle(List.of(initial, added)), storage);
        assertEquals(1, extended.createdObjects());
        assertEquals(1, extended.createdLinks());
        assertEquals(seeded.references().get("seeds:a"), extended.references().get("seeds:a"));
    }

    private void concurrent(StorageProvider storage) {
        var bundle = bundle(List.of(batch("seed.yaml", List.of(object("a", "A")), List.of())));
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = java.util.stream.IntStream.range(0, 2).mapToObj(i -> executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, java.util.concurrent.TimeUnit.SECONDS));
                return new PackSeeder().apply(CONTEXT, bundle, storage);
            })).toList();
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            var first = futures.getFirst().get(20, java.util.concurrent.TimeUnit.SECONDS);
            var second = futures.getLast().get(20, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(1, first.createdObjects() + second.createdObjects());
            assertEquals(first.references(), second.references());
            assertEquals(1, storage.queryObjects(CONTEXT, "Item", QueryOptions.defaults()).size());
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    @Test
    void receiptsAndReferenceIdsSurviveProviderRecreation() {
        String url = "jdbc:h2:file:" + temporary.resolve("seeds") + ";WRITE_DELAY=0";
        var bundle = bundle(List.of(batch("seed.yaml", List.of(object("a", "A")), List.of())));
        var first = new PackSeeder().apply(CONTEXT, bundle, provider(url));
        var recovered = new PackSeeder().apply(CONTEXT, bundle, provider(url));
        assertEquals(0, recovered.createdObjects());
        assertEquals(first.references(), recovered.references());
    }

    private static JdbcStorageProvider provider(String url) {
        var data = new JdbcDataSource();
        data.setURL(url);
        var storage = new JdbcStorageProvider(data, DatabaseDialect.h2());
        storage.applySchema(CONTEXT, new OdlParser().parse(ODL));
        return storage;
    }
    private static PackAssets.SeedObject object(String ref, String name) { return new PackAssets.SeedObject("Item", ref, Map.of("name", name)); }
    private static PackAssets.SeedLink link(String from, String to) { return new PackAssets.SeedLink("Related", from, to, Map.of()); }
    private static PackAssets.SeedBatch batch(String path, List<PackAssets.SeedObject> objects, List<PackAssets.SeedLink> links) {
        return new PackAssets.SeedBatch("seeds", path, objects, links);
    }
    private static LoadedPackBundle bundle(List<PackAssets.SeedBatch> batches) {
        return new LoadedPackBundle(List.of(), new SchemaCompiler().compile(new OdlParser().parse(ODL)), Map.of(), Map.of(),
                new PackAssets(Map.of(), List.of(), batches, List.of()), Set.of(), "fixture");
    }
}
