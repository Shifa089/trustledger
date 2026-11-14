package com.trustledger.core.identity.domain;

import java.util.Optional;
import java.util.UUID;

/** Port for users; implemented by Spring Data in {@code identity.infrastructure}. */
public interface UserRepository {

    Optional<User> findById(UUID id);

    Optional<User> findByKeycloakSubject(String keycloakSubject);

    Optional<User> findByPhoneHash(String phoneHash);

    /** Flushes at once, so a unique-constraint violation surfaces here and not at commit. */
    User saveAndFlush(User user);
}
