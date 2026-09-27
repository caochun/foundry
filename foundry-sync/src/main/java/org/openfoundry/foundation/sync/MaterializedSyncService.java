package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.ObjectRecord;
import org.openfoundry.foundation.spi.RequestContext;
import org.openfoundry.foundation.spi.StorageProvider;
import org.openfoundry.foundation.spi.Transaction;

import java.util.ArrayList;
import java.util.List;

/** Applies mapped source records as governed materialized state changes. */
public final class MaterializedSyncService {
    private final StorageProvider storage;

    public MaterializedSyncService(StorageProvider storage) {
        this.storage = storage;
    }

    public SyncResult sync(Connector connector, SourceQuery query, MappingConfig mapping,
                           RequestContext context) {
        RecordMapper mapper = new RecordMapper(mapping);
        List<SyncFailure> failures = new ArrayList<>();
        MutableCounts counts = new MutableCounts();
        connector.read(query).forEach(source -> {
            try {
                MappedRecord mapped = mapper.map(source);
                ObjectRecord existing = storage.getObject(context, mapped.key().type(), mapped.key().id());
                try (Transaction transaction = storage.beginTransaction(context)) {
                    String applied = "NONE";
                    if ("DELETE".equalsIgnoreCase(mapped.operation())) {
                        if (existing != null && !existing.isDeleted()) {
                            transaction.deleteObject(existing.type(), existing.id(), existing.version());
                            applied = "DELETED";
                        }
                    } else if (existing == null) {
                        transaction.createObject(mapped.key().type(), mapped.key().id(), mapped.properties());
                        applied = "CREATED";
                    } else {
                        var patch = new java.util.LinkedHashMap<>(mapped.properties());
                        patch.entrySet().removeIf(entry -> existing.properties().containsKey(entry.getKey())
                                && java.util.Objects.equals(existing.properties().get(entry.getKey()), entry.getValue()));
                        if (!patch.isEmpty()) {
                            transaction.updateObject(existing.type(), existing.id(), patch, existing.version());
                            applied = "UPDATED";
                        }
                    }
                    transaction.commit();
                    switch (applied) {
                        case "CREATED" -> counts.created++;
                        case "UPDATED" -> counts.updated++;
                        case "DELETED" -> counts.deleted++;
                        default -> { }
                    }
                }
            } catch (RuntimeException exception) {
                failures.add(new SyncFailure(source.sourceSystem(), source.sourceRecordId(), exception.getMessage()));
            }
        });
        return new SyncResult(counts.created, counts.updated, counts.deleted, failures);
    }

    public record SyncResult(int created, int updated, int deleted, List<SyncFailure> failures) {
        public SyncResult { failures = List.copyOf(failures); }
    }

    public record SyncFailure(String sourceSystem, String sourceRecordId, String reason) {}

    private static final class MutableCounts {
        private int created;
        private int updated;
        private int deleted;
    }
}
