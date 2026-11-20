package com.trustledger.core.payments;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.identity.IdentityApi;
import com.trustledger.core.identity.application.TokenIdentity;
import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.EntryType;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.ledger.PostingLine;
import com.trustledger.core.shared.Money;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.WalletTier;
import com.trustledger.core.wallet.WalletView;
import com.trustledger.core.wallet.application.WalletComplianceService;
import com.trustledger.core.wallet.application.WalletLedgerLinkService;
import com.trustledger.core.wallet.application.WalletProvisioningService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Test support for transfer ITs, through the real services. Funding is <b>ledger-consistent</b>: a TOPUP journal
 * entry plus the matching wallet credit, as a captured top-up will do in M7, so "the ledger reconciles" can be
 * asserted after transfers.
 */
@Component
class PaymentsFixtures {

    /** A provisioned customer: the token subject (for MockMvc), our user ID, their wallet, their mobile. */
    record Customer(String subject, UUID userId, UUID walletId, String phone) {
    }

    private final UserProvisioningService users;
    private final IdentityApi identity;
    private final WalletProvisioningService provisioning;
    private final WalletLedgerLinkService links;
    private final WalletComplianceService compliance;
    private final WalletApi wallets;
    private final LedgerApi ledger;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;

    PaymentsFixtures(UserProvisioningService users, IdentityApi identity, WalletProvisioningService provisioning,
            WalletLedgerLinkService links, WalletComplianceService compliance, WalletApi wallets, LedgerApi ledger,
            TransactionTemplate tx, JdbcTemplate jdbc) {
        this.users = users;
        this.identity = identity;
        this.provisioning = provisioning;
        this.links = links;
        this.compliance = compliance;
        this.wallets = wallets;
        this.ledger = ledger;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    static String randomPhone() {
        return "9" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    /** A user with a verified mobile and no wallet (no KYC yet). */
    Customer userWithoutWallet() {
        String subject = ApiMockMvc.newSubject();
        String phone = randomPhone();
        UUID userId = users.ensureUser(new TokenIdentity(subject, null, "Transfer Test User"));
        identity.recordVerifiedPhone(userId, phone);
        return new Customer(subject, userId, null, phone);
    }

    /** A customer with a linked wallet of the tier, funded with {@code minor} paise through the ledger. */
    Customer customer(WalletTier tier, long minor) {
        Customer user = userWithoutWallet();
        provisioning.apply(user.userId(), tier == WalletTier.FULL ? KycLevel.FULL : KycLevel.MIN);
        UUID walletId = wallets.findByUser(user.userId()).orElseThrow().walletId();
        links.link(walletId);
        if (minor > 0) {
            fund(walletId, minor);
        }
        return new Customer(user.subject(), user.userId(), walletId, user.phone());
    }

    /** TOPUP posting and wallet credit in one transaction, so cache and ledger agree. */
    void fund(UUID walletId, long minor) {
        Money amount = Money.of(minor, Money.INR);
        tx.executeWithoutResult(status -> {
            ledger.post(PostingCommand.of(EntryType.TOPUP, "TOPUP", UUID.randomUUID(), List.of(
                    PostingLine.debit(AccountCodes.GATEWAY_CLEARING, amount),
                    PostingLine.credit(AccountCodes.wallet(walletId), amount))));
            wallets.lockForUpdate(List.of(walletId));
            wallets.credit(walletId, amount);
        });
    }

    void freeze(UUID walletId) {
        compliance.freeze(walletId, UUID.randomUUID(), "Transfer test freeze");
    }

    WalletView wallet(UUID walletId) {
        return wallets.findById(walletId).orElseThrow();
    }

    /** The wallet's balance derived from the journal, in paise. */
    long ledgerBalance(UUID walletId) {
        return ledger.balanceOf(AccountCodes.wallet(walletId)).balanceMinor();
    }

    long transfersFrom(UUID senderWalletId) {
        return jdbc.queryForObject("select count(*) from payments.transfers where sender_wallet_id = ?",
                Long.class, senderWalletId);
    }

    String transferStatus(UUID transferId) {
        return jdbc.queryForObject("select status from payments.transfers where id = ?", String.class, transferId);
    }

    long journalEntriesFor(UUID transferId) {
        return jdbc.queryForObject("select count(*) from ledger.journal_entries where reference_type = 'TRANSFER'"
                + " and reference_id = ? and entry_type = 'P2P_TRANSFER'", Long.class, transferId);
    }

    // --- top-ups (M7) ---

    /** Inserts a top-up row directly, e.g. to use up part of a monthly load. Gateway IDs follow the V13 rules. */
    UUID insertTopup(UUID walletId, long minor, String status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        String suffix = id.toString().replace("-", "").substring(0, 20);
        String orderId = status.equals("CREATED") ? null : "order_" + suffix;
        String paymentId = status.equals("CAPTURED") ? "pay_" + suffix : null;
        UUID entryId = status.equals("CAPTURED") ? UUID.randomUUID() : null;
        jdbc.update("""
                insert into payments.topups (id, wallet_id, amount_minor, currency, gateway_order_id,
                    gateway_payment_id, status, journal_entry_id, created_at, updated_at, version)
                values (?, ?, ?, 'INR', ?, ?, ?, ?, ?, ?, 0)""",
                id, walletId, minor, orderId, paymentId, status, entryId, Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        return id;
    }

    long topupsOf(UUID walletId) {
        return jdbc.queryForObject("select count(*) from payments.topups where wallet_id = ?", Long.class, walletId);
    }

    long topupsIn(UUID walletId, String status) {
        return jdbc.queryForObject("select count(*) from payments.topups where wallet_id = ? and status = ?",
                Long.class, walletId, status);
    }

    String topupStatus(UUID topupId) {
        return jdbc.queryForObject("select status from payments.topups where id = ?", String.class, topupId);
    }

    /** Number of TOPUP journal entries posted for the top-up (the unique reference allows at most one). */
    long topupEntries(UUID topupId) {
        return jdbc.queryForObject("select count(*) from ledger.journal_entries where reference_type = 'TOPUP'"
                + " and reference_id = ? and entry_type = 'TOPUP'", Long.class, topupId);
    }

    /** The lines of the top-up's journal entry as {@code "ACCOUNT DIRECTION amountMinor"}, in line order. */
    List<String> topupEntryLines(UUID topupId) {
        return jdbc.queryForList("""
                select a.code || ' ' || l.direction || ' ' || l.amount_minor
                from payments.topups t
                join ledger.journal_lines l on l.entry_id = t.journal_entry_id
                join ledger.ledger_accounts a on a.id = l.account_id
                where t.id = ? order by l.line_no""", String.class, topupId);
    }

    String topupPaymentId(UUID topupId) {
        return jdbc.queryForObject("select gateway_payment_id from payments.topups where id = ?", String.class,
                topupId);
    }

    String topupOrderId(UUID topupId) {
        return jdbc.queryForObject("select gateway_order_id from payments.topups where id = ?", String.class,
                topupId);
    }
}
