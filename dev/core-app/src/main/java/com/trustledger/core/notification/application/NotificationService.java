package com.trustledger.core.notification.application;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.NotificationContact;
import com.trustledger.core.notification.domain.EmailSender;
import com.trustledger.core.notification.domain.Notification;
import com.trustledger.core.notification.domain.NotificationRepository;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends one notification and records what happened (FR-N-1).
 * <ol>
 * <li>A row for the {@code dedupKey} exists: the event was delivered again (restart republish); send nothing.</li>
 * <li>No contact (no e-mail, erased user): record SKIPPED.</li>
 * <li>Insert PENDING and flush, which claims the key, then send; record SENT, or FAILED when the mail server
 * fails.</li>
 * </ol>
 * A mail failure is <b>recorded, not thrown</b>: the business transaction has already committed (the listener runs
 * after commit), and throwing would only make the outbox resend the event to a mail server that is still down. Other
 * exceptions are bugs and propagate, so the publication stays incomplete and is retried on restart.
 * <p>
 * Never logs the address or an exception message (an SMTP error can quote the address): type, ID and exception
 * class only.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final IdentityApi identity;
    private final EmailSender emails;
    private final Clock clock;

    NotificationService(NotificationRepository notifications, IdentityApi identity, EmailSender emails,
            Clock clock) {
        this.notifications = notifications;
        this.identity = identity;
        this.emails = emails;
        this.clock = clock;
    }

    @Transactional
    public NotificationOutcome send(NotificationRequest request) {
        if (notifications.existsByDedupKey(request.dedupKey())) {
            log.debug("Notification {} already handled; not sending again", request.dedupKey());
            return NotificationOutcome.DUPLICATE;
        }
        Notification notification =
                Notification.pending(request.userId(), request.type(), request.dedupKey(), clock.instant());
        Optional<NotificationContact> contact = identity.findNotificationContact(request.userId());
        if (contact.isEmpty()) {
            notification.markSkipped(Notification.NO_CONTACT, clock.instant());
            notifications.saveAndFlush(notification);
            log.info("Notification {} for user {} skipped: no contact", request.type(), request.userId());
            return NotificationOutcome.SKIPPED;
        }
        notifications.saveAndFlush(notification);
        try {
            emails.send(contact.get().email(), request.message());
        } catch (MailException failure) {
            notification.markFailed(failure.getClass().getSimpleName(), clock.instant());
            notifications.saveAndFlush(notification);
            log.warn("Notification {} {} for user {} failed: {}", request.type(), notification.getId(),
                    request.userId(), failure.getClass().getSimpleName());
            return NotificationOutcome.FAILED;
        }
        notification.markSent(clock.instant());
        notifications.saveAndFlush(notification);
        return NotificationOutcome.SENT;
    }
}
