package org.openfoundry.foundation.sync;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConflictResolver {
    public enum Strategy { LAST_WRITE_WINS, SOURCE_PRIORITY, ACTION_PRIORITY }

    public record IncomingValue(Object value, String source, Instant timestamp, boolean action) {}
    public record ExistingValue(Object value, String source, Instant timestamp, boolean action) {}
    public record Resolution(Map<String, Object> accepted, Map<String, String> reasons) {}

    private final Strategy defaultStrategy;
    private final Map<String, Strategy> fieldStrategies;
    private final Map<String, Integer> sourcePriority;

    public ConflictResolver(Strategy defaultStrategy, Map<String, Strategy> fieldStrategies,
                            Map<String, Integer> sourcePriority) {
        this.defaultStrategy = defaultStrategy;
        this.fieldStrategies = Map.copyOf(fieldStrategies);
        this.sourcePriority = Map.copyOf(sourcePriority);
    }

    public Resolution resolve(Map<String, IncomingValue> incoming, Map<String, ExistingValue> existing) {
        Map<String, Object> accepted = new LinkedHashMap<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        incoming.forEach((field, candidate) -> {
            ExistingValue current = existing.get(field);
            if (current == null || current.value() == null || java.util.Objects.equals(current.value(), candidate.value())) {
                accepted.put(field, candidate.value());
                reasons.put(field, "accepted without conflict");
                return;
            }
            Strategy strategy = fieldStrategies.getOrDefault(field, defaultStrategy);
            boolean takeIncoming = switch (strategy) {
                case LAST_WRITE_WINS -> !candidate.timestamp().isBefore(current.timestamp());
                case SOURCE_PRIORITY -> sourcePriority.getOrDefault(candidate.source(), Integer.MAX_VALUE)
                        <= sourcePriority.getOrDefault(current.source(), Integer.MAX_VALUE);
                case ACTION_PRIORITY -> candidate.action() || !current.action();
            };
            if (takeIncoming) accepted.put(field, candidate.value());
            reasons.put(field, takeIncoming ? strategy + ": incoming accepted" : strategy + ": existing retained");
        });
        return new Resolution(accepted, reasons);
    }
}
