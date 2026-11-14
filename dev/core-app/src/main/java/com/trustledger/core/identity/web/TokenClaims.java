package com.trustledger.core.identity.web;

import com.trustledger.core.identity.application.TokenIdentity;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the provisioning claims from a Keycloak access token (standard OIDC claim names). */
final class TokenClaims {

    private TokenClaims() {
    }

    static TokenIdentity identity(Jwt jwt) {
        return new TokenIdentity(jwt.getSubject(), jwt.getClaimAsString("email"), fullName(jwt));
    }

    /** {@code name}, or {@code given_name family_name} when a token carries only those. */
    private static String fullName(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isBlank()) {
            return name;
        }
        String given = jwt.getClaimAsString("given_name");
        String family = jwt.getClaimAsString("family_name");
        String joined = ((given == null ? "" : given) + " " + (family == null ? "" : family)).strip();
        return joined.isEmpty() ? null : joined;
    }
}
