package org.openfoundry.foundation.sync;

import org.openfoundry.foundation.spi.LineageValues;
import org.openfoundry.foundation.spi.schema.PropertyValues;

import java.time.Instant;
import java.util.*;

/** Field-level source policy. Equal values may corroborate an assertion without transferring its authority. */
public final class ConflictResolver {
    public enum Strategy { LAST_WRITE_WINS, SOURCE_PRIORITY, ACTION_PRIORITY }

    public record IncomingValue(Object value, String source, Instant timestamp, boolean action, boolean present) {
        public IncomingValue(Object value, String source, Instant timestamp, boolean action) { this(value, source, timestamp, action, true); }
        public IncomingValue {
            if (source == null || source.isBlank()) throw new IllegalArgumentException("Incoming source is required");
            Objects.requireNonNull(timestamp);
            value = present ? PropertyValues.immutableValue(value) : null;
        }
    }
    public record ExistingValue(Object value, String source, Instant timestamp, boolean action, boolean present) {
        public ExistingValue(Object value, String source, Instant timestamp, boolean action) { this(value, source, timestamp, action, true); }
        public ExistingValue { value = present ? PropertyValues.immutableValue(value) : null; }
    }
    public record Resolution(Map<String, Object> accepted, Map<String, String> reasons, Set<String> provenanceFields, Set<String> conflicts) {
        public Resolution(Map<String, Object> accepted, Map<String, String> reasons) { this(accepted, reasons, accepted.keySet(), Set.of()); }
        public Resolution {
            accepted = PropertyValues.immutableMap(accepted);
            reasons = Map.copyOf(reasons);
            provenanceFields = Set.copyOf(provenanceFields);
            conflicts = Set.copyOf(conflicts);
        }
    }

    private final Strategy defaultStrategy;
    private final Map<String, Strategy> fieldStrategies;
    private final Map<String, Integer> sourcePriority;
    private final Map<String, Map<String, Integer>> fieldPriorities;

    public ConflictResolver(Strategy defaultStrategy, Map<String, Strategy> fieldStrategies, Map<String, Integer> sourcePriority) {
        this(defaultStrategy, fieldStrategies, sourcePriority, Map.of());
    }

    public ConflictResolver(Strategy defaultStrategy, Map<String, Strategy> fieldStrategies,
                            Map<String, Integer> sourcePriority, Map<String, Map<String, Integer>> fieldPriorities) {
        this.defaultStrategy = Objects.requireNonNull(defaultStrategy);
        this.fieldStrategies = Map.copyOf(fieldStrategies);
        this.sourcePriority = ranks(sourcePriority);
        var copy = new LinkedHashMap<String, Map<String, Integer>>();
        fieldPriorities.forEach((field, priority) -> copy.put(field, ranks(priority)));
        this.fieldPriorities = Map.copyOf(copy);
    }

    public Resolution resolve(Map<String, IncomingValue> incoming, Map<String, ExistingValue> existing) {
        var accepted = new LinkedHashMap<String, Object>();
        var reasons = new LinkedHashMap<String, String>();
        var owners = new HashSet<String>();
        var conflicts = new HashSet<String>();
        incoming.forEach((field, candidate) -> {
            var current = existing.get(field);
            boolean equal = current != null && LineageValues.hash(candidate.present(), candidate.value()).equals(LineageValues.hash(current.present(), current.value()));
            boolean preferred = current == null || preferIncoming(field, candidate, current);
            if (current == null || equal || preferred) accepted.put(field, candidate.value());
            if (preferred) owners.add(field);
            if (current == null) reasons.put(field, "No existing assertion");
            else if (equal) reasons.put(field, preferred ? "Same value; incoming source accepted" : "Same value; existing authority retained");
            else {
                conflicts.add(field);
                reasons.put(field, fieldStrategies.getOrDefault(field, defaultStrategy) + (preferred ? ": incoming accepted" : ": existing retained"));
            }
        });
        return new Resolution(accepted, reasons, owners, conflicts);
    }

    public boolean preferIncoming(String field, IncomingValue candidate, ExistingValue current) {
        return switch (fieldStrategies.getOrDefault(field, defaultStrategy)) {
            case LAST_WRITE_WINS -> newer(candidate, current);
            case SOURCE_PRIORITY -> {
                var ranks = fieldPriorities.getOrDefault(field, sourcePriority);
                long incomingRank = ranks.containsKey(candidate.source()) ? ranks.get(candidate.source()).longValue() : Long.MAX_VALUE;
                long currentRank = current.source() != null && ranks.containsKey(current.source()) ? ranks.get(current.source()).longValue() : Long.MAX_VALUE;
                yield incomingRank == currentRank ? newer(candidate, current) : incomingRank < currentRank;
            }
            case ACTION_PRIORITY -> candidate.action() == current.action() ? newer(candidate, current) : candidate.action();
        };
    }

    public Set<String> configuredFields() {
        var fields = new HashSet<>(fieldStrategies.keySet());
        fields.addAll(fieldPriorities.keySet());
        return Set.copyOf(fields);
    }

    public Map<String, Object> configuration() {
        var rules = new LinkedHashMap<String, String>();
        fieldStrategies.forEach((field, strategy) -> rules.put(field, strategy.name()));
        return PropertyValues.immutableMap(Map.of("default", defaultStrategy.name(), "fields", rules, "priority", sourcePriority, "fieldPriorities", fieldPriorities));
    }

    private static boolean newer(IncomingValue candidate, ExistingValue current) {
        return current.timestamp() == null || !candidate.timestamp().isBefore(current.timestamp());
    }

    private static Map<String, Integer> ranks(Map<String, Integer> values) {
        var result = Map.copyOf(values);
        if (result.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank() || entry.getValue() < 0)) throw new IllegalArgumentException("Invalid source priority");
        return result;
    }
}
