package com.trustledger.core.notification;

import static com.trustledger.core.Eventually.eventually;
import static org.assertj.core.api.Assertions.assertThat;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.MailpitClient;
import com.trustledger.core.MailpitClient.Mail;
import com.trustledger.core.TestcontainersConfiguration;
import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.application.TokenIdentity;
import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.KycLevelUpgraded;
import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.EntryType;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.ledger.PostingLine;
import com.trustledger.core.payments.application.TopupSettlementService;
import com.trustledger.core.payments.application.TransferCommand;
import com.trustledger.core.payments.application.TransferRecipient;
import com.trustledger.core.payments.application.TransferResult;
import com.trustledger.core.payments.application.TransferService;
import com.trustledger.core.payments.domain.GatewayPayment;
import com.trustledger.core.payments.domain.GatewayPaymentStatus;
import com.trustledger.core.shared.EventMetadata;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.FreezeSource;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletFrozen;
import com.trustledger.core.wallet.application.WalletComplianceService;
import com.trustledger.core.wallet.application.WalletLedgerLinkService;
import com.trustledger.core.wallet.application.WalletProvisioningService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-N-1 end to end: real events from the real services reach a real SMTP server (Mailpit, Testcontainers), which
 * the test reads through Mailpit's HTTP API. Each customer has a unique fake address, so tests can't see each
 * other's mail.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class NotificationIT {

    private static final Duration WAIT = Duration.ofSeconds(20);

    @Autowired
    UserProvisioningService users;

    @Autowired
    IdentityApi identity;

    @Autowired
    WalletProvisioningService provisioning;

    @Autowired
    WalletLedgerLinkService links;

    @Autowired
    WalletApi wallets;

    @Autowired
    LedgerApi ledger;

    @Autowired
    TransferService transfers;

    @Autowired
    TopupSettlementService settlement;

    @Autowired
    WalletComplianceService compliance;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MailpitClient mailpit;

    record Customer(UUID userId, UUID walletId, String email) {
    }

    // --- fixtures ---

    private static String newEmail() {
        return "notify-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
    }

    private UUID user(String email, String fullName) {
        UUID userId = users.ensureUser(new TokenIdentity(ApiMockMvc.newSubject(), email, fullName));
        identity.recordVerifiedPhone(userId,
                "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000)));
        return userId;
    }

    /** A FULL-KYC customer with a linked wallet funded through the ledger. No KYC event, so no KYC mail. */
    private Customer customer(String fullName, long minor) {
        String email = newEmail();
        UUID userId = user(email, fullName);
        provisioning.apply(userId, KycLevel.FULL);
        UUID walletId = wallets.findByUser(userId).orElseThrow().walletId();
        links.link(walletId);
        if (minor > 0) {
            Money amount = Money.of(minor, Money.INR);
            tx.executeWithoutResult(status -> {
                ledger.post(PostingCommand.of(EntryType.TOPUP, "TOPUP", UUID.randomUUID(), List.of(
                        PostingLine.debit(AccountCodes.GATEWAY_CLEARING, amount),
                        PostingLine.credit(AccountCodes.wallet(walletId), amount))));
                wallets.lockForUpdate(List.of(walletId));
                wallets.credit(walletId, amount);
            });
        }
        return new Customer(userId, walletId, email);
    }

    private List<Mail> eventuallyMailsTo(String address, int count) {
        eventually(WAIT, () -> assertThat(mailpit.messagesTo(address)).hasSize(count));
        return mailpit.messagesTo(address);
    }

    private List<Map<String, Object>> rowsOf(UUID userId) {
        return jdbc.queryForList("""
                select type, dedup_key, status, failure_reason, sent_at from notification.notifications
                where user_id = ? order by created_at""", userId);
    }

    private void eventuallyRows(UUID userId, int count) {
        eventually(WAIT, () -> assertThat(rowsOf(userId)).hasSize(count)
                .allSatisfy(row -> assertThat(row.get("status")).isNotEqualTo("PENDING")));
    }

    // --- tests ---

    @Test
    void aTransferMailsSenderAndReceiverWithMaskedDetails() {
        Customer sender = customer("Sender Person", 100_000);
        Customer receiver = customer("Receiver Person", 0);

        TransferResult result = transfers.transfer(new TransferCommand(sender.userId(),
                TransferRecipient.byWalletId(receiver.walletId()), Money.parse("250.50"), "Notification test"));

        Mail sent = eventuallyMailsTo(sender.email(), 1).getFirst();
        Mail received = eventuallyMailsTo(receiver.email(), 1).getFirst();
        assertThat(sent.from()).isEqualTo("no-reply@trustledger.local");
        assertThat(sent.subject()).isEqualTo("You sent INR 250.50");
        assertThat(sent.text()).contains("to R*** P***")
                .doesNotContain("Receiver", receiver.email(), result.transferId().toString());
        assertThat(received.subject()).isEqualTo("You received INR 250.50");
        assertThat(received.text()).contains("from S*** P***").doesNotContain("Sender", sender.email());

        eventuallyRows(sender.userId(), 1);
        eventuallyRows(receiver.userId(), 1);
        assertThat(rowsOf(sender.userId()).getFirst())
                .containsEntry("type", "TRANSFER_SENT")
                .containsEntry("dedup_key", "TRANSFER_SENT:" + result.transferId())
                .containsEntry("status", "SENT");
        assertThat(rowsOf(receiver.userId()).getFirst())
                .containsEntry("type", "TRANSFER_RECEIVED").containsEntry("status", "SENT");
        assertThat(jdbc.queryForObject("select count(*) from notification.notifications where dedup_key like ?",
                Integer.class, "%" + sender.email() + "%")).as("no address stored").isZero();
    }

    @Test
    void aCapturedTopupMailsTheOwner() {
        Customer customer = customer("Topup Person", 0);
        UUID topupId = UUID.randomUUID();
        String suffix = topupId.toString().replace("-", "").substring(0, 20);
        Instant now = Instant.now();
        jdbc.update("""
                insert into payments.topups (id, wallet_id, amount_minor, currency, gateway_order_id,
                    gateway_payment_id, status, journal_entry_id, created_at, updated_at, version)
                values (?, ?, 50000, 'INR', ?, null, 'PENDING', null, ?, ?, 0)""",
                topupId, customer.walletId(), "order_" + suffix, Timestamp.from(now), Timestamp.from(now));

        settlement.capture(new GatewayPayment("pay_" + suffix.substring(0, 14), "order_" + suffix,
                Money.parse("500.00"), GatewayPaymentStatus.CAPTURED, null));

        Mail mail = eventuallyMailsTo(customer.email(), 1).getFirst();
        assertThat(mail.subject()).isEqualTo("INR 500.00 added to your wallet");
        assertThat(mail.text()).contains("ending " + topupId.toString().substring(32));
        eventuallyRows(customer.userId(), 1);
        assertThat(rowsOf(customer.userId()).getFirst()).containsEntry("type", "TOPUP_CAPTURED")
                .containsEntry("dedup_key", "TOPUP_CAPTURED:" + topupId).containsEntry("status", "SENT");
    }

    @Test
    void aKycUpgradeMailsTheUser() {
        String email = newEmail();
        UUID userId = user(email, "Kyc Person");

        tx.executeWithoutResult(status -> events.publishEvent(new KycLevelUpgraded(userId, KycLevel.MIN)));

        Mail mail = eventuallyMailsTo(email, 1).getFirst();
        assertThat(mail.subject()).isEqualTo("Your KYC is approved");
        assertThat(mail.text()).contains("minimum KYC is approved");
        eventuallyRows(userId, 1);
        assertThat(rowsOf(userId).getFirst()).containsEntry("dedup_key", "KYC_UPGRADED:" + userId + ":MIN");
    }

    @Test
    void aFrozenWalletMailsTheUserWithoutTheReason() {
        Customer customer = customer("Frozen Person", 0);
        String reason = "Officer note that must never reach the customer";

        compliance.freeze(customer.walletId(), UUID.randomUUID(), reason);

        Mail mail = eventuallyMailsTo(customer.email(), 1).getFirst();
        assertThat(mail.subject()).isEqualTo("Your wallet has been frozen");
        assertThat(mail.text()).contains("ending " + customer.walletId().toString().substring(32))
                .doesNotContain(reason, "COMPLIANCE", "AML");
        eventuallyRows(customer.userId(), 1);
        assertThat(rowsOf(customer.userId()).getFirst()).containsEntry("type", "WALLET_FROZEN");
    }

    @Test
    void theSameEventDeliveredTwiceSendsOneMail() {
        Customer customer = customer("Twice Person", 0);
        WalletFrozen event = new WalletFrozen(customer.walletId(), customer.userId(), FreezeSource.AML,
                Instant.now(), new EventMetadata(UUID.randomUUID(), null));

        tx.executeWithoutResult(status -> events.publishEvent(event));
        eventuallyRows(customer.userId(), 1);
        tx.executeWithoutResult(status -> events.publishEvent(event));
        eventually(WAIT, () -> assertThat(jdbc.queryForObject("""
                select count(*) from shared.event_publication
                where completion_date is null and serialized_event like ?""", Integer.class,
                "%" + event.metadata().eventId() + "%")).as("both deliveries handled").isZero());

        assertThat(mailpit.messagesTo(customer.email())).hasSize(1);
        assertThat(rowsOf(customer.userId())).singleElement().satisfies(row -> assertThat(row)
                .containsEntry("dedup_key", "WALLET_FROZEN:" + event.metadata().eventId())
                .containsEntry("status", "SENT"));
    }

    @Test
    void aMailServerOutageIsRecordedAndNeverUndoesTheBusinessChange() {
        Customer customer = customer("Outage Person", 0);

        mailpit.pause();
        try {
            compliance.freeze(customer.walletId(), UUID.randomUUID(), "Outage test freeze");

            eventuallyRows(customer.userId(), 1);
        } finally {
            mailpit.unpause();
        }

        assertThat(wallets.findById(customer.walletId()).orElseThrow().status().name())
                .as("the freeze is committed").isEqualTo("FROZEN");
        assertThat(rowsOf(customer.userId()).getFirst())
                .containsEntry("status", "FAILED")
                .containsEntry("sent_at", null)
                .hasEntrySatisfying("failure_reason", reason -> assertThat((String) reason)
                        .endsWith("Exception").doesNotContain("@"));
        eventually(WAIT, () -> assertThat(jdbc.queryForObject("""
                select count(*) from shared.event_publication
                where completion_date is null and listener_id like '%.notification.%' and serialized_event like ?""",
                Integer.class, "%" + customer.walletId() + "%"))
                .as("the failure was handled, so the outbox does not resend it forever").isZero());
        assertThat(mailpit.messagesTo(customer.email())).isEmpty();
    }

    @Test
    void aUserWithoutAnEmailIsSkipped() {
        UUID userId = user(null, "No Mail Person");

        tx.executeWithoutResult(status -> events.publishEvent(new KycLevelUpgraded(userId, KycLevel.MIN)));

        eventuallyRows(userId, 1);
        assertThat(rowsOf(userId).getFirst()).containsEntry("status", "SKIPPED")
                .containsEntry("failure_reason", "NO_CONTACT");
    }
}
