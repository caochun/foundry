package org.openfoundry.foundation.security;

import java.util.Set;

public record SecurityPrincipal(String id, String tenantId, Set<String> roles) {
    public SecurityPrincipal {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be blank");
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        roles = Set.copyOf(roles);
    }
}
