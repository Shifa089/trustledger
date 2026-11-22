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
import static org.mockito.Mockito.when;

import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.EntryType;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.payments.TransferCompleted;
import com.trustledger.core.payments.domain.Transfer;
import com.trustledger.core.payments.domain.TransferRepository;
import com.trustledger.core.payments.domain.TransferStatus;
import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.paging.PagingProperties;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletLimits;
import com.trustledger.core.wallet.WalletStatus;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletView;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * "Transfer flow" step 7: exactly one {@link TransferCompleted} for a completed transfer, with the posted journal
 * entry and the time from the injected {@link Clock}; none for a held transfer or a refusal. Also pins the order
 * lock -> post -> publish, which the Modulith outbox relies on (publish inside the money transaction).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransferEventsTest {

    private static final Instant NOW = Instant.parse("2026-11-19T06:30:00Z");
    // Placeholder: comparisons ignore metadata (its eventId is random); tests assert it is present separately.
    private static final EventMetadata ANY_METADATA = new EventMetadata(UUID.randomUUID(), null);
    private static final UUID SENDER_USER = UUID.randomUUID();
    private static final UUID SENDER = UUID.randomUUID();
    private static final UUID RECEIVER = UUID.randomUUID();
    private static final UUID TRANSFER_ID = UUID.randomUUID();
    private static final UUID ENTRY_ID = UUID.randomUUID();
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
    RecipientResolver recipients;

    @Mock
    TransferRepository transfers;

    @Mock
    ApplicationEventPublisher events;

    TransferService service;

    private static WalletView view(UUID walletId, WalletStatus status, long balanceMinor) {
        Money balance = Money.of(balanceMinor, Money.INR);
        return new WalletView(walletId, UUID.randomUUID(), WalletTier.FULL, status, balance, Money.zero(Money.INR),
                balance);
    }

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        // The real hold path on the same mocks, so the hold test still sees wallets.hold and no posting.
        HeldTransferService held = new HeldTransferService(wallets, ledger, limits, transfers, events, clock,
                new PagingProperties(20, 100), new PaymentsMetrics(new SimpleMeterRegistry()));
        service = new TransferService(wallets, ledger, limits, recipients, transfers, held, events, clock,
                new PaymentsMetrics(new SimpleMeterRegistry()));
        when(wallets.findByUser(SENDER_USER)).thenReturn(Optional.of(view(SENDER, WalletStatus.ACTIVE, 0)));
        when(recipients.resolve(eq(SENDER), any())).thenReturn(view(RECEIVER, WalletStatus.ACTIVE, 0));
        lockedAs(WalletStatus.ACTIVE, WalletStatus.ACTIVE);
        when(wallets.limitsFor(any())).thenReturn(FULL);
        // JPA assigns the UUIDv7 on persist; the mock does the same.
        when(transfers.save(any())).thenAnswer(call -> {
            Transfer transfer = call.getArgument(0);
            ReflectionTestUtils.setField(transfer, "id", TRANSFER_ID);
            return transfer;
        });
        when(ledger.post(any())).thenReturn(ENTRY_ID);
    }

    private void lockedAs(WalletStatus senderStatus, WalletStatus receiverStatus) {
        when(wallets.lockForUpdate(anyList())).thenReturn(List.of(
                view(SENDER, senderStatus, 10_000_000), view(RECEIVER, receiverStatus, 0)));
    }

    private TransferResult send(String amount) {
        return service.transfer(new TransferCommand(SENDER_USER, TransferRecipient.byWalletId(RECEIVER),
                Money.parse(amount), "Rent"));
    }

    @Test
    void completedTransferPublishesExactlyOneTransferCompleted() {
        TransferResult result = send("250.00");

        assertThat(result.status()).isEqualTo(TransferStatus.COMPLETED);
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(published.capture());
        assertThat(published.getAllValues()).singleElement().usingRecursiveComparison().ignoringFields("metadata")
                .isEqualTo(new TransferCompleted(TRANSFER_ID, SENDER, RECEIVER, Money.parse("250.00"), ENTRY_ID, NOW,
                        ANY_METADATA));
        assertThat(((TransferCompleted) published.getValue()).metadata().eventId()).isNotNull();
    }

    @Test
    void locksThenPostsThenMovesMoneyThenPublishes() {
        send("250.00");

        InOrder order = inOrder(wallets, limits, ledger, events);
        order.verify(wallets).lockForUpdate(List.of(SENDER, RECEIVER));
        order.verify(limits).checkSender(eq(SENDER), eq(FULL), eq(Money.parse("250.00")));
        order.verify(ledger).post(any());
        order.verify(wallets).debit(SENDER, Money.parse("250.00"));
        order.verify(wallets).credit(RECEIVER, Money.parse("250.00"));
        order.verify(events).publishEvent(any(TransferCompleted.class));
    }

    @Test
    void postingDebitsTheSenderAndCreditsTheReceiverReferencingTheTransfer() {
        send("250.00");

        ArgumentCaptor<PostingCommand> posting = ArgumentCaptor.forClass(PostingCommand.class);
        verify(ledger).post(posting.capture());
        PostingCommand command = posting.getValue();
        assertThat(command.entryType()).isEqualTo(EntryType.P2P_TRANSFER);
        assertThat(command.referenceType()).isEqualTo("TRANSFER");
        assertThat(command.referenceId()).isEqualTo(TRANSFER_ID);
        assertThat(command.lines()).hasSize(2);
        assertThat(command.lines()).anySatisfy(line -> {
            assertThat(line.accountCode()).isEqualTo(AccountCodes.wallet(SENDER));
            assertThat(line.direction()).isEqualTo(Direction.DEBIT);
        });
        assertThat(command.lines()).anySatisfy(line -> {
            assertThat(line.accountCode()).isEqualTo(AccountCodes.wallet(RECEIVER));
            assertThat(line.direction()).isEqualTo(Direction.CREDIT);
        });
    }

    @Test
    void heldTransferPublishesNothingAndPostsNothing() {
        TransferResult result = send("50000.00");

        assertThat(result.status()).isEqualTo(TransferStatus.HELD_FOR_REVIEW);
        verify(wallets).hold(SENDER, Money.parse("50000.00"));
        verify(ledger, never()).post(any());
        verify(wallets, never()).debit(any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void limitRefusalPublishesNothing() {
        doThrow(new TrustLedgerException(ErrorCode.LIMIT_EXCEEDED, "over"))
                .when(limits).checkSender(any(), any(), any());

        assertThatThrownBy(() -> send("250.00")).isInstanceOf(TrustLedgerException.class);
        verify(events, never()).publishEvent(any());
        verify(ledger, never()).post(any());
        verify(transfers, never()).save(any());
    }

    @Test
    void frozenReceiverPublishesNothing() {
        lockedAs(WalletStatus.ACTIVE, WalletStatus.FROZEN);

        assertThatThrownBy(() -> send("250.00")).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.WALLET_FROZEN);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void insufficientFundsPublishesNothing() {
        when(wallets.lockForUpdate(anyList())).thenReturn(List.of(
                view(SENDER, WalletStatus.ACTIVE, 100), view(RECEIVER, WalletStatus.ACTIVE, 0)));

        assertThatThrownBy(() -> send("250.00")).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
        verify(events, never()).publishEvent(any());
    }

    @Test
    void ledgerFailurePublishesNothing() {
        when(ledger.post(any())).thenThrow(new TrustLedgerException(ErrorCode.DUPLICATE_POSTING, "dup"));

        assertThatThrownBy(() -> send("250.00")).isInstanceOf(TrustLedgerException.class);
        verify(events, never()).publishEvent(any());
        verify(wallets, never()).debit(any(), any());
    }
}
