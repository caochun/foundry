package org.openfoundry.foundation.actions;

import java.util.Set;

public record ActionActor(String id, Set<String> roles) {
    public ActionActor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        roles = Set.copyOf(roles);
    }
}
