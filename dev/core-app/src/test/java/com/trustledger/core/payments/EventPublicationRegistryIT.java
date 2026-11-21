package com.trustledger.core.payments;

import static com.trustledger.core.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.payments.PaymentsFixtures.Customer;
import com.trustledger.core.payments.application.TransferCommand;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.payments.application.TransferResult;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletTier;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox (M8, ADR 0005): Spring Modulith's JDBC event publication registry in {@code shared.event_publication}
 * (Flyway V16), against real PostgreSQL. The publication is written in the transfer's own transaction, so it exists
 * exactly when the money moved, and a stored publication can be delivered again with the same event identity.
 * <p>
 * Module listeners run asynchronously after the commit (the registry turns on {@code @EnableAsync}), hence
 * {@link com.trustledger.core.Eventually}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EventPublicationRegistryIT {

    @Autowired
    TransferService transfers;

    @Autowired
    PaymentsFixtures fixtures;

    @Autowired
    TransferCompletedRecorder recorder;

    @Autowired
    IncompleteEventPublications incomplete;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    private TransferResult send(Customer from, Customer to, String amount) {
        return transfers.transfer(new TransferCommand(from.userId(), TransferRecipient.byWalletId(to.walletId()),
                Money.parse(amount), "Outbox test"));
    }

    /** The registry rows of one transfer's TransferCompleted (the serialized event holds the transfer ID). */
    private List<Map<String, Object>> publicationsOf(UUID transferId) {
        return jdbc.queryForList("""
                select listener_id, serialized_event, completion_date, status
                from shared.event_publication
                where event_type = ? and serialized_event like ?
                """, TransferCompleted.class.getName(), "%" + transferId + "%");
    }

    private long transferPublicationsFromSender(UUID senderWalletId) {
        return jdbc.queryForObject("""
                select count(*) from shared.event_publication where event_type = ? and serialized_event like ?
                """, Long.class, TransferCompleted.class.getName(), "%" + senderWalletId + "%");
    }

    @Test
    void aCompletedTransferLeavesOneCompletedPublicationWithTheFullEvent() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);

        TransferResult result = send(sender, receiver, "250.00");

        // The row exists as soon as the transfer has committed; the listener runs after that.
        assertThat(publicationsOf(result.transferId())).hasSize(1);
        eventually(() -> assertThat(recorder.receivedFor(result.transferId())).hasSize(1));
        TransferCompleted event = recorder.receivedFor(result.transferId()).getFirst();

        eventually(() -> assertThat(publicationsOf(result.transferId())).singleElement().satisfies(row -> {
            assertThat((String) row.get("listener_id")).contains(TransferCompletedRecorder.class.getName());
            assertThat(row.get("completion_date")).as("listener succeeded").isNotNull();
            assertThat((String) row.get("serialized_event"))
                    .contains(event.metadata().eventId().toString())
                    .contains(sender.walletId().toString(), receiver.walletId().toString());
        }));
    }

    @Test
    void whenTheMoneyMovementRollsBackThePublicationRollsBackWithIt() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);

        // TransferService joins the outer transaction: the event IS published, then everything rolls back.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            send(sender, receiver, "100.00");
            throw new IllegalStateException("Simulated failure after the transfer, before commit");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(fixtures.transfersFrom(sender.walletId())).as("no transfer").isZero();
        assertThat(transferPublicationsFromSender(sender.walletId())).as("no publication: no dual write").isZero();
    }

    @Test
    void aFailedListenerLeavesAnIncompletePublicationThatIsResubmittedWithTheSameEventId() {
        Customer sender = fixtures.customer(WalletTier.FULL, 100_000);
        Customer receiver = fixtures.customer(WalletTier.FULL, 0);
        recorder.failForSender(sender.walletId());

        TransferResult result = send(sender, receiver, "75.25");

        // The money moved and the transfer committed, even though the listener failed after the commit.
        eventually(() -> assertThat(recorder.receivedFor(result.transferId())).hasSize(1));
        eventually(() -> assertThat(publicationsOf(result.transferId())).singleElement()
                .satisfies(row -> assertThat(row.get("status")).as("marked failed").isEqualTo("FAILED")));
        assertThat(publicationsOf(result.transferId())).singleElement()
                .satisfies(row -> assertThat(row.get("completion_date")).as("still incomplete").isNull());

        recorder.recoverSender(sender.walletId());
        incomplete.resubmitIncompletePublications(publication -> publication.getEvent() instanceof TransferCompleted e
                && e.transferId().equals(result.transferId()));

        // Read back from the stored JSON: Money, Currency, Instant and EventMetadata survive, so the Kafka
        // mapping of the resubmitted event gives the same contract, with the same eventId (consumers de-duplicate).
        eventually(() -> assertThat(recorder.receivedFor(result.transferId())).hasSize(2));
        List<TransferCompleted> delivered = recorder.receivedFor(result.transferId());
        assertThat(delivered.get(1)).isEqualTo(delivered.get(0));
        assertThat(delivered.get(1).toV1()).isEqualTo(delivered.get(0).toV1());
        eventually(() -> assertThat(publicationsOf(result.transferId())).singleElement()
                .satisfies(row -> assertThat(row.get("completion_date")).as("completed now").isNotNull()));
    }
}
