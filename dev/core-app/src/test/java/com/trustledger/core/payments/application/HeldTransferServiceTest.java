package com.trustledger.core.payments.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.payments.TransferCompleted;
import com.trustledger.core.payments.domain.Transfer;
import com.trustledger.core.payments.domain.TransferRepository;
import com.trustledger.core.payments.domain.TransferStatus;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletLimits;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletView;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The order of a compliance decision (FR-P-3): lock the transfer, lock the wallets, check, release the hold, post,
 * move money, publish. And what must <em>not</em> happen: no posting or event on a rejection, nothing touched
 * when the transfer isn't held.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HeldTransferServiceTest {

    private static final Instant CREATED = Instant.parse("2026-11-19T05:00:00Z");
    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID RECEIVER = UUID.randomUUID();
    private static final UUID TRANSFER_ID = UUID.randomUUID();
    private static final UUID ENTRY_ID = UUID.randomUUID();
    private static final UUID OFFICER = UUID.randomUUID();
    private static final Money AMOUNT = Money.parse("50000.00");
    private static final WalletLimits FULL = new WalletLimits(WalletTier.FULL, Money.parse("200000.00"),
            Money.parse("200000.00"), true, Money.parse("100000.00"), Money.parse("50000.00"),
            Money.parse("500000.00"));

    @Mock
    WalletApi wallets;

    @Mock
    LedgerApi ledger;

    @Mock
    LimitChecker limits;

    @Mock
    TransferRepository transfers;

    @Mock
    ApplicationEventPublisher events;

    HeldTransferService service;
    Transfer transfer;

    private static WalletView view(UUID walletId, WalletStatus status) {
        Money balance = Money.parse("60000.00");
        return new WalletView(walletId, UUID.randomUUID(), WalletTier.FULL, status, balance, AMOUNT,
                balance.minus(AMOUNT));
    }

    @BeforeEach
    void setUp() {
        service = new HeldTransferService(wallets, ledger, limits, transfers, events, Clock.fixed(NOW,
                ZoneOffset.UTC));
        transfer = Transfer.initiate(SENDER, RECEIVER, AMOUNT, "Rent", CREATED);
        ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
        transfer.holdForReview(CREATED);
        when(transfers.findByIdForUpdate(TRANSFER_ID)).thenReturn(Optional.of(transfer));
        lockedAs(WalletStatus.ACTIVE, WalletStatus.ACTIVE);
        when(wallets.limitsFor(any())).thenReturn(FULL);
        when(ledger.post(any())).thenReturn(ENTRY_ID);
    }

    private void lockedAs(WalletStatus senderStatus, WalletStatus receiverStatus) {
        when(wallets.lockForUpdate(anyList())).thenReturn(List.of(view(SENDER, senderStatus),
                view(RECEIVER, receiverStatus)));
    }

    @Test
    void approveLocksChecksPostsReleasesMovesThenPublishes() {
        HeldTransferView result = service.approve(TRANSFER_ID, OFFICER);

        InOrder order = inOrder(transfers, wallets, limits, ledger, events);
        order.verify(transfers).findByIdForUpdate(TRANSFER_ID);
        order.verify(wallets).lockForUpdate(List.of(SENDER, RECEIVER));
        order.verify(limits).checkReceiverCap(any(), eq(FULL), eq(AMOUNT));
        order.verify(ledger).post(TransferService.postingFor(transfer));
        order.verify(wallets).releaseHold(SENDER, AMOUNT);
        order.verify(wallets).debit(SENDER, AMOUNT);
        order.verify(wallets).credit(RECEIVER, AMOUNT);
        order.verify(events).publishEvent(new TransferCompleted(TRANSFER_ID, SENDER, RECEIVER, AMOUNT, ENTRY_ID,
                NOW));
        assertThat(result.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.reviewedBy()).isEqualTo(OFFICER);
        assertThat(result.reviewedAt()).isEqualTo(NOW);
        assertThat(transfer.getJournalEntryId()).isEqualTo(ENTRY_ID);
    }

    @Test
    void rejectReleasesTheHoldAndNeitherPostsNorPublishes() {
        HeldTransferView result = service.reject(TRANSFER_ID, OFFICER);

        InOrder order = inOrder(transfers, wallets);
        order.verify(transfers).findByIdForUpdate(TRANSFER_ID);
        order.verify(wallets).lockForUpdate(List.of(SENDER));
        order.verify(wallets).releaseHold(SENDER, AMOUNT);
        verify(ledger, never()).post(any());
        verify(wallets, never()).debit(any(), any());
        verify(wallets, never()).credit(any(), any());
        verify(events, never()).publishEvent(any());
        assertThat(result.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(result.reviewedBy()).isEqualTo(OFFICER);
    }

    @Test
    void aTransferThatIsNotHeldTouchesNoWallet() {
        transfer.reject(UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> service.approve(TRANSFER_ID, OFFICER))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        assertThatThrownBy(() -> service.reject(TRANSFER_ID, OFFICER))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
        verifyNoInteractions(wallets, ledger, events);
    }

    @Test
    void unknownTransferIsNotFound() {
        UUID unknown = UUID.randomUUID();
        when(transfers.findByIdForUpdate(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approve(unknown, OFFICER))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        verifyNoInteractions(wallets, ledger, events);
    }

    @Test
    void approvalOfAFrozenWalletIsRefusedBeforeAnyMoneyMoves() {
        lockedAs(WalletStatus.FROZEN, WalletStatus.ACTIVE);

        assertThatThrownBy(() -> service.approve(TRANSFER_ID, OFFICER))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.WALLET_FROZEN));
        verify(wallets, never()).releaseHold(any(), any());
        verify(ledger, never()).post(any());
        verify(events, never()).publishEvent(any());
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.HELD_FOR_REVIEW);
    }

    @Test
    void receiverCapRefusalAtApprovalMovesNothing() {
        doThrow(new TrustLedgerException(ErrorCode.LIMIT_EXCEEDED, "cap"))
                .when(limits).checkReceiverCap(any(), any(), any());

        assertThatThrownBy(() -> service.approve(TRANSFER_ID, OFFICER))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED));
        verify(wallets, never()).releaseHold(any(), any());
        verify(ledger, never()).post(any());
        assertThat(transfer.getStatus()).isEqualTo(TransferStatus.HELD_FOR_REVIEW);
    }

    @Test
    void queueRejectsAPageSizeOutsideItsBounds() {
        assertThatThrownBy(() -> service.queue(TransferStatus.HELD_FOR_REVIEW, 0, 0))
                .isInstanceOf(TrustLedgerException.class);
        assertThatThrownBy(() -> service.queue(TransferStatus.HELD_FOR_REVIEW, 0,
                HeldTransferService.MAX_PAGE_SIZE + 1)).isInstanceOf(TrustLedgerException.class);
        assertThatThrownBy(() -> service.queue(TransferStatus.HELD_FOR_REVIEW, -1, 20))
                .isInstanceOf(TrustLedgerException.class);
    }
}
