package org.openfoundry.foundation.events;

import java.time.Instant;
import java.util.List;

public interface AuditStore {
    void append(AuditRecord record);

    List<AuditRecord> query(String tenantId, String objectType, String objectId,
                            Instant from, Instant to, int limit);
}
