package com.trustledger.core.payments;

import static com.trustledger.core.ApiMockMvc.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.application.HeldTransferService;
import com.trustledger.core.payments.application.PaymentsMetrics;
import com.trustledger.core.payments.application.TransferCommand;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.payments.application.TransferResult;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.payments.domain.TransferStatus;
import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.TrustLedgerException;
import com.trustledger.core.wallet.WalletTier;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

/**
 * M12: {@code trustledger.transfers{status}} and {@code trustledger.transfer.amount} against the real services and
 * database. The registry is shared by every test in the context, so each test compares before and after.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferMetricsIT {

    private static final long HIGH_VALUE = 5_000_000; // ₹50,000, the configured threshold

    @Autowired
    TransferService transfers;

    @Autowired
    HeldTransferService review;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    MeterRegistry meters;

    @Autowired
    WebApplicationContext context;

    private Customer sender;
    private Customer receiver;
    private Map<TransferStatus, Double> before;
    private long amountsBefore;
    private double amountTotalBefore;

    @BeforeEach
    void setUp() {
        sender = fixtures.customer(WalletTier.FULL, 10_000_000); // ₹1,00,000
        receiver = fixtures.customer(WalletTier.FULL, 0);
        before = snapshot();
        amountsBefore = amounts().count();
        amountTotalBefore = amounts().totalAmount();
    }

    @Test
    void aCompletedTransferIsCountedWithItsAmount() {
        send(Money.parse("1234.50"));

        assertThat(increase(TransferStatus.COMPLETED)).isEqualTo(1.0);
        assertThat(amounts().count() - amountsBefore).isEqualTo(1);
        assertThat(amounts().totalAmount() - amountTotalBefore).isEqualTo(1234.50);
        assertThat(otherIncreases(TransferStatus.COMPLETED)).isZero();
    }

    @Test
    void aRefusedTransferCountsAsFailedAndRecordsNoAmount() {
        assertThatThrownBy(() -> send(Money.parse("200000.00"))).isInstanceOf(TrustLedgerException.class);

        assertThat(increase(TransferStatus.FAILED)).isEqualTo(1.0);
        assertThat(amounts().count()).isEqualTo(amountsBefore);
        assertThat(otherIncreases(TransferStatus.FAILED)).isZero();
    }

    @Test
    void aHeldTransferCountsAsHeldThenAsRejected() {
        TransferResult held = send(Money.of(HIGH_VALUE, Money.INR));
        assertThat(held.status()).isEqualTo(TransferStatus.HELD_FOR_REVIEW);
        assertThat(increase(TransferStatus.HELD_FOR_REVIEW)).isEqualTo(1.0);

        review.reject(held.transferId(), UUID.randomUUID());

        assertThat(increase(TransferStatus.REJECTED)).isEqualTo(1.0);
        assertThat(increase(TransferStatus.COMPLETED)).isZero();
    }

    @Test
    void anApprovedHeldTransferCountsAsCompleted() {
        TransferResult held = send(Money.of(HIGH_VALUE, Money.INR));

        review.approve(held.transferId(), UUID.randomUUID());

        assertThat(increase(TransferStatus.HELD_FOR_REVIEW)).isEqualTo(1.0);
        assertThat(increase(TransferStatus.COMPLETED)).isEqualTo(1.0);
        assertThat(amounts().count() - amountsBefore).as("the amount is recorded once, when it was held")
                .isEqualTo(1);
    }

    @Test
    void anIdempotentReplayIsNotCountedAgain() throws Exception {
        MockMvc mvc = ApiMockMvc.create(context);
        String key = UUID.randomUUID().toString();
        String body = "{\"recipientWalletId\":\"" + receiver.walletId() + "\",\"amount\":\"75.00\",\"note\":\"Tea\"}";

        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/transfers").with(customer(sender.subject())).header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        }

        assertThat(increase(TransferStatus.COMPLETED)).isEqualTo(1.0);
    }

    private TransferResult send(Money amount) {
        return transfers.transfer(new TransferCommand(sender.userId(),
                TransferRecipient.byWalletId(receiver.walletId()), amount, "Metrics test"));
    }

    private Map<TransferStatus, Double> snapshot() {
        Map<TransferStatus, Double> counts = new EnumMap<>(TransferStatus.class);
        for (TransferStatus status : TransferStatus.values()) {
            counts.put(status, count(status));
        }
        return counts;
    }

    private double count(TransferStatus status) {
        return meters.get(PaymentsMetrics.TRANSFERS).tag("status", status.name()).counter().count();
    }

    private double increase(TransferStatus status) {
        return count(status) - before.get(status);
    }

    private double otherIncreases(TransferStatus except) {
        double sum = 0;
        for (TransferStatus status : TransferStatus.values()) {
            if (status != except) {
                sum += increase(status);
            }
        }
        return sum;
    }

    private DistributionSummary amounts() {
        return meters.get(PaymentsMetrics.TRANSFER_AMOUNT).summary();
    }
}
