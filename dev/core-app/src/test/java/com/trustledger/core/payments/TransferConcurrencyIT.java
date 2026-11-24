package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.application.TransferCommand;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.application.ReconciliationJob;
import com.trustledger.core.wallet.application.ReconciliationReport;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * M6 "Done when" 2 / must-pass 4: 100 concurrent transfers from one wallet never overdraw it, and the ledger
 * reconciles. The {@code SELECT ... FOR UPDATE} on the sender serialises the balance checks.
 * <p>
 * Tasks start together through {@link ConcurrentRun} (ExecutorService + CountDownLatch, 60 s timeout). Outcomes
 * are the service's own answers: a success or {@code INSUFFICIENT_FUNDS}. Anything else (a lock timeout, an
 * optimistic-lock failure) fails the test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferConcurrencyIT {

    @Autowired
    TransferService service;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    ReconciliationJob reconciliation;

    private enum Outcome { COMPLETED, INSUFFICIENT_FUNDS }

    private Callable<Outcome> transfer(Customer from, Customer to, long minor) {
        TransferCommand command = new TransferCommand(from.userId(), TransferRecipient.byWalletId(to.walletId()),
                Money.of(minor, Money.INR), null);
        return () -> {
            try {
                service.transfer(command);
                return Outcome.COMPLETED;
            } catch (TrustLedgerException e) {
                if (e.errorCode() == ErrorCode.INSUFFICIENT_FUNDS) {
                    return Outcome.INSUFFICIENT_FUNDS;
                }
                throw e;
            }
        };
    }

    @Test
    void hundredConcurrentTransfersWithBalanceForFiftyExactlyFiftySucceed() throws Exception {
        Customer sender = fixtures.customer(WalletTier.FULL, 500_000); // ₹5,000 = 50 x ₹100
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        List<Callable<Outcome>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            tasks.add(transfer(sender, receiver, 10_000));
        }

        List<Outcome> outcomes = ConcurrentRun.together(tasks);

        assertThat(outcomes).filteredOn(o -> o == Outcome.COMPLETED).hasSize(50);
        assertThat(outcomes).filteredOn(o -> o == Outcome.INSUFFICIENT_FUNDS).hasSize(50);
        assertThat(fixtures.wallet(sender.walletId()).balance().isZero()).as("never negative, ends at 0").isTrue();
        assertThat(fixtures.wallet(receiver.walletId()).balance()).isEqualTo(Money.of(500_000, Money.INR));
        assertThat(fixtures.transfersFrom(sender.walletId())).isEqualTo(50);
        // The ledger reconciles: the journal-derived balances equal the cached ones.
        assertThat(fixtures.ledgerBalance(sender.walletId())).isZero();
        assertThat(fixtures.ledgerBalance(receiver.walletId())).isEqualTo(500_000);

        // Must-pass 4 word for word: the real reconciliation job reports no mismatch for either wallet. The test
        // database is shared, so (as in ReconciliationIT) we look at our own wallets in the complete listed part,
        // never at the global totals, which include other tests' deliberately broken wallets.
        ReconciliationReport report = reconciliation.runNow();
        assertThat(report.truncated()).as("our wallets must be in the listed part").isFalse();
        assertThat(report.walletsChecked()).isGreaterThanOrEqualTo(2);
        assertThat(report.listed())
                .filteredOn(m -> m.walletId().equals(sender.walletId()) || m.walletId().equals(receiver.walletId()))
                .as("reconciliation mismatches for the sender and receiver").isEmpty();
    }
}
