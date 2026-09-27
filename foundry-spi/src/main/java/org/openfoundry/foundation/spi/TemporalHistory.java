package org.openfoundry.foundation.spi;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Provider-neutral selection of append-only state assertions. Deletions participate before filtering. */
public final class TemporalHistory {
    private TemporalHistory() {}

    public static HistorySnapshot at(List<HistorySnapshot> history, Instant validTime, Instant recordedTime) {
        Objects.requireNonNull(validTime, "valid time");
        Objects.requireNonNull(recordedTime, "recorded time");
        return history.stream().filter(snapshot -> !snapshot.recordedAt().isAfter(recordedTime))
                .filter(snapshot -> !snapshot.validFrom().isAfter(validTime))
                .filter(snapshot -> snapshot.validTo() == null || validTime.isBefore(snapshot.validTo()))
                .max(Comparator.comparing(HistorySnapshot::recordedAt).thenComparingLong(HistorySnapshot::version)).orElse(null);
    }

    public static Instant effectiveAt(Instant requested, Instant recordedAt, List<HistorySnapshot> history) {
        if (requested != null && !requested.equals(requested.truncatedTo(java.time.temporal.ChronoUnit.MICROS))) {
            throw new IllegalArgumentException("Effective time precision is limited to microseconds");
        }
        Instant effective = requested == null ? recordedAt : requested;
        if (effective.isAfter(recordedAt)) throw new IllegalArgumentException("Future-effective writes require a separate scheduling contract");
        for (var previous : history) {
            if (previous.recordedAt().isAfter(recordedAt)) throw new IllegalStateException("Storage clock moved backwards");
            if (previous.validFrom().isAfter(effective)) throw new IllegalArgumentException("Out-of-order correction requires an explicit correction contract");
        }
        return effective;
    }

    public static boolean active(HistorySnapshot snapshot) {
        return snapshot != null && snapshot.operation() != EntityOperation.DELETED;
    }

    public static EntityKey from(HistorySnapshot snapshot) {
        return new EntityKey(snapshot.state().get("_fromType").toString(), snapshot.state().get("_fromId").toString());
    }

    public static EntityKey to(HistorySnapshot snapshot) {
        return new EntityKey(snapshot.state().get("_toType").toString(), snapshot.state().get("_toId").toString());
    }

    public static Map<EntityKey, List<HistorySnapshot>> group(List<HistorySnapshot> snapshots) {
        var grouped = new HashMap<EntityKey, List<HistorySnapshot>>();
        for (var snapshot : snapshots) grouped.computeIfAbsent(snapshot.key(), key -> new ArrayList<>()).add(snapshot);
        return grouped;
    }

    public static ObjectRecord object(String tenant, HistorySnapshot snapshot, Instant createdAt) {
        return new ObjectRecord(tenant, snapshot.key().type(), snapshot.key().id(), snapshot.version(), createdAt,
                snapshot.recordedAt(), active(snapshot) ? null : snapshot.validFrom(), snapshot.transactionId(), snapshot.actionId(), snapshot.state());
    }

    public static LinkRecord link(String tenant, HistorySnapshot snapshot, List<HistorySnapshot> history, Instant recordedTime) {
        var creation = history.stream().min(Comparator.comparingLong(HistorySnapshot::version)).orElseThrow();
        Instant ended = history.stream().filter(event -> event.operation() == EntityOperation.DELETED)
                .filter(event -> !event.recordedAt().isAfter(recordedTime)).map(HistorySnapshot::validFrom)
                .min(Instant::compareTo).orElse(null);
        var properties = new LinkedHashMap<>(snapshot.state());
        for (String key : List.of("_fromType", "_fromId", "_toType", "_toId")) properties.remove(key);
        return new LinkRecord(tenant, snapshot.key().type(), snapshot.key().id(), from(snapshot), to(snapshot), snapshot.version(),
                creation.recordedAt(), snapshot.recordedAt(), active(snapshot) ? null : snapshot.validFrom(), creation.validFrom(), ended,
                snapshot.transactionId(), snapshot.actionId(), properties);
    }

    public static Instant createdAt(List<HistorySnapshot> history) {
        return history.stream().min(Comparator.comparingLong(HistorySnapshot::version)).orElseThrow().recordedAt();
    }

    public static <T> List<T> page(List<T> values, QueryOptions options) {
        int start = Math.min(options.offset(), values.size());
        int end = (int) Math.min((long) start + options.limit(), values.size());
        return List.copyOf(values.subList(start, end));
    }

    public static TraversalResult traverse(List<HistorySnapshot> objectHistory, List<HistorySnapshot> linkHistory,
                                           EntityKey start, List<TraversalStep> path, Instant validTime,
                                           Instant recordedTime, QueryOptions options) {
        if (path.size() > 10) throw new IllegalArgumentException("traversal depth exceeds 10");
        if (options.asOfValidTime() != null && (!options.asOfValidTime().equals(validTime) || !options.asOfRecordedTime().equals(recordedTime))) {
            throw new IllegalArgumentException("Conflicting traversal timestamps");
        }
        var objects = group(objectHistory);
        if (!active(at(objects.getOrDefault(start, List.of()), validTime, recordedTime))) return new TraversalResult(List.of(), List.of());
        var selectedLinks = group(linkHistory).values().stream().map(history -> at(history, validTime, recordedTime))
                .filter(TemporalHistory::active).sorted(Comparator.comparing((HistorySnapshot s) -> s.key().type()).thenComparing(s -> s.key().id())).toList();
        List<EntityKey> frontier = List.of(start);
        var edges = new LinkedHashMap<EntityKey, HistorySnapshot>();
        for (var step : path) {
            var next = new java.util.TreeSet<EntityKey>(Comparator.comparing(EntityKey::type).thenComparing(EntityKey::id));
            for (var endpoint : frontier) for (var link : selectedLinks) {
                if (!link.key().type().equals(step.linkType())) continue;
                EntityKey source = step.direction() == StorageProvider.Direction.OUTBOUND ? from(link) : to(link);
                EntityKey target = step.direction() == StorageProvider.Direction.OUTBOUND ? to(link) : from(link);
                if (source.equals(endpoint) && active(at(objects.getOrDefault(target, List.of()), validTime, recordedTime))) {
                    next.add(target);
                    edges.put(link.key(), link);
                    if (edges.size() > 10000 || next.size() > 10000) throw new IllegalArgumentException("Temporal traversal exceeds 10000 nodes or edges");
                }
            }
            frontier = List.copyOf(next);
            if (frontier.isEmpty()) break;
        }
        return new TraversalResult(page(frontier, options), List.copyOf(edges.values()));
    }
}
