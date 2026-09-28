package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.LineageValues;
import org.openfoundry.foundation.spi.Provenance;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/** Read-through, TTL-cached source projection. It never writes ontology facts or history. */
public final class OverlayEngine implements AutoCloseable {
    public record OverlayObject(String objectType, String id, Map<String, Object> properties,
                                OverlayLineage lineage, Instant cachedAt, Duration ttl) {
        public OverlayObject {
            properties = PropertyValues.immutableMap(properties);
            Objects.requireNonNull(lineage);
            Objects.requireNonNull(cachedAt);
            Objects.requireNonNull(ttl);
            if (ttl.isNegative() || ttl.isZero()) throw new IllegalArgumentException("Overlay TTL must be positive");
        }
    }

    public record OverlayLineage(String connector, String sourceSystem, String sourceRecordId, Provenance provenance) {
        public OverlayLineage {
            if (connector == null || connector.isBlank() || sourceSystem == null || sourceSystem.isBlank()) {
                throw new IllegalArgumentException("Overlay lineage is required");
            }
        }
    }

    public static final class OverlayReadOnlyException extends UnsupportedOperationException {
        public OverlayReadOnlyException() {
            super("Overlay objects are read-only");
        }
    }

    private record Entry(OverlayObject value, Instant expiresAt) {}

    private final DatasourceMapping definition;
    private final Connector connector;
    private final RecordMapper mapper;
    private final Duration ttl;
    private final Clock clock;
    private final Map<String, Entry> cache = new LinkedHashMap<>();
    private boolean closed;

    public OverlayEngine(DatasourceMapping definition, Connector connector) {
        this(definition, connector, Clock.systemUTC());
    }

    public OverlayEngine(DatasourceMapping definition, Connector connector, Clock clock) {
        this.definition = Objects.requireNonNull(definition);
        this.connector = Objects.requireNonNull(connector);
        this.clock = Objects.requireNonNull(clock);
        if (definition.sync().mode() != DatasourceMapping.Mode.OVERLAY) {
            throw new IllegalArgumentException("Overlay engine requires OVERLAY mapping mode");
        }
        if (Boolean.TRUE.equals(definition.sync().writeback())) {
            throw new IllegalArgumentException("Overlay writeback is not enabled by this read-only engine");
        }
        if (definition.mapping().links() != null && !definition.mapping().links().isEmpty()) {
            throw new UnsupportedOperationException("Overlay relationship projection requires a governed relationship reader");
        }
        String raw = definition.sync().cacheTTL() == null ? "PT5M" : definition.sync().cacheTTL();
        ttl = Duration.parse(raw);
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofDays(30)) > 0) {
            throw new IllegalArgumentException("Overlay TTL must be positive and at most 30 days");
        }
        mapper = new RecordMapper(definition.mapping());
    }

    public synchronized OverlayObject get(Map<String, Object> key) {
        requireOpen();
        Map<String, Object> requested = PropertyValues.immutableMap(key);
        var normalizedKey = new LinkedHashMap<String, Object>();
        requested.forEach((name, value) -> normalizedKey.put(name, canonical(value)));
        String cacheKey = LineageValues.hash(true, Map.of("table", definition.connection().table(), "key", normalizedKey));
        Instant now = clock.instant();
        Entry cached = cache.get(cacheKey);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.value();
        }
        MappedRecord found = null;
        Stream<SourceRecord> source = connector instanceof ManagedConnector managed
                ? managed.fullExtract(ManagedConnector.ExtractOptions.defaults())
                : connector.read(new SourceQuery(definition.connection().table(), Map.of("key", requested)));
        try (Stream<SourceRecord> records = source) {
            var iterator = records.iterator();
            while (iterator.hasNext()) {
                SourceRecord record = iterator.next();
                if (matches(record.data(), requested)) {
                    found = mapper.map(record);
                    break;
                }
            }
        }
        if (found == null || found.operation().equals("DELETE")) {
            cache.remove(cacheKey);
            return null;
        }
        var value = new OverlayObject(found.key().type(), found.key().id(), found.properties(),
                new OverlayLineage(definition.connector(), definition.datasource(), found.key().id(), found.provenance()), now, ttl);
        cache.put(cacheKey, new Entry(value, now.plus(ttl)));
        return value;
    }

    public synchronized int cacheSize() {
        return cache.size();
    }

    public synchronized void clearCache() {
        cache.clear();
    }

    public synchronized void mutate(String id, Map<String, Object> values) {
        requireOpen();
        throw new OverlayReadOnlyException();
    }

    @Override
    public synchronized void close() {
        closed = true;
        cache.clear();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Overlay engine is closed");
    }

    private static boolean matches(Map<String, Object> values, Map<String, Object> requested) {
        return requested.entrySet().stream().allMatch(entry -> Objects.equals(canonical(values.get(entry.getKey())), canonical(entry.getValue())));
    }

    private static String canonical(Object value) {
        if (value instanceof Number number) return new java.math.BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        return Objects.toString(value, null);
    }
}
