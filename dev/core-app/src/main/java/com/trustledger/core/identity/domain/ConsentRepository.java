package com.trustledger.core.identity.domain;

import java.util.List;
import java.util.UUID;

/** Port for the append-only consent history; implemented by Spring Data in {@code identity.infrastructure}. */
public interface ConsentRepository {

    Consent save(Consent consent);

    /** The latest row per purpose (highest {@code seq}): the user's current consent state. */
    List<Consent> findLatestPerPurpose(UUID userId);

    /** Full history, oldest first. */
    List<Consent> findByUserIdOrderBySeqAsc(UUID userId);
}
