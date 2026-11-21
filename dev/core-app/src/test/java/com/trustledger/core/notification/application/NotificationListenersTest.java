package com.trustledger.core.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.UserView;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.KycLevelUpgraded;
import com.trustledger.core.notification.domain.NotificationType;
import com.trustledger.core.payments.TopupCaptured;
import com.trustledger.core.payments.TransferCompleted;
import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.FreezeSource;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletFrozen;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletView;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationListenersTest {

    private static final Instant AT = Instant.parse("2026-11-21T08:35:00Z");
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID RECEIVER = UUID.randomUUID();
    private static final UUID SENDER_WALLET = UUID.randomUUID();
    private static final UUID RECEIVER_WALLET = UUID.randomUUID();

    private final NotificationService service = mock(NotificationService.class);
    private final WalletApi wallets = mock(WalletApi.class);
    private final IdentityApi identity = mock(IdentityApi.class);
    private final NotificationListeners listeners = new NotificationListeners(service, wallets, identity,
            new NotificationProperties("no-reply@trustledger.local", ZoneId.of("Asia/Kolkata")));

    private static WalletView wallet(UUID walletId, UUID userId) {
        Money zero = Money.zero(Money.INR);
        return new WalletView(walletId, userId, WalletTier.FULL, WalletStatus.ACTIVE, zero, zero, zero);
    }

    private static EventMetadata metadata() {
        return new EventMetadata(UUID.randomUUID(), null);
    }

    private List<NotificationRequest> sent(int count) {
        ArgumentCaptor<NotificationRequest> captor = ArgumentCaptor.forClass(NotificationRequest.class);
        verify(service, times(count)).send(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void aTransferNotifiesSenderAndReceiverEachNamingTheOtherMasked() {
        UUID transferId = UUID.randomUUID();
        when(wallets.findById(SENDER_WALLET)).thenReturn(Optional.of(wallet(SENDER_WALLET, SENDER)));
        when(wallets.findById(RECEIVER_WALLET)).thenReturn(Optional.of(wallet(RECEIVER_WALLET, RECEIVER)));
        when(identity.findUser(SENDER)).thenReturn(Optional.of(
                new UserView(SENDER, UserView.Status.ACTIVE, "S*** O***", "******1111")));
        when(identity.findUser(RECEIVER)).thenReturn(Optional.of(
                new UserView(RECEIVER, UserView.Status.ACTIVE, "R*** T***", "******2222")));

        listeners.on(new TransferCompleted(transferId, SENDER_WALLET, RECEIVER_WALLET, Money.parse("250.50"),
                UUID.randomUUID(), AT, metadata()));

        List<NotificationRequest> requests = sent(2);
        assertThat(requests.get(0).userId()).isEqualTo(SENDER);
        assertThat(requests.get(0).type()).isEqualTo(NotificationType.TRANSFER_SENT);
        assertThat(requests.get(0).dedupKey()).isEqualTo("TRANSFER_SENT:" + transferId);
        assertThat(requests.get(0).message().body()).contains("INR 250.50 to R*** T***");
        assertThat(requests.get(1).userId()).isEqualTo(RECEIVER);
        assertThat(requests.get(1).type()).isEqualTo(NotificationType.TRANSFER_RECEIVED);
        assertThat(requests.get(1).dedupKey()).isEqualTo("TRANSFER_RECEIVED:" + transferId);
        assertThat(requests.get(1).message().body()).contains("INR 250.50 from S*** O***");
    }

    @Test
    void anUnknownWalletSendsNothingForIt() {
        when(wallets.findById(any())).thenReturn(Optional.empty());

        listeners.on(new TopupCaptured(UUID.randomUUID(), UUID.randomUUID(), Money.parse("1.00"), UUID.randomUUID(),
                AT, metadata()));

        verifyNoInteractions(service);
    }

    @Test
    void aTopupNotifiesTheWalletOwner() {
        UUID topupId = UUID.randomUUID();
        when(wallets.findById(SENDER_WALLET)).thenReturn(Optional.of(wallet(SENDER_WALLET, SENDER)));

        listeners.on(new TopupCaptured(topupId, SENDER_WALLET, Money.parse("500.00"), UUID.randomUUID(), AT,
                metadata()));

        NotificationRequest request = sent(1).getFirst();
        assertThat(request.userId()).isEqualTo(SENDER);
        assertThat(request.dedupKey()).isEqualTo("TOPUP_CAPTURED:" + topupId);
    }

    @Test
    void aKycUpgradeIsKeyedByUserAndLevel() {
        listeners.on(new KycLevelUpgraded(SENDER, KycLevel.FULL));

        NotificationRequest request = sent(1).getFirst();
        assertThat(request.userId()).isEqualTo(SENDER);
        assertThat(request.dedupKey()).isEqualTo("KYC_UPGRADED:" + SENDER + ":FULL");
    }

    @Test
    void aFreezeIsKeyedByItsEventIdSoARefreezeIsNotADuplicate() {
        EventMetadata metadata = metadata();

        listeners.on(new WalletFrozen(SENDER_WALLET, SENDER, FreezeSource.AML, AT, metadata));

        NotificationRequest request = sent(1).getFirst();
        assertThat(request.userId()).isEqualTo(SENDER);
        assertThat(request.dedupKey()).isEqualTo("WALLET_FROZEN:" + metadata.eventId());
        assertThat(request.message().body()).doesNotContain("AML");
    }
}
