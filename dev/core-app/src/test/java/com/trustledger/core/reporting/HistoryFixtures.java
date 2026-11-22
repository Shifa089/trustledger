package com.trustledger.core.reporting;

import com.trustledger.core.ApiMockMvc;
import com.trustledger.core.identity.application.TokenIdentity;
import com.trustledger.core.identity.application.UserProvisioningService;
import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.wallet.WalletApi;
import com.trustledger.core.wallet.application.WalletProvisioningService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Test support for the history: a customer with a wallet (through the real services), and transfer and top-up rows
 * written straight into the payments tables with exact timestamps and statuses. The history is a read model, so its
 * tests need precise control over {@code created_at} (day boundaries, equal timestamps) that the write services,
 * driven by the clock, don't give. Every row still passes the tables' check constraints.
 */
@Component
class HistoryFixtures {

    record Customer(String subject, UUID userId, UUID walletId) {
    }

    private final UserProvisioningService users;
    private final WalletProvisioningService provisioning;
    private final WalletApi wallets;
    private final JdbcTemplate jdbc;

    HistoryFixtures(UserProvisioningService users, WalletProvisioningService provisioning, WalletApi wallets,
            JdbcTemplate jdbc) {
        this.users = users;
        this.provisioning = provisioning;
        this.wallets = wallets;
        this.jdbc = jdbc;
    }

    Customer customer() {
        String subject = ApiMockMvc.newSubject();
        UUID userId = users.ensureUser(new TokenIdentity(subject, null, "History Test User"));
        provisioning.apply(userId, KycLevel.MIN);
        return new Customer(subject, userId, wallets.findByUser(userId).orElseThrow().walletId());
    }

    /** A transfer row; COMPLETED/REVERSED get a journal entry, REJECTED a reviewer, as the constraints demand. */
    UUID transfer(UUID sender, UUID receiver, long minor, String status, String note, Instant createdAt) {
        UUID id = UUID.randomUUID();
        boolean posted = status.equals("COMPLETED") || status.equals("REVERSED");
        boolean reviewed = status.equals("REJECTED");
        jdbc.update("""
                insert into payments.transfers (id, sender_wallet_id, receiver_wallet_id, amount_minor, currency, note,
                    status, journal_entry_id, reviewed_by, reviewed_at, created_at, updated_at, version)
                values (?, ?, ?, ?, 'INR', ?, ?, ?, ?, ?, ?, ?, 0)""",
                id, sender, receiver, minor, note, status, posted ? UUID.randomUUID() : null,
                reviewed ? UUID.randomUUID() : null, reviewed ? Timestamp.from(createdAt) : null,
                Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }

    UUID completedTransfer(UUID sender, UUID receiver, long minor, Instant createdAt) {
        return transfer(sender, receiver, minor, "COMPLETED", "rent", createdAt);
    }

    /** A top-up row; the gateway IDs and journal entry follow the status rules of V13/V15. */
    UUID topup(UUID wallet, long minor, String status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        boolean hasOrder = !status.equals("CREATED") && !status.equals("FAILED");
        boolean settled = status.equals("CAPTURED") || status.equals("PARKED");
        jdbc.update("""
                insert into payments.topups (id, wallet_id, amount_minor, currency, gateway_order_id,
                    gateway_payment_id, status, journal_entry_id, created_at, updated_at, version)
                values (?, ?, ?, 'INR', ?, ?, ?, ?, ?, ?, 0)""",
                id, wallet, minor, hasOrder ? "order_" + id : null, settled ? "pay_" + id : null, status,
                settled ? UUID.randomUUID() : null, Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }

    UUID capturedTopup(UUID wallet, long minor, Instant createdAt) {
        return topup(wallet, minor, "CAPTURED", createdAt);
    }
}
