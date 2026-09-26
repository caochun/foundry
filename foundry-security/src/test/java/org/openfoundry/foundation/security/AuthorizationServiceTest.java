package org.openfoundry.foundation.security;

import org.junit.jupiter.api.Test;
import org.openfoundry.foundation.spi.EntityKey;
import org.openfoundry.foundation.spi.RequestContext;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorizationServiceTest {
    @Test
    void delegatesRelationshipCheckAndRedactsFields() {
        SecurityPrincipal principal = new SecurityPrincipal("u-1", "tenant", Set.of("viewer"));
        AuthorizationService service = new AuthorizationService((actor, relation, resource) ->
                actor.roles().contains("viewer") && relation.equals("viewer"));
        assertTrue(service.check(RequestContext.system("tenant", "u-1"), principal,
                "viewer", new EntityKey("Person", "p-1")));
        Map<String, Object> redacted = service.redact(principal,
                Map.of("id", "p-1", "name", "Alice", "phone", "secret"),
                new FieldPolicy(Set.of("id", "name"), Map.of()));
        assertEquals("Alice", redacted.get("name"));
        assertEquals(null, redacted.get("phone"));
    }
}
