package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.Provenance;

import java.util.Map;

public record MappedRecord(EntityKey key, Map<String, Object> properties,
                          Provenance provenance, String operation) {
    public MappedRecord {
        properties = Map.copyOf(properties);
    }
}
