package org.openfoundry.foundation.actions;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ActionManifest(String action, int version, boolean reversible,
                             List<Precondition> preconditions, List<ActionEffect> effects, RollbackPolicy onSideEffectFailure, List<SideEffect> sideEffects) {
    public ActionManifest(String action, int version, boolean reversible, List<Precondition> preconditions, List<ActionEffect> effects) {
        this(action, version, reversible, preconditions, effects, RollbackPolicy.LOG_AND_CONTINUE);
    }

    public ActionManifest(String action, int version, boolean reversible, List<Precondition> preconditions,
                          List<ActionEffect> effects, RollbackPolicy onSideEffectFailure) {
        this(action, version, reversible, preconditions, effects, onSideEffectFailure, List.of());
    }

    public record SideEffect(String name, String type, Map<String, Object> config, int retries, java.time.Duration retryDelay) {
        public SideEffect {
            if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_.-]{0,79}")) throw new IllegalArgumentException("Invalid side-effect name");
            if (!java.util.Set.of("event", "webhook").contains(type)) throw new IllegalArgumentException("Unsupported side-effect type");
            if (retries < 1 || retries > 1000 || retryDelay == null || retryDelay.isNegative() || retryDelay.compareTo(java.time.Duration.ofDays(1)) > 0) throw new IllegalArgumentException("Invalid retry policy");
            config = org.openfoundry.foundation.spi.schema.PropertyValues.immutableMap(config);
        }
    }

    public enum RollbackPolicy { LOG_AND_CONTINUE, RETRY_INDEFINITELY, ROLLBACK_ALL }

    public ActionManifest {
        Objects.requireNonNull(onSideEffectFailure, "onSideEffectFailure");
        sideEffects = List.copyOf(sideEffects);
        if (sideEffects.stream().map(SideEffect::name).distinct().count() != sideEffects.size()) {
            throw new IllegalArgumentException("Duplicate side-effect name");
        }
        if (action == null || action.isBlank()) throw new IllegalArgumentException("action must not be blank");
        if (version < 1) throw new IllegalArgumentException("version must be positive");
        preconditions = List.copyOf(Objects.requireNonNull(preconditions, "preconditions must not be null"));
        effects = List.copyOf(Objects.requireNonNull(effects, "effects must not be null"));
    }

    public record Precondition(String expression, String error) {
        public Precondition {
            if (expression == null || expression.isBlank()) throw new IllegalArgumentException("expression must not be blank");
            if (error == null || error.isBlank()) throw new IllegalArgumentException("error must not be blank");
        }
    }

    public sealed interface ActionEffect permits UpdateObject, CreateObject, CreateLink, DeleteLink, RecordConsent {}

    public record UpdateObject(String target, Map<String, String> set) implements ActionEffect {
        public UpdateObject {
            if (target == null || target.isBlank()) throw new IllegalArgumentException("target must not be blank");
            set = Map.copyOf(Objects.requireNonNull(set, "set must not be null"));
        }
    }

    public record CreateObject(String objectType, String target, Map<String, String> properties) implements ActionEffect {
        public CreateObject {
            if (objectType == null || objectType.isBlank()) throw new IllegalArgumentException("objectType must not be blank");
            if (target != null && target.isBlank()) throw new IllegalArgumentException("target must not be blank");
            properties = Map.copyOf(Objects.requireNonNull(properties, "properties must not be null"));
        }
    }

    public record CreateLink(String linkType, String from, String to, Map<String, String> properties) implements ActionEffect {
        public CreateLink {
            if (linkType == null || linkType.isBlank()) throw new IllegalArgumentException("linkType must not be blank");
            if (from == null || from.isBlank() || to == null || to.isBlank()) throw new IllegalArgumentException("link endpoints must not be blank");
            properties = Map.copyOf(Objects.requireNonNull(properties, "properties must not be null"));
        }
    }

    public record RecordConsent(String subject, String subjectType, String purpose, org.openfoundry.foundation.spi.ConsentRecord.Decision decision,
                                String evidence, String condition) implements ActionEffect {
        public RecordConsent {
            if (subject == null || subject.isBlank()) throw new IllegalArgumentException("Consent subject expression is required");
            if (subjectType != null && subjectType.isBlank() || purpose != null && purpose.isBlank() || condition != null && condition.isBlank()) {
                throw new IllegalArgumentException("Consent effect declarations must not be blank");
            }
            Objects.requireNonNull(decision);
        }
    }

    public enum LinkExpectation { ONE, ALL }

    public record LinkFilter(String from, String to, Boolean active) {
        public LinkFilter {
            if (from != null && from.isBlank() || to != null && to.isBlank()) {
                throw new IllegalArgumentException("Link filter references must not be blank");
            }
        }
    }

    public record DeleteLink(String linkType, String linkId, LinkFilter filter, LinkExpectation expect) implements ActionEffect {
        public DeleteLink(String linkType, String linkId) {
            this(linkType, linkId, null, LinkExpectation.ONE);
        }

        public DeleteLink(String linkType, LinkFilter filter, LinkExpectation expect) {
            this(linkType, null, filter, expect);
        }

        public DeleteLink {
            if (linkType == null || linkType.isBlank()) throw new IllegalArgumentException("Link type is required");
            if ((linkId == null) == (filter == null) || linkId != null && linkId.isBlank()) {
                throw new IllegalArgumentException("DeleteLink requires exactly one linkId or filter");
            }
            Objects.requireNonNull(expect, "expect");
            if (linkId != null && expect != LinkExpectation.ONE) {
                throw new IllegalArgumentException("A direct link ID requires expect ONE");
            }
        }
    }
}
