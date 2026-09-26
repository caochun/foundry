package org.openfoundry.foundation.security;

import org.jose4j.jwa.AlgorithmConstraints;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;
import org.jose4j.keys.AesKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OidcAuthenticatorTest {
    @Test
    void extractsSubjectTenantAndRolesAfterValidation() throws Exception {
        AesKey key = new AesKey("01234567890123456789012345678901".getBytes());
        JwtClaims claims = JwtClaims.parse("{}");
        claims.setSubject("user-1");
        claims.setIssuer("https://issuer.example");
        claims.setAudience("client");
        claims.setExpirationTimeMinutesInTheFuture(5);
        claims.setClaim("tenant_id", "tenant-a");
        claims.setStringListClaim("roles", List.of("admin", "reviewer"));
        JsonWebSignature jws = new JsonWebSignature();
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.HMAC_SHA256);
        jws.setKey(key);
        jws.setPayload(claims.toJson());
        String token = jws.getCompactSerialization();
        JwtConsumer consumer = new JwtConsumerBuilder()
                .setRequireExpirationTime().setRequireSubject().setExpectedIssuer("https://issuer.example")
                .setExpectedAudience("client").setVerificationKey(key)
                .setJwsAlgorithmConstraints(new AlgorithmConstraints(AlgorithmConstraints.ConstraintType.PERMIT, AlgorithmIdentifiers.HMAC_SHA256))
                .build();

        SecurityPrincipal principal = new OidcAuthenticator(consumer).authenticate("Bearer " + token);
        assertEquals("user-1", principal.id());
        assertEquals("tenant-a", principal.tenantId());
        assertEquals(List.of("admin", "reviewer"), principal.roles().stream().sorted().toList());
    }

    @Test
    void rejectsMissingTenant() throws Exception {
        AesKey key = new AesKey("01234567890123456789012345678901".getBytes());
        JwtClaims claims = JwtClaims.parse("{}");
        claims.setSubject("user-1"); claims.setIssuer("issuer"); claims.setAudience("client");
        claims.setExpirationTimeMinutesInTheFuture(5);
        JsonWebSignature jws = new JsonWebSignature(); jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.HMAC_SHA256); jws.setKey(key); jws.setPayload(claims.toJson());
        JwtConsumer consumer = new JwtConsumerBuilder().setRequireExpirationTime().setRequireSubject()
                .setExpectedIssuer("issuer").setExpectedAudience("client").setVerificationKey(key).build();
        assertThrows(OidcAuthenticator.AuthenticationException.class,
                () -> new OidcAuthenticator(consumer).authenticate(jws.getCompactSerialization()));
    }
}
