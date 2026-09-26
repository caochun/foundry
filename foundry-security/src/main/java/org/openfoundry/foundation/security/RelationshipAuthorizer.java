package org.openfoundry.foundation.security;

import org.openfoundry.foundation.spi.EntityKey;

@FunctionalInterface
public interface RelationshipAuthorizer {
    boolean check(SecurityPrincipal principal, String relation, EntityKey resource);
}
