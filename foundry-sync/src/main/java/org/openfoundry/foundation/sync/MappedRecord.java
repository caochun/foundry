package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.Provenance;

import java.util.Map;

public record MappedRecord(EntityKey key, Map<String, Object> properties,
                          Provenance provenance, String operation, java.util.List<MappedLink> links) {
    public MappedRecord(EntityKey key, Map<String, Object> properties, Provenance provenance, String operation) {
        this(key, properties, provenance, operation, java.util.List.of());
    }

    public MappedRecord {
        properties = org.openfoundry.foundation.spi.schema.PropertyValues.immutableMap(properties);
        links = java.util.List.copyOf(links);
    }
}
