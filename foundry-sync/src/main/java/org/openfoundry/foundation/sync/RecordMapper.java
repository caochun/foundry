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
        config.sourceToTarget().forEach((sourceField, targetField) -> {
            if (source.data().containsKey(sourceField)) properties.put(targetField, source.data().get(sourceField));
        });
        return new MappedRecord(new EntityKey(config.objectType(), canonicalId(rawId)),
                properties, source.provenance(), source.operation());
    }

    static String canonicalId(Object rawId) {
        if (rawId instanceof String text && !text.isBlank()) return text;
        if (rawId instanceof Number number) return new java.math.BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        throw new IllegalArgumentException("Source primary key must be a nonblank string or number");
    }
}
