package org.openfoundry.foundation.actions;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ActionManifest(String action, int version, boolean reversible,
                             List<Precondition> preconditions, List<ActionEffect> effects) {
    public ActionManifest {
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

    public sealed interface ActionEffect permits UpdateObject, CreateObject, CreateLink, DeleteLink {}

    public record UpdateObject(String target, Map<String, String> set) implements ActionEffect {
        public UpdateObject {
            if (target == null || target.isBlank()) throw new IllegalArgumentException("target must not be blank");
            set = Map.copyOf(Objects.requireNonNull(set, "set must not be null"));
        }
    }

    public record CreateObject(String objectType, String target, Map<String, String> properties) implements ActionEffect {
        public CreateObject {
            if (objectType == null || objectType.isBlank()) throw new IllegalArgumentException("objectType must not be blank");
            if (target == null || target.isBlank()) throw new IllegalArgumentException("target must not be blank");
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

    public record DeleteLink(String linkType, String linkId) implements ActionEffect {
        public DeleteLink {
            if (linkType == null || linkType.isBlank() || linkId == null || linkId.isBlank()) throw new IllegalArgumentException("link type and id must not be blank");
        }
    }
}
