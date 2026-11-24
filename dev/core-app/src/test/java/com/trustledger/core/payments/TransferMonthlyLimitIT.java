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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * The FULL monthly transfer limit (₹5,00,000, FR-W-2), split out of {@code TransferServiceIT} so it runs on a fixed
 * clock. With the real clock the test had to skip itself on the 1st of every month: "earlier this month" was then
 * also "today", so the daily limit refused the transfer first and the monthly rule went untested that day.
 * <p>
 * Today is fixed at 16 November 2026, 12:00 IST (the limit zone is Asia/Kolkata), so the 1st is safely another day.
 * The date lies in the past on purpose: the scheduled jobs of this test's application context run against the shared
 * database too, and a clock behind the real one never makes another test's rows look expired.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferMonthlyLimitIT {

    private static final Instant TODAY_NOON_IST = Instant.parse("2026-11-16T06:30:00Z");
    private static final Instant MONTH_START_IST = Instant.parse("2026-10-31T18:30:00Z"); // 1 Nov 00:00 IST

    @TestBean
    Clock clock;

    @Autowired
    TransferService service;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    JdbcTemplate jdbc;

    static Clock clock() {
        return Clock.fixed(TODAY_NOON_IST, ZoneOffset.UTC);
    }

    @Test
    void theMonthlyLimitRefusesOnePaisaOverAndAllowsExactlyTheLimit() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        // ₹4,99,999.99 already sent on the 1st (a held transfer counts, so no journal entry is needed).
        sentEarlier(sender, receiver, 49_999_999, MONTH_START_IST);

        assertThatThrownBy(() -> send(sender, receiver.walletId(), "0.02"))
                .isInstanceOf(TrustLedgerException.class)
                .hasMessageContaining("monthly")
                .extracting(e -> ((TrustLedgerException) e).errorCode()).isEqualTo(ErrorCode.LIMIT_EXCEEDED);
        assertThat(fixtures.wallet(sender.walletId()).balance()).isEqualTo(Money.of(100_000, Money.INR));
        assertThat(fixtures.transfersFrom(sender.walletId())).isEqualTo(1);

        // ₹0.01 lands exactly on ₹5,00,000.
        assertThat(send(sender, receiver.walletId(), "0.01").status()).isEqualTo(TransferStatus.COMPLETED);
    }

    @Test
    void lastMonthsTransfersDoNotCount() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        // ₹4,99,999.99 one second before the month started (31 Oct 23:59:59 IST).
        sentEarlier(sender, receiver, 49_999_999, MONTH_START_IST.minusSeconds(1));

        assertThat(send(sender, receiver.walletId(), "1.00").status()).isEqualTo(TransferStatus.COMPLETED);
    }

    private TransferResult send(Customer from, UUID toWallet, String amount) {
        return service.transfer(new TransferCommand(from.userId(), TransferRecipient.byWalletId(toWallet),
                Money.parse(amount), "Monthly limit test"));
    }

    private void sentEarlier(Customer sender, Customer receiver, long amountMinor, Instant at) {
        jdbc.update("insert into payments.transfers (id, sender_wallet_id, receiver_wallet_id, amount_minor, currency,"
                + " status, created_at, updated_at, version) values (?, ?, ?, ?, 'INR', 'HELD_FOR_REVIEW', ?, ?, 0)",
                UUID.randomUUID(), sender.walletId(), receiver.walletId(), amountMinor, Timestamp.from(at),
                Timestamp.from(at));
    }
}
