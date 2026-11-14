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
}
