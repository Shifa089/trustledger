package com.trustledger.core.notification.infrastructure;

import com.trustledger.core.notification.domain.Notification;
import com.trustledger.core.notification.domain.NotificationRepository;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data implementation of {@link NotificationRepository}. */
public interface JpaNotificationRepository extends JpaRepository<Notification, UUID>, NotificationRepository {
}
