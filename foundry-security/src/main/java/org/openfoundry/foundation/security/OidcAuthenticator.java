package org.openfoundry.foundation.security;

import org.jose4j.jwk.HttpsJwks;
import org.jose4j.keys.resolvers.HttpsJwksVerificationKeyResolver;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.jwt.consumer.InvalidJwtException;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;

import java.util.List;

/** OIDC JWT validation with issuer, audience and tenant claims enforced. */
public final class OidcAuthenticator {
    private final JwtConsumer consumer;

    public OidcAuthenticator(String issuer, String audience, String jwksUri) {
        HttpsJwks jwks = new HttpsJwks(jwksUri);
        this.consumer = new JwtConsumerBuilder()
                .setRequireExpirationTime()
                .setRequireSubject()
                .setExpectedIssuer(issuer)
                .setExpectedAudience(audience)
                .setVerificationKeyResolver(new HttpsJwksVerificationKeyResolver(jwks))
                .setAllowedClockSkewInSeconds(30)
                .build();
    }

    OidcAuthenticator(JwtConsumer consumer) {
        this.consumer = consumer;
    }

    public SecurityPrincipal authenticate(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) throw new AuthenticationException("missing bearer token");
        String token = bearerToken.startsWith("Bearer ") ? bearerToken.substring(7) : bearerToken;
        try {
            JwtClaims claims = consumer.processToClaims(token);
            String tenant = claims.getStringClaimValue("tenant_id");
            if (tenant == null || tenant.isBlank()) throw new AuthenticationException("missing tenant_id claim");
            List<String> roles;
            try {
                roles = claims.getStringListClaimValue("roles");
            } catch (Exception ignored) {
                roles = List.of();
            }
            return new SecurityPrincipal(claims.getSubject(), tenant, java.util.Set.copyOf(roles));
        } catch (InvalidJwtException exception) {
            throw new AuthenticationException("invalid OIDC token", exception);
        } catch (AuthenticationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AuthenticationException("invalid OIDC claims", exception);
        }
    }

    public static final class AuthenticationException extends RuntimeException {
        public AuthenticationException(String message) { super(message); }
        public AuthenticationException(String message, Throwable cause) { super(message, cause); }
    }
}
