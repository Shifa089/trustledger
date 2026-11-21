package com.trustledger.core.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.NotificationContact;
import com.trustledger.core.notification.domain.EmailMessage;
import com.trustledger.core.notification.domain.EmailSender;
import com.trustledger.core.notification.domain.Notification;
import com.trustledger.core.notification.domain.NotificationRepository;
import com.trustledger.core.notification.domain.NotificationStatus;
import com.trustledger.core.notification.domain.NotificationType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

class NotificationServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final String ADDRESS = "notify.user@example.test";
    private static final EmailMessage MESSAGE = new EmailMessage("Subject", "Body");
    private static final NotificationRequest REQUEST =
            new NotificationRequest(USER, NotificationType.TOPUP_CAPTURED, "TOPUP_CAPTURED:1", MESSAGE);

    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final IdentityApi identity = mock(IdentityApi.class);
    private final EmailSender emails = mock(EmailSender.class);
    private final List<NotificationStatus> savedStatuses = new ArrayList<>();
    private Notification saved;

    private final NotificationService service = new NotificationService(repository, identity, emails,
            Clock.fixed(Instant.parse("2026-11-21T08:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void recordSaves() {
        when(repository.saveAndFlush(any())).thenAnswer(call -> {
            saved = call.getArgument(0);
            savedStatuses.add(saved.getStatus());
            return saved;
        });
    }

    @Test
    void sendsToTheContactAndRecordsSentAfterClaimingTheKey() {
        when(identity.findNotificationContact(USER)).thenReturn(Optional.of(new NotificationContact(USER, ADDRESS)));

        assertThat(service.send(REQUEST)).isEqualTo(NotificationOutcome.SENT);

        verify(emails).send(ADDRESS, MESSAGE);
        assertThat(savedStatuses).as("PENDING flushed before sending, then SENT")
                .containsExactly(NotificationStatus.PENDING, NotificationStatus.SENT);
        assertThat(saved.getDedupKey()).isEqualTo("TOPUP_CAPTURED:1");
        assertThat(saved.getType()).isEqualTo(NotificationType.TOPUP_CAPTURED);
    }

    @Test
    void aRedeliveredEventSendsNothing() {
        when(repository.existsByDedupKey("TOPUP_CAPTURED:1")).thenReturn(true);

        assertThat(service.send(REQUEST)).isEqualTo(NotificationOutcome.DUPLICATE);

        verifyNoInteractions(emails, identity);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void noContactIsRecordedAsSkipped() {
        when(identity.findNotificationContact(USER)).thenReturn(Optional.empty());

        assertThat(service.send(REQUEST)).isEqualTo(NotificationOutcome.SKIPPED);

        verifyNoInteractions(emails);
        assertThat(saved.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(saved.getFailureReason()).isEqualTo(Notification.NO_CONTACT);
    }

    @Test
    void aMailFailureIsRecordedWithTheClassNameOnlyAndNotThrown() {
        when(identity.findNotificationContact(USER)).thenReturn(Optional.of(new NotificationContact(USER, ADDRESS)));
        doThrow(new MailSendException("550 rejected <" + ADDRESS + ">")).when(emails).send(anyString(), any());

        assertThat(service.send(REQUEST)).isEqualTo(NotificationOutcome.FAILED);

        assertThat(saved.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(saved.getFailureReason()).isEqualTo("MailSendException").doesNotContain(ADDRESS);
    }

    @Test
    void aBugIsNotSwallowed() {
        when(identity.findNotificationContact(USER)).thenReturn(Optional.of(new NotificationContact(USER, ADDRESS)));
        doThrow(new IllegalStateException("bug")).when(emails).send(anyString(), any());

        assertThatThrownBy(() -> service.send(REQUEST)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theContactNeverPrintsTheAddress() {
        assertThat(new NotificationContact(USER, ADDRESS).toString()).doesNotContain(ADDRESS).contains("n***@");
    }
}
