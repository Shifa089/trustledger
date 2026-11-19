package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.application.HeldTransferService;
import com.trustledger.core.payments.application.HeldTransferView;
import com.trustledger.core.payments.application.TransferCommand;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.payments.application.TransferResult;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.payments.domain.TransferStatus;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * M6 "Done when" 5 / must-pass 7 (FR-P-3): a ₹50,000 transfer is held; approval posts it and releases the hold;
 * rejection releases the hold and posts nothing. Plus the edges: a decision is final, a frozen wallet blocks
 * approval but not rejection, the receiver cap is checked again at approval, and two officers deciding at the
 * same time produce exactly one decision.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@RecordApplicationEvents
class HeldTransferServiceIT {

    private static final long HIGH_VALUE = 5_000_000; // ₹50,000, the configured threshold

    @Autowired
    TransferService transfers;

    @Autowired
    HeldTransferService review;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    ApplicationEvents events;

    private final UUID officer = UUID.randomUUID();

    private TransferResult send(Customer from, Customer to, long minor) {
        return transfers.transfer(new TransferCommand(from.userId(), TransferRecipient.byWalletId(to.walletId()),
                Money.of(minor, Money.INR), "Car"));
    }

    private UUID hold(Customer from, Customer to) {
        TransferResult result = send(from, to, HIGH_VALUE);
        assertThat(result.status()).isEqualTo(TransferStatus.HELD_FOR_REVIEW);
        return result.transferId();
    }

    private long completedEventsFor(UUID transferId) {
        return events.stream(TransferCompleted.class).filter(e -> e.transferId().equals(transferId)).count();
    }

    private static void assertRefused(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(TrustLedgerException.class,
                e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    private static Money inr(long minor) {
        return Money.of(minor, Money.INR);
    }

    @Test
    void approvalPostsTheTransferReleasesTheHoldAndPublishesOnce() {
        Customer sender = fixtures.customer(WalletTier.FULL, 6_000_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        UUID id = hold(sender, receiver);
        assertThat(completedEventsFor(id)).as("no event while held").isZero();

        HeldTransferView approved = review.approve(id, officer);

        assertThat(approved.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(approved.reviewedBy()).isEqualTo(officer);
        assertThat(approved.reviewedAt()).isNotNull();
        WalletView from = fixtures.wallet(sender.walletId());
        assertThat(from.balance()).isEqualTo(inr(1_000_000));
        assertThat(from.held().isZero()).isTrue();
        assertThat(fixtures.wallet(receiver.walletId()).balance()).isEqualTo(inr(HIGH_VALUE));
        assertThat(fixtures.ledgerBalance(sender.walletId())).isEqualTo(1_000_000);
        assertThat(fixtures.ledgerBalance(receiver.walletId())).isEqualTo(HIGH_VALUE);
        assertThat(fixtures.journalEntriesFor(id)).isEqualTo(1);
        assertThat(fixtures.transferStatus(id)).isEqualTo("COMPLETED");
        assertThat(events.stream(TransferCompleted.class).filter(e -> e.transferId().equals(id)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.amount()).isEqualTo(inr(HIGH_VALUE));
                    assertThat(e.senderWalletId()).isEqualTo(sender.walletId());
                    assertThat(e.receiverWalletId()).isEqualTo(receiver.walletId());
                    assertThat(e.journalEntryId()).isNotNull();
                });
    }

    @Test
    void rejectionReleasesTheHoldAndMovesNothing() {
        Customer sender = fixtures.customer(WalletTier.FULL, 6_000_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        UUID id = hold(sender, receiver);

        HeldTransferView rejected = review.reject(id, officer);

        assertThat(rejected.status()).isEqualTo(TransferStatus.REJECTED);
        assertThat(rejected.reviewedBy()).isEqualTo(officer);
        WalletView from = fixtures.wallet(sender.walletId());
        assertThat(from.balance()).isEqualTo(inr(6_000_000));
        assertThat(from.held().isZero()).isTrue();
        assertThat(from.available()).isEqualTo(inr(6_000_000));
        assertThat(fixtures.wallet(receiver.walletId()).balance().isZero()).isTrue();
        assertThat(fixtures.ledgerBalance(sender.walletId())).isEqualTo(6_000_000);
        assertThat(fixtures.ledgerBalance(receiver.walletId())).isZero();
        assertThat(fixtures.journalEntriesFor(id)).isZero();
        assertThat(completedEventsFor(id)).isZero();
    }

    @Test
    void aRejectedTransferNoLongerUsesUpTheDailyLimit() {
        Customer sender = fixtures.customer(WalletTier.FULL, 16_000_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        UUID first = hold(sender, receiver);
        hold(sender, receiver); // ₹1,00,000 held today: the FULL daily limit is used up
        assertRefused(() -> send(sender, receiver, HIGH_VALUE), ErrorCode.LIMIT_EXCEEDED);

        review.reject(first, officer);

        assertThat(send(sender, receiver, HIGH_VALUE).status()).isEqualTo(TransferStatus.HELD_FOR_REVIEW);
    }

    @Test
    void aDecisionIsFinal() {
        Customer sender = fixtures.customer(WalletTier.FULL, 12_000_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        UUID approved = hold(sender, receiver);
        UUID rejected = hold(sender, receiver);
        review.approve(approved, officer);
        review.reject(rejected, officer);

        assertRefused(() -> review.approve(approved, officer), ErrorCode.INVALID_STATE_TRANSITION);
        assertRefused(() -> review.reject(approved, officer), ErrorCode.INVALID_STATE_TRANSITION);
        assertRefused(() -> review.approve(rejected, officer), ErrorCode.INVALID_STATE_TRANSITION);
        assertRefused(() -> review.reject(rejected, officer), ErrorCode.INVALID_STATE_TRANSITION);

        assertThat(fixtures.journalEntriesFor(approved)).isEqualTo(1);
        assertThat(fixtures.journalEntriesFor(rejected)).isZero();
        WalletView from = fixtures.wallet(sender.walletId());
        assertThat(from.balance()).isEqualTo(inr(7_000_000));
        assertThat(from.held().isZero()).as("a second release would have gone negative or failed").isTrue();
        assertThat(fixtures.wallet(receiver.walletId()).balance()).isEqualTo(inr(HIGH_VALUE));
    }

    @Test
    void anOrdinaryCompletedTransferIsNotUpForReview() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        UUID id = send(sender, receiver, 10_000).transferId();

        assertRefused(() -> review.approve(id, officer), ErrorCode.INVALID_STATE_TRANSITION);
        assertRefused(() -> review.reject(id, officer), ErrorCode.INVALID_STATE_TRANSITION);
        assertThat(fixtures.journalEntriesFor(id)).isEqualTo(1);
    }

    @Test
    void anUnknownTransferIsNotFound() {
        UUID unknown = UUID.randomUUID();
        assertRefused(() -> review.approve(unknown, officer), ErrorCode.NOT_FOUND);
        assertRefused(() -> review.reject(unknown, officer), ErrorCode.NOT_FOUND);
    }

    @Test
    void aFrozenSenderBlocksApprovalButNotRejection() {
        Customer sender = fixtures.customer(WalletTier.FULL, 6_000_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        UUID id = hold(sender, receiver);
        fixtures.freeze(sender.walletId());

        assertRefused(() -> review.approve(id, officer), ErrorCode.WALLET_FROZEN);
        assertThat(fixtures.transferStatus(id)).isEqualTo("HELD_FOR_REVIEW");
        assertThat(fixtures.wallet(sender.walletId()).held()).isEqualTo(inr(HIGH_VALUE));
        assertThat(fixtures.journalEntriesFor(id)).isZero();

        review.reject(id, officer);

        WalletView from = fixtures.wallet(sender.walletId());
        assertThat(from.held().isZero()).isTrue();
        assertThat(from.balance()).isEqualTo(inr(6_000_000));
    }

    @Test
    void aFrozenReceiverBlocksApproval() {
        Customer sender = fixtures.customer(WalletTier.FULL, 6_000_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        UUID id = hold(sender, receiver);
        fixtures.freeze(receiver.walletId());

        assertRefused(() -> review.approve(id, officer), ErrorCode.WALLET_FROZEN);
        assertThat(fixtures.transferStatus(id)).isEqualTo("HELD_FOR_REVIEW");
        assertThat(fixtures.wallet(sender.walletId()).held()).isEqualTo(inr(HIGH_VALUE));
    }

    @Test
    void theReceiverCapIsCheckedAgainAtApproval() {
        Customer sender = fixtures.customer(WalletTier.FULL, 6_000_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 14_000_000); // ₹1,40,000 + ₹50,000 fits ₹2,00,000
        UUID id = hold(sender, receiver);
        fixtures.fund(receiver.walletId(), 2_000_000); // now ₹1,60,000: the transfer no longer fits

        assertRefused(() -> review.approve(id, officer), ErrorCode.LIMIT_EXCEEDED);
        assertThat(fixtures.transferStatus(id)).isEqualTo("HELD_FOR_REVIEW");
        assertThat(fixtures.wallet(sender.walletId()).held()).isEqualTo(inr(HIGH_VALUE));
        assertThat(fixtures.wallet(receiver.walletId()).balance()).isEqualTo(inr(16_000_000));
    }

    private enum Decision { APPROVED, REJECTED, ALREADY_DECIDED }

    @Test
    void concurrentDecisionsOnOneTransferProduceExactlyOne() throws Exception {
        Customer sender = fixtures.customer(WalletTier.FULL, 6_000_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        UUID id = hold(sender, receiver);
        List<Callable<Decision>> tasks = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            boolean approve = i % 2 == 0;
            UUID officerId = UUID.randomUUID();
            tasks.add(() -> {
                try {
                    if (approve) {
                        review.approve(id, officerId);
                        return Decision.APPROVED;
                    }
                    review.reject(id, officerId);
                    return Decision.REJECTED;
                } catch (TrustLedgerException e) {
                    if (e.errorCode() == ErrorCode.INVALID_STATE_TRANSITION) {
                        return Decision.ALREADY_DECIDED;
                    }
                    throw e;
                }
            });
        }

        List<Decision> decisions = ConcurrentRun.together(tasks);

        assertThat(decisions).filteredOn(d -> d != Decision.ALREADY_DECIDED).hasSize(1);
        assertThat(decisions).filteredOn(d -> d == Decision.ALREADY_DECIDED).hasSize(19);
        WalletView from = fixtures.wallet(sender.walletId());
        assertThat(from.held().isZero()).as("released exactly once").isTrue();
        if (decisions.contains(Decision.APPROVED)) {
            assertThat(fixtures.transferStatus(id)).isEqualTo("COMPLETED");
            assertThat(fixtures.journalEntriesFor(id)).isEqualTo(1);
            assertThat(from.balance()).isEqualTo(inr(1_000_000));
            assertThat(fixtures.ledgerBalance(receiver.walletId())).isEqualTo(HIGH_VALUE);
        } else {
            assertThat(fixtures.transferStatus(id)).isEqualTo("REJECTED");
            assertThat(fixtures.journalEntriesFor(id)).isZero();
            assertThat(from.balance()).isEqualTo(inr(6_000_000));
        }
        assertThat(fixtures.ledgerBalance(sender.walletId())).isEqualTo(from.balance().amountMinor());
    }
}
