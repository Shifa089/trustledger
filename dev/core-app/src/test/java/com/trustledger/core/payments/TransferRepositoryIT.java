package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.domain.Transfer;
import com.trustledger.core.payments.domain.TransferRepository;
import com.trustledger.core.payments.domain.TransferStatus;
import com.trustledger.core.shared.Money;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** The Transfer aggregate's mapping, optimistic locking and the limit-check sum against PostgreSQL. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferRepositoryIT {

    private static final Instant T0 = Instant.parse("2026-11-19T04:00:00Z");
    private static final Set<TransferStatus> COUNTED = EnumSet.of(TransferStatus.COMPLETED,
            TransferStatus.HELD_FOR_REVIEW);

    @Autowired
    TransferRepository transfers;

    private Transfer saved(UUID sender, String amount, Instant at) {
        return transfers.save(Transfer.initiate(sender, UUID.randomUUID(), Money.parse(amount), null, at));
    }

    @Test
    void roundTripKeepsEveryField() {
        UUID sender = UUID.randomUUID();
        UUID receiver = UUID.randomUUID();
        UUID officer = UUID.randomUUID();
        UUID entry = UUID.randomUUID();
        Transfer transfer = Transfer.initiate(sender, receiver, Money.parse("50000.00"), "flat deposit", T0);
        transfer.holdForReview(T0);
        UUID id = transfers.save(transfer).getId();

        Transfer held = transfers.findById(id).orElseThrow();
        held.approve(officer, entry, T0.plusSeconds(600));
        transfers.save(held);

        Transfer loaded = transfers.findById(id).orElseThrow();
        assertThat(loaded.getId()).isNotNull().isEqualTo(id);
        assertThat(loaded.getSenderWalletId()).isEqualTo(sender);
        assertThat(loaded.getReceiverWalletId()).isEqualTo(receiver);
        assertThat(loaded.amount()).isEqualTo(Money.of(5_000_000, Money.INR));
        assertThat(loaded.getNote()).isEqualTo("flat deposit");
        assertThat(loaded.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(loaded.getJournalEntryId()).isEqualTo(entry);
        assertThat(loaded.getReviewedBy()).isEqualTo(officer);
        assertThat(loaded.getReviewedAt()).isEqualTo(T0.plusSeconds(600));
        assertThat(loaded.getCreatedAt()).isEqualTo(T0);
        assertThat(loaded.getVersion()).isEqualTo(1);
    }

    @Test
    void idsAreTimeOrderedUuidV7() {
        assertThat(saved(UUID.randomUUID(), "1.00", T0).getId().version()).isEqualTo(7);
    }

    @Test
    void staleCopyLosesOnTheVersion() {
        UUID id = saved(UUID.randomUUID(), "10.00", T0).getId();
        Transfer first = transfers.findById(id).orElseThrow();
        Transfer second = transfers.findById(id).orElseThrow();

        first.holdForReview(T0);
        transfers.save(first);
        second.fail(T0);

        assertThatThrownBy(() -> transfers.save(second)).isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(transfers.findById(id).orElseThrow().getStatus()).isEqualTo(TransferStatus.HELD_FOR_REVIEW);
    }

    @Test
    void sumCountsTheSendersTransfersInTheWindowWithTheGivenStatuses() {
        UUID sender = UUID.randomUUID();
        Transfer completed = Transfer.initiate(sender, UUID.randomUUID(), Money.parse("100.00"), null, T0);
        completed.complete(UUID.randomUUID(), T0);
        transfers.save(completed);
        Transfer held = Transfer.initiate(sender, UUID.randomUUID(), Money.parse("50000.00"), null, T0);
        held.holdForReview(T0);
        transfers.save(held);
        Transfer failed = Transfer.initiate(sender, UUID.randomUUID(), Money.parse("7.00"), null, T0);
        failed.fail(T0);
        transfers.save(failed);
        Transfer rejected = Transfer.initiate(sender, UUID.randomUUID(), Money.parse("9.00"), null, T0);
        rejected.holdForReview(T0);
        rejected.reject(UUID.randomUUID(), T0);
        transfers.save(rejected);
        Transfer earlier = Transfer.initiate(sender, UUID.randomUUID(), Money.parse("1000.00"), null,
                T0.minusSeconds(1));
        earlier.complete(UUID.randomUUID(), T0);
        transfers.save(earlier);
        Transfer someoneElse = Transfer.initiate(UUID.randomUUID(), sender, Money.parse("3.00"), null, T0);
        someoneElse.complete(UUID.randomUUID(), T0);
        transfers.save(someoneElse);

        // From T0 inclusive: completed + held only.
        assertThat(transfers.sumOutgoingSince(sender, T0, COUNTED)).isEqualTo(10_000 + 5_000_000);
        // One second earlier also includes the earlier transfer.
        assertThat(transfers.sumOutgoingSince(sender, T0.minusSeconds(1), COUNTED))
                .isEqualTo(10_000 + 5_000_000 + 100_000);
        assertThat(transfers.sumOutgoingSince(sender, T0, EnumSet.of(TransferStatus.FAILED))).isEqualTo(700);
    }

    @Test
    void sumOfNothingIsZero() {
        assertThat(transfers.sumOutgoingSince(UUID.randomUUID(), T0, COUNTED)).isZero();
    }
}
