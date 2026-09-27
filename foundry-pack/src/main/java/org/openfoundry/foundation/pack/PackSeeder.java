package org.openfoundry.foundation.pack;

import org.openfoundry.foundation.spi.*;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explicit trusted bootstrap operation. Existing seed receipts are replayed; application data is never upserted. */
public final class PackSeeder {
    public SeedResult apply(RequestContext context, LoadedPackBundle bundle, StorageProvider storage) {
        if (context.actorId() == null || !storage.capabilities().transactionalCommandReceipts()) {
            throw new IllegalArgumentException("Seed initialization requires an actor and transactional receipts");
        }
        PackAssetsReader.validate(bundle.assets(), bundle.ontology().schema());
        for (int attempt = 0; attempt < 8; attempt++) {
            try (var tx = storage.beginTransaction(context)) {
                tx.acquireWrite();
                var references = new LinkedHashMap<String, EntityKey>();
                var pending = new ArrayList<Pending>();
                for (var batch : bundle.assets().seeds()) {
                    String key = DomainPackLoader.digest(PropertyValues.canonical(List.of("pack.seed", batch.namespace(), batch.path())));
                    String hash = hash(batch);
                    var receipt = tx.getCommandReceipt(key);
                    if (receipt != null) {
                        if (!receipt.action().equals("pack.seed") || !receipt.requestHash().equals(hash)) {
                            throw new IllegalStateException("Seed definition changed; an explicit data migration is required: " + batch.namespace() + "/" + batch.path());
                        }
                        restoreReferences(receipt.result(), batch.namespace(), references);
                    } else {
                        pending.add(new Pending(batch, key, hash, new ArrayList<>()));
                    }
                }
                int objects = 0, links = 0;
                // All objects are available before any link, including forward references between seed files.
                for (var item : pending) {
                    for (int index = 0; index < item.batch.objects().size(); index++) {
                        var seed = item.batch.objects().get(index);
                        var definition = bundle.ontology().schema().objectTypes().stream().filter(type -> type.name().equals(seed.type())).findFirst().orElseThrow();
                        String primary = definition.properties().stream().filter(field -> field.primary()).findFirst().orElseThrow().name();
                        Object identity = seed.reference() == null ? List.of("position", item.batch.path(), index) : List.of("reference", seed.reference());
                        String id = id(seed.fields().get(primary), item.batch.namespace(), seed.type(), identity);
                        tx.createObject(seed.type(), id, seed.fields());
                        objects++;
                        if (seed.reference() != null) {
                            String reference = item.batch.namespace() + ":" + seed.reference();
                            var key = new EntityKey(seed.type(), id);
                            if (references.putIfAbsent(reference, key) != null) throw new IllegalArgumentException("Duplicate seed reference");
                            item.references.add(Map.of("reference", seed.reference(), "type", seed.type(), "id", id));
                        }
                    }
                }
                for (var item : pending) {
                    for (int index = 0; index < item.batch.links().size(); index++) {
                        var seed = item.batch.links().get(index);
                        var definition = bundle.ontology().schema().linkTypes().stream().filter(type -> type.name().equals(seed.type())).findFirst().orElseThrow();
                        String primary = definition.properties().stream().filter(field -> field.primary()).findFirst().orElseThrow().name();
                        String id = id(seed.fields().get(primary), item.batch.namespace(), seed.type(), List.of("link", item.batch.path(), index));
                        var from = endpoint(item.batch.namespace(), seed.from(), definition.fromType(), references);
                        var to = endpoint(item.batch.namespace(), seed.to(), definition.toType(), references);
                        tx.createLink(seed.type(), id, from, to, seed.fields());
                        links++;
                    }
                    tx.putCommandReceipt(new CommandReceipt(item.key, context.actorId(), "pack.seed", item.hash,
                            Map.of("format", 1, "namespace", item.batch.namespace(), "references", item.references)));
                    String eventId = "seed_" + DomainPackLoader.digest(PropertyValues.canonical(List.of(context.tenantId(), item.key)));
                    var detail = Map.<String, Object>of("namespace", item.batch.namespace(), "file", item.batch.path(),
                            "objects", item.batch.objects().size(), "links", item.batch.links().size());
                    tx.appendAudit(new AuditEntry("audit_" + eventId, Instant.now(), context.tenantId(), context.actorId(), "seed",
                            null, null, "pack.seed", tx.transactionId(), "success", detail));
                    tx.enqueueOutbox(new OutboxEntry(eventId, context.tenantId(), "openfoundry.seed.applied", item.batch.namespace(), Instant.now(), tx.transactionId(), detail));
                }
                tx.commit();
                return new SeedResult(objects, links, references);
            } catch (TransactionConflictException conflict) {
                if (attempt == 7) throw conflict;
            }
        }
        throw new IllegalStateException("Seed transaction retry limit reached");
    }

    private static EntityKey endpoint(String namespace, String reference, String type, Map<String, EntityKey> references) {
        var key = references.get(namespace + ":" + reference);
        if (key == null) key = references.get(reference);
        if (key == null) return new EntityKey(type, reference); // Literal ID: provider verifies its active endpoint.
        if (!key.type().equals(type)) throw new IllegalArgumentException("Seed endpoint type does not match relationship");
        return key;
    }

    private static String id(Object explicit, String namespace, String type, Object reference) {
        if (explicit != null) {
            if (!(explicit instanceof String value) || value.isBlank()) throw new IllegalArgumentException("Seed primary ID must be text");
            return value;
        }
        return "seed_" + DomainPackLoader.digest(PropertyValues.canonical(List.of(namespace, type, reference)));
    }

    private static String hash(PackAssets.SeedBatch seed) {
        return DomainPackLoader.digest(PropertyValues.canonical(Map.of("namespace", seed.namespace(), "path", seed.path(),
                "objects", seed.objects().stream().map(object -> {
                    var value = new LinkedHashMap<String, Object>();
                    value.put("type", object.type()); value.put("ref", object.reference()); value.put("fields", object.fields());
                    return value;
                }).toList(), "links", seed.links().stream().map(link -> Map.of("type", link.type(), "from", link.from(), "to", link.to(), "fields", link.fields())).toList())));
    }

    private static void restoreReferences(Map<String, Object> result, String namespace, Map<String, EntityKey> references) {
        if (!(result.get("format") instanceof Integer || result.get("format") instanceof Long)
                || ((Number) result.get("format")).longValue() != 1 || !namespace.equals(result.get("namespace"))
                || !(result.get("references") instanceof List<?> rows)) throw new IllegalStateException("Unknown seed receipt format");
        for (var raw : rows) {
            if (!(raw instanceof Map<?, ?> row) || !(row.get("reference") instanceof String reference)
                    || !(row.get("type") instanceof String type) || !(row.get("id") instanceof String id)) throw new IllegalStateException("Invalid seed receipt reference");
            if (references.putIfAbsent(namespace + ":" + reference, new EntityKey(type, id)) != null) throw new IllegalStateException("Conflicting seed receipt reference");
        }
    }

    public record SeedResult(int createdObjects, int createdLinks, Map<String, EntityKey> references) {
        public SeedResult { references = Map.copyOf(references); }
    }
    private record Pending(PackAssets.SeedBatch batch, String key, String hash, List<Map<String, Object>> references) {}
}
