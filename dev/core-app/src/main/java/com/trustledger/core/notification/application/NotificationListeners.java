package com.trustledger.core.notification.application;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.UserView;
import com.trustledger.core.kyc.KycLevelUpgraded;
import com.trustledger.core.notification.domain.NotificationTemplates;
import com.trustledger.core.notification.domain.NotificationType;
import com.trustledger.core.payments.TopupCaptured;
import com.trustledger.core.payments.TransferCompleted;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletFrozen;
import com.trustledger.core.wallet.WalletView;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * FR-N-1: turns domain events into e-mails. {@link ApplicationModuleListener} = after the publishing transaction has
 * committed, in a new transaction, with the publication recorded in the outbox: a notification can neither undo
 * nor be lost with a committed money movement, and an event left incomplete by a crash is delivered again on
 * restart (the dedup key then prevents a second mail).
 * <p>
 * Each handler only works out <em>who</em> gets <em>what</em>; {@link NotificationService} sends and records.
 */
@Component
class NotificationListeners {

    private static final Logger log = LoggerFactory.getLogger(NotificationListeners.class);

    private final NotificationService notifications;
    private final WalletApi wallets;
    private final IdentityApi identity;
    private final ZoneId zone;

    NotificationListeners(NotificationService notifications, WalletApi wallets, IdentityApi identity,
            NotificationProperties properties) {
        this.notifications = notifications;
        this.wallets = wallets;
        this.identity = identity;
        this.zone = properties.zone();
    }

    /** Two mails: the sender's ("you sent") and the receiver's ("you received"), each naming the other, masked. */
    @ApplicationModuleListener
    void on(TransferCompleted event) {
        UUID senderId = ownerOf(event.senderWalletId());
        UUID receiverId = ownerOf(event.receiverWalletId());
        if (senderId != null) {
            notifications.send(new NotificationRequest(senderId, NotificationType.TRANSFER_SENT,
                    NotificationType.TRANSFER_SENT.dedupKey(event.transferId()),
                    NotificationTemplates.transferSent(event.amount(), maskedName(receiverId), event.transferId(),
                            event.completedAt(), zone)));
        }
        if (receiverId != null) {
            notifications.send(new NotificationRequest(receiverId, NotificationType.TRANSFER_RECEIVED,
                    NotificationType.TRANSFER_RECEIVED.dedupKey(event.transferId()),
                    NotificationTemplates.transferReceived(event.amount(), maskedName(senderId), event.transferId(),
                            event.completedAt(), zone)));
        }
    }

    @ApplicationModuleListener
    void on(TopupCaptured event) {
        UUID userId = ownerOf(event.walletId());
        if (userId != null) {
            notifications.send(new NotificationRequest(userId, NotificationType.TOPUP_CAPTURED,
                    NotificationType.TOPUP_CAPTURED.dedupKey(event.topupId()),
                    NotificationTemplates.topupCaptured(event.amount(), event.topupId(), event.capturedAt(), zone)));
        }
    }

    /** The event has no ID of its own, but a user reaches each level once, so user + level identifies it. */
    @ApplicationModuleListener
    void on(KycLevelUpgraded event) {
        notifications.send(new NotificationRequest(event.userId(), NotificationType.KYC_UPGRADED,
                NotificationType.KYC_UPGRADED.dedupKey(event.userId(), event.level()),
                NotificationTemplates.kycUpgraded(event.level())));
    }

    /** A wallet can be frozen again after an unfreeze, so the key is the event's own ID. */
    @ApplicationModuleListener
    void on(WalletFrozen event) {
        notifications.send(new NotificationRequest(event.userId(), NotificationType.WALLET_FROZEN,
                NotificationType.WALLET_FROZEN.dedupKey(event.metadata().eventId()),
                NotificationTemplates.walletFrozen(event.walletId(), event.frozenAt(), zone)));
    }

    /** The wallet's user, or {@code null} (logged) if the wallet is unknown, which a committed event rules out. */
    private UUID ownerOf(UUID walletId) {
        UUID owner = wallets.findById(walletId).map(WalletView::userId).orElse(null);
        if (owner == null) {
            log.warn("No wallet {} for a notification; nothing sent", walletId);
        }
        return owner;
    }

    private String maskedName(UUID userId) {
        return userId == null ? null : identity.findUser(userId).map(UserView::maskedFullName).orElse(null);
    }
}
