package com.trustledger.core.reporting.web;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/** Our user ID for the caller's token. Same as the other modules' helpers; each module keeps its web helpers internal. */
@Component("reportingCallers")
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
