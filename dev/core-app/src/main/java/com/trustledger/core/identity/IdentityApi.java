package com.trustledger.core.identity;

import java.util.Optional;
import java.util.UUID;

/**
 * The identity module's public API (Spring Modulith: only types in this base package are visible to
 * other modules). Other modules never see the {@code User} entity or any plaintext personal data.
 */
public interface IdentityApi {

    /**
     * Our user ID for a Keycloak token subject ({@code sub}). Empty if the user has never called the API
     * (provisioning happens on the first authenticated request, so a current caller always has one).
     */
    Optional<UUID> findUserId(String keycloakSubject);

    /**
     * Looks a user up by mobile number through the blind index, e.g. the recipient of a P2P transfer
     * (FR-P-1). Accepts common formats ({@code 9876543210}, {@code +91 98765 43210}, {@code 09876543210}).
     * Empty for an unknown or malformed number; it never says which.
     */
    Optional<UserView> findByPhone(String phone);

    /**
     * Stores the mobile number verified by minimum KYC (FR-KYC-1) on the user, encrypted, with its blind
     * index. Joins the caller's transaction, so a later failure in the KYC step rolls this back too.
     * Recording the number the user already has is a no-op.
     *
     * @throws com.trustledger.core.shared.TrustLedgerException {@code PHONE_ALREADY_REGISTERED} if another
     *         user has this number; {@code NOT_FOUND} for an unknown user; {@code VALIDATION_FAILED} for a
     *         malformed number
     */
    void recordVerifiedPhone(UUID userId, String phone);
}
