package org.openfoundry.foundation.events;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class InMemoryAuditStore implements AuditStore {
    private final List<AuditRecord> records = new ArrayList<>();

    @Override
    public synchronized void append(AuditRecord record) {
        if (records.stream().anyMatch(existing -> existing.id().equals(record.id()))) {
            throw new IllegalArgumentException("duplicate audit id: " + record.id());
        }
        records.add(record);
    }

    @Override
    public synchronized List<AuditRecord> query(String tenantId, String objectType, String objectId,
                                                Instant from, Instant to, int limit) {
        return records.stream()
                .filter(record -> record.tenantId().equals(tenantId))
                .filter(record -> objectType == null || objectType.equals(record.objectType()))
                .filter(record -> objectId == null || objectId.equals(record.objectId()))
                .filter(record -> from == null || !record.timestamp().isBefore(from))
                .filter(record -> to == null || !record.timestamp().isAfter(to))
                .sorted((left, right) -> right.timestamp().compareTo(left.timestamp()))
                .limit(limit)
                .toList();
    }
}
