package org.openfoundry.foundation.security;

import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.RequestContext;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Relationship authorization and field-level redaction at the application boundary. */
public final class AuthorizationService {
    private final RelationshipAuthorizer authorizer;

    public AuthorizationService(RelationshipAuthorizer authorizer) {
        this.authorizer = authorizer;
    }

    public boolean check(SecurityPrincipal principal, String relation, EntityKey resource) {
        return authorizer.check(principal, relation, resource);
    }

    public boolean check(RequestContext context, SecurityPrincipal principal,
                         String relation, EntityKey resource) {
        if (!context.tenantId().equals(principal.tenantId())) return false;
        return check(principal, relation, resource);
    }

    public Map<String, Object> redact(SecurityPrincipal principal, Map<String, Object> object,
                                      FieldPolicy policy) {
        java.util.Set<String> visible = new HashSet<>(policy.alwaysVisible());
        for (String role : principal.roles()) visible.addAll(policy.fieldsByRole().getOrDefault(role, java.util.Set.of()));
        Map<String, Object> redacted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            redacted.put(entry.getKey(), visible.contains(entry.getKey()) ? entry.getValue() : null);
        }
        return Collections.unmodifiableMap(redacted);
    }
}
