package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.domain.Topup;
import com.trustledger.core.payments.domain.TopupRepository;
import com.trustledger.core.payments.domain.TopupStatus;
import com.trustledger.core.shared.Money;
import java.time.Instant;
import java.util.EnumSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

/** The JPA mapping of {@link Topup} against V13, and the two sums behind the top-up limits. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TopupRepositoryIT {

    private static final Instant MONTH_START = Instant.parse("2026-10-31T18:30:00Z");

    @Autowired
    TopupRepository topups;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    TransactionTemplate tx;

    @Test
    void savesAndReadsBackEveryField() {
        UUID wallet = UUID.randomUUID();
        Instant now = Instant.parse("2026-11-19T10:00:00Z");
        Topup saved = tx.execute(status -> {
            Topup topup = topups.saveAndFlush(Topup.create(wallet, Money.parse("1500.50"), now));
            topup.markPending("order_REPO" + topup.getId().toString().substring(0, 8), now.plusSeconds(1));
            topup.capture("pay_REPO" + topup.getId().toString().substring(0, 8), UUID.randomUUID(), now.plusSeconds(2));
            return topups.saveAndFlush(topup);
        });

        Topup read = topups.findById(saved.getId()).orElseThrow();
        assertThat(read.getId()).isNotNull();
        assertThat(read.getWalletId()).isEqualTo(wallet);
        assertThat(read.amount()).isEqualTo(Money.parse("1500.50"));
        assertThat(read.getStatus()).isEqualTo(TopupStatus.CAPTURED);
        assertThat(read.getGatewayOrderId()).startsWith("order_REPO");
        assertThat(read.getGatewayPaymentId()).startsWith("pay_REPO");
        assertThat(read.getJournalEntryId()).isEqualTo(saved.getJournalEntryId());
        assertThat(read.getCreatedAt()).isEqualTo(now);
        assertThat(read.getUpdatedAt()).isEqualTo(now.plusSeconds(2));
        assertThat(read.getVersion()).isPositive();
    }

    @Test
    void sumCreatedSinceFiltersByWalletWindowAndStatus() {
        UUID wallet = UUID.randomUUID();
        fixtures.insertTopup(wallet, 100, "CREATED", MONTH_START);                   // on the boundary: counts
        fixtures.insertTopup(wallet, 200, "PENDING", MONTH_START.plusSeconds(60));
        fixtures.insertTopup(wallet, 400, "CAPTURED", MONTH_START.plusSeconds(120));
        fixtures.insertTopup(wallet, 800, "FAILED", MONTH_START.plusSeconds(180));   // never counts
        fixtures.insertTopup(wallet, 1_600, "EXPIRED", MONTH_START.plusSeconds(240)); // never counts
        fixtures.insertTopup(wallet, 3_200, "CAPTURED", MONTH_START.minusSeconds(1)); // last month
        fixtures.insertTopup(UUID.randomUUID(), 6_400, "CAPTURED", MONTH_START);      // another wallet

        assertThat(topups.sumCreatedSince(wallet, MONTH_START, TopupStatus.COUNTED_FOR_LOAD)).isEqualTo(700);
        assertThat(topups.sumCreatedSince(wallet, MONTH_START, EnumSet.of(TopupStatus.FAILED))).isEqualTo(800);
        assertThat(topups.sumCreatedSince(UUID.randomUUID(), MONTH_START, TopupStatus.COUNTED_FOR_LOAD)).isZero();
    }

    @Test
    void sumByStatusIgnoresTimeAndCountsOpenOnly() {
        UUID wallet = UUID.randomUUID();
        fixtures.insertTopup(wallet, 100, "CREATED", MONTH_START.minusSeconds(86_400 * 40L)); // old but open
        fixtures.insertTopup(wallet, 200, "PENDING", MONTH_START);
        fixtures.insertTopup(wallet, 400, "CAPTURED", MONTH_START);
        fixtures.insertTopup(wallet, 800, "FAILED", MONTH_START);

        assertThat(topups.sumByStatus(wallet, TopupStatus.OPEN)).isEqualTo(300);
        assertThat(topups.sumByStatus(UUID.randomUUID(), TopupStatus.OPEN)).isZero();
    }
}
