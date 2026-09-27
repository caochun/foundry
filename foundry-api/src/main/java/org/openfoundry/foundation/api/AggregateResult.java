package org.openfoundry.foundation.api;

import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AggregateResult(List<Group> groups, int totalGroups) {
    public AggregateResult { groups = List.copyOf(groups); }

    public record Group(Map<String, Object> keys, Map<String, Number> values) {
        public Group {
            keys = PropertyValues.immutableMap(keys);
            // Empty numerical aggregates retain null, rather than becoming zero or disappearing.
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }
    }
}
