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
                    if ("DELETE".equalsIgnoreCase(mapped.operation())) {
                        if (existing != null && !existing.isDeleted()) {
                            transaction.deleteObject(existing.type(), existing.id(), existing.version());
                            counts.deleted++;
                        }
                    } else if (existing == null) {
                        transaction.createObject(mapped.key().type(), mapped.key().id(), mapped.properties());
                        counts.created++;
                    } else {
                        transaction.updateObject(existing.type(), existing.id(), mapped.properties(), existing.version());
                        counts.updated++;
                    }
                    transaction.commit();
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
