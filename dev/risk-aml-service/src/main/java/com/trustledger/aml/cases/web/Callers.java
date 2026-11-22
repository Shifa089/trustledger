package com.trustledger.aml.cases.web;

import com.trustledger.aml.shared.ErrorCode;
import com.trustledger.aml.shared.TrustLedgerException;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/** The calling officer's ID: the token's {@code sub}, Keycloak's user ID (a UUID). */
final class Callers {

    private Callers() {
    }

    static UUID officerId(Jwt jwt) {
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            // Keycloak always issues a UUID subject; anything else is not one of our officers.
            throw new TrustLedgerException(ErrorCode.ACCESS_DENIED, "You are not allowed to perform this action.");
        }
    }
}
