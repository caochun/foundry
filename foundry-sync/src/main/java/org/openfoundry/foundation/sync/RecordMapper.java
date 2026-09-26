package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.EntityKey;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RecordMapper {
    private final MappingConfig config;

    public RecordMapper(MappingConfig config) {
        this.config = config;
    }

    public MappedRecord map(SourceRecord source) {
        Object rawId = source.data().get(config.primaryKeyField());
        if (rawId == null) throw new IllegalArgumentException("source primary key is missing: " + config.primaryKeyField());
        Map<String, Object> properties = new LinkedHashMap<>();
        config.sourceToTarget().forEach((sourceField, targetField) -> properties.put(targetField, source.data().get(sourceField)));
        return new MappedRecord(new EntityKey(config.objectType(), String.valueOf(rawId)),
                properties, source.provenance(), source.operation());
    }
}
