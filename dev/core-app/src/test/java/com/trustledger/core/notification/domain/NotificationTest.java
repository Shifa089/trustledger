package com.trustledger.core.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationTest {

    private static final Instant T0 = Instant.parse("2026-11-21T08:00:00Z");
    private static final Instant T1 = T0.plusSeconds(1);
    private static final UUID USER = UUID.randomUUID();

    private static Notification pending() {
        return Notification.pending(USER, NotificationType.TOPUP_CAPTURED, "TOPUP_CAPTURED:x", T0);
    }

    @Test
    void startsPending() {
        Notification n = pending();

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(n.getUserId()).isEqualTo(USER);
        assertThat(n.getCreatedAt()).isEqualTo(T0);
        assertThat(n.getSentAt()).isNull();
        assertThat(n.getFailureReason()).isNull();
    }

    @Test
    void markSentRecordsTheTime() {
        Notification n = pending();
        n.markSent(T1);

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getSentAt()).isEqualTo(T1);
        assertThat(n.getUpdatedAt()).isEqualTo(T1);
    }

    @Test
    void markFailedAndSkippedRecordTheReasonWithoutASendTime() {
        Notification failed = pending();
        failed.markFailed("MailSendException", T1);
        Notification skipped = pending();
        skipped.markSkipped(Notification.NO_CONTACT, T1);

        assertThat(failed.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("MailSendException");
        assertThat(failed.getSentAt()).isNull();
        assertThat(skipped.getStatus()).isEqualTo(NotificationStatus.SKIPPED);
        assertThat(skipped.getFailureReason()).isEqualTo("NO_CONTACT");
    }

    @Test
    void anOverlongReasonIsCutToTheColumnSize() {
        Notification n = pending();
        n.markFailed("X".repeat(500), T1);

        assertThat(n.getFailureReason()).hasSize(Notification.MAX_REASON_LENGTH);
    }

    @Test
    void finishesOnlyOnce() {
        Notification n = pending();
        n.markSent(T1);

        assertThatThrownBy(() -> n.markFailed("MailSendException", T1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> n.markSent(T1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> n.markSkipped("NO_CONTACT", T1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> Notification.pending(USER, NotificationType.KYC_UPGRADED, " ", T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Notification.pending(USER, NotificationType.KYC_UPGRADED, "K".repeat(129), T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Notification.pending(null, NotificationType.KYC_UPGRADED, "k", T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pending().markFailed(" ", T1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dedupKeysJoinTypeAndParts() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");

        assertThat(NotificationType.TRANSFER_SENT.dedupKey(id))
                .isEqualTo("TRANSFER_SENT:00000000-0000-0000-0000-000000000001");
        assertThat(NotificationType.KYC_UPGRADED.dedupKey(id, "FULL"))
                .isEqualTo("KYC_UPGRADED:00000000-0000-0000-0000-000000000001:FULL");
    }
}
