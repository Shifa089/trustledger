package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
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
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SPEC §4 "Transfer flow" steps 2 to 7 against real PostgreSQL, the real ledger and the real wallets (M6 "Done
 * when" 4 and the hold half of 5). Every refusal also proves that <em>nothing</em> changed: both cached balances,
 * both ledger balances, and no transfer row.
 * <p>
 * Limits used here come from {@code application.yml}: FULL daily ₹1,00,000, monthly ₹5,00,000, high-value
 * threshold ₹50,000, max balance ₹2,00,000; MIN can't send P2P.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferServiceIT {

    @Autowired
    TransferService service;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    JdbcTemplate jdbc;

    private static Money rupees(String amount) {
        return Money.parse(amount);
    }

    private TransferResult send(Customer from, UUID toWallet, String amount) {
        return service.transfer(new TransferCommand(from.userId(), TransferRecipient.byWalletId(toWallet),
                rupees(amount), "Test transfer"));
    }

    /** Snapshot of everything a refused transfer must leave untouched. */
    private record State(WalletView sender, WalletView receiver, long senderLedger, long receiverLedger,
            long senderTransfers) {
    }

    private State state(Customer sender, Customer receiver) {
        return new State(fixtures.wallet(sender.walletId()), fixtures.wallet(receiver.walletId()),
                fixtures.ledgerBalance(sender.walletId()), fixtures.ledgerBalance(receiver.walletId()),
                fixtures.transfersFrom(sender.walletId()));
    }

    private void refused(Customer sender, Customer receiver, Runnable attempt, ErrorCode code) {
        State before = state(sender, receiver);
        assertThatThrownBy(attempt::run).isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(code);
        assertThat(state(sender, receiver)).as("nothing moved").isEqualTo(before);
    }

    // ---- happy paths ---------------------------------------------------------------------------------------

    @Test
    void completesByWalletIdMovingCacheAndLedgerTogether() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);

        TransferResult result = send(sender, receiver.walletId(), "250.50");

        assertThat(result.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.senderWalletId()).isEqualTo(sender.walletId());
        assertThat(result.receiverWalletId()).isEqualTo(receiver.walletId());
        assertThat(result.amount()).isEqualTo(rupees("250.50"));
        assertThat(result.note()).isEqualTo("Test transfer");

        assertThat(fixtures.wallet(sender.walletId()).balance()).isEqualTo(Money.of(74_950, Money.INR));
        assertThat(fixtures.wallet(receiver.walletId()).balance()).isEqualTo(Money.of(25_050, Money.INR));
        assertThat(fixtures.ledgerBalance(sender.walletId())).isEqualTo(74_950);
        assertThat(fixtures.ledgerBalance(receiver.walletId())).isEqualTo(25_050);
        assertThat(fixtures.journalEntriesFor(result.transferId())).isOne();

        UUID entryId = jdbc.queryForObject("select journal_entry_id from payments.transfers where id = ?",
                UUID.class, result.transferId());
        assertThat(entryId).isNotNull();
        assertThat(jdbc.queryForObject("select status from payments.transfers where id = ?", String.class,
                result.transferId())).isEqualTo("COMPLETED");
    }

    @Test
    void completesByMobileNumber() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);

        TransferResult result = service.transfer(new TransferCommand(sender.userId(),
                TransferRecipient.byMobile(receiver.phone()), rupees("10.00"), null));

        assertThat(result.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.receiverWalletId()).isEqualTo(receiver.walletId());
        assertThat(result.note()).isNull();
        assertThat(fixtures.wallet(receiver.walletId()).balance()).isEqualTo(Money.of(1_000, Money.INR));
    }

    @Test
    void spendingTheWholeAvailableBalanceIsAllowed() {
        Customer sender = fixtures.customer(WalletTier.FULL, 12_345);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);

        send(sender, receiver.walletId(), "123.45");

        assertThat(fixtures.wallet(sender.walletId()).balance().isZero()).isTrue();
        assertThat(fixtures.ledgerBalance(sender.walletId())).isZero();
    }

    // ---- high value (FR-P-3) -------------------------------------------------------------------------------

    @Test
    void atTheHighValueThresholdTheAmountIsHeldAndNothingIsPosted() {
        Customer sender = fixtures.customer(WalletTier.FULL, 6_000_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);

        TransferResult result = send(sender, receiver.walletId(), "50000.00");

        assertThat(result.status()).isEqualTo(TransferStatus.HELD_FOR_REVIEW);
        WalletView after = fixtures.wallet(sender.walletId());
        assertThat(after.balance()).isEqualTo(Money.of(6_000_000, Money.INR));
        assertThat(after.held()).isEqualTo(Money.of(5_000_000, Money.INR));
        assertThat(after.available()).isEqualTo(Money.of(1_000_000, Money.INR));
        assertThat(fixtures.wallet(receiver.walletId()).balance().isZero()).isTrue();
        assertThat(fixtures.ledgerBalance(sender.walletId())).isEqualTo(6_000_000);
        assertThat(fixtures.journalEntriesFor(result.transferId())).isZero();
    }

    @Test
    void oneRupeeBelowTheThresholdCompletes() {
        Customer sender = fixtures.customer(WalletTier.FULL, 6_000_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);

        assertThat(send(sender, receiver.walletId(), "49999.99").status()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void heldMoneyIsNotAvailableForTheNextTransfer() {
        Customer sender = fixtures.customer(WalletTier.FULL, 5_000_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        send(sender, receiver.walletId(), "50000.00");

        refused(sender, receiver, () -> send(sender, receiver.walletId(), "0.01"), ErrorCode.INSUFFICIENT_FUNDS);
    }

    // ---- each rejection rule (FR-P-2) ----------------------------------------------------------------------

    @Test
    void frozenSenderIsRefused() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        fixtures.freeze(sender.walletId());

        refused(sender, receiver, () -> send(sender, receiver.walletId(), "10.00"), ErrorCode.WALLET_FROZEN);
    }

    @Test
    void frozenReceiverIsRefused() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        fixtures.freeze(receiver.walletId());

        refused(sender, receiver, () -> send(sender, receiver.walletId(), "10.00"), ErrorCode.WALLET_FROZEN);
    }

    @Test
    void minTierSenderNeedsFullKyc() {
        Customer sender = fixtures.customer(WalletTier.MIN, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);

        refused(sender, receiver, () -> send(sender, receiver.walletId(), "10.00"), ErrorCode.KYC_REQUIRED);
    }

    @Test
    void callerWithoutAWalletNeedsKyc() {
        Customer noWallet = fixtures.userWithoutWallet();
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);

        assertThatThrownBy(() -> send(noWallet, receiver.walletId(), "10.00"))
                .isInstanceOf(TrustLedgerException.class)
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.KYC_REQUIRED);
        assertThat(fixtures.wallet(receiver.walletId()).balance().isZero()).isTrue();
    }

    @Test
    void insufficientFundsIsRefused() {
        Customer sender = fixtures.customer(WalletTier.FULL, 10_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);

        refused(sender, receiver, () -> send(sender, receiver.walletId(), "100.01"), ErrorCode.INSUFFICIENT_FUNDS);
    }

    @Test
    void dailyLimitIsRefusedOnePaisaOver() {
        Customer sender = fixtures.customer(WalletTier.FULL, 15_000_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        send(sender, receiver.walletId(), "49999.99");
        send(sender, receiver.walletId(), "49999.99");

        // ₹99,999.98 used today: ₹0.02 lands exactly on ₹1,00,000, ₹0.03 is one paisa over.
        refused(sender, receiver, () -> send(sender, receiver.walletId(), "0.03"), ErrorCode.LIMIT_EXCEEDED);
        assertThat(send(sender, receiver.walletId(), "0.02").status()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void receiverBalanceCapIsRefused() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 19_999_950); // ₹1,99,999.50 of ₹2,00,000

        refused(sender, receiver, () -> send(sender, receiver.walletId(), "1.00"), ErrorCode.LIMIT_EXCEEDED);
        assertThat(send(sender, receiver.walletId(), "0.50").status()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void sendingToYourOwnWalletIsRefused() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);

        refused(sender, sender, () -> send(sender, sender.walletId(), "10.00"), ErrorCode.VALIDATION_FAILED);
        refused(sender, sender, () -> service.transfer(new TransferCommand(sender.userId(),
                TransferRecipient.byMobile(sender.phone()), rupees("10.00"), null)), ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void unknownRecipientIsNotFound() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);

        refused(sender, sender, () -> send(sender, UUID.randomUUID(), "10.00"), ErrorCode.NOT_FOUND);
        refused(sender, sender, () -> service.transfer(new TransferCommand(sender.userId(),
                TransferRecipient.byMobile(PaymentsFixtures.randomPhone()), rupees("10.00"), null)),
                ErrorCode.NOT_FOUND);
    }

    @Test
    void zeroAmountIsRefused() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);

        refused(sender, receiver, () -> send(sender, receiver.walletId(), "0.00"), ErrorCode.VALIDATION_FAILED);
    }
}
