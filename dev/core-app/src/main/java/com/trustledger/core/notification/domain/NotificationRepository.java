package com.trustledger.core.notification.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for notifications; implemented by Spring Data in {@code notification.infrastructure}. */
public interface NotificationRepository {

    boolean existsByDedupKey(String dedupKey);

    Optional<Notification> findByDedupKey(String dedupKey);

    List<Notification> findByUserId(UUID userId);

    /** Flushes at once, so a concurrent duplicate surfaces on the unique {@code dedup_key} here, before sending. */
    Notification saveAndFlush(Notification notification);
}
