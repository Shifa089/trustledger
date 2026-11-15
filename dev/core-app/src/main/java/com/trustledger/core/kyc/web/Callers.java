package com.trustledger.core.kyc.web;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Our user ID for the caller's token. Customers and officers alike: officers are provisioned on their first
 * request too, so {@code verified_by}/{@code approved_by} hold the same kind of ID as {@code user_id}, which
 * is what lets the aggregate stop an officer from reviewing their own case.
 */
@Component
class Callers {

    private final IdentityApi identity;

    Callers(IdentityApi identity) {
        this.identity = identity;
    }

    /** The JIT filter has provisioned every authenticated caller before a controller runs. */
    UUID userId(Jwt jwt) {
        return identity.findUserId(jwt.getSubject())
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "User profile not found."));
    }
}
