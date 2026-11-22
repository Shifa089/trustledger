package com.trustledger.core.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.core.TestcontainersConfiguration;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V13: the database itself refuses an inconsistent top-up, even for raw SQL that bypasses the aggregate, and has
 * the index the monthly-load sum relies on.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TopupsMigrationIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-11-19T10:00:00Z"));

    @Autowired
    JdbcTemplate jdbc;

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private UUID insert(long amount, String currency, String status, String orderId, String paymentId,
            UUID journalEntryId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into payments.topups (id, wallet_id, amount_minor, currency, gateway_order_id,
                    gateway_payment_id, status, journal_entry_id, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
                id, UUID.randomUUID(), amount, currency, orderId, paymentId, status, journalEntryId, NOW, NOW);
        return id;
    }

    private UUID insert(String status, String orderId, String paymentId, UUID journalEntryId) {
        return insert(50_000, "INR", status, orderId, paymentId, journalEntryId);
    }

    private static void refusedBy(Runnable insert, String constraint) {
        assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    @Test
    void everyValidStateIsAccepted() {
        insert("CREATED", null, null, null);
        insert("PENDING", unique("order_"), null, null);
        insert("CAPTURED", unique("order_"), unique("pay_"), UUID.randomUUID());
        insert("FAILED", null, null, null);
        insert("FAILED", unique("order_"), null, null);
        insert("EXPIRED", unique("order_"), null, null);
        insert("PARKED", unique("order_"), unique("pay_"), UUID.randomUUID());
    }

    @Test
    void amountCurrencyAndStatusAreChecked() {
        refusedBy(() -> insert(0, "INR", "CREATED", null, null, null), "ck_topups_amount_positive");
        refusedBy(() -> insert(-1, "INR", "CREATED", null, null, null), "ck_topups_amount_positive");
        refusedBy(() -> insert(100, "USD", "CREATED", null, null, null), "ck_topups_currency");
        refusedBy(() -> insert("PAID", unique("order_"), null, null), "ck_topups_status");
    }

    @Test
    void orderIdExistsExactlyFromPendingOn() {
        refusedBy(() -> insert("CREATED", unique("order_"), null, null), "ck_topups_order_id");
        refusedBy(() -> insert("PENDING", null, null, null), "ck_topups_order_id");
        refusedBy(() -> insert("CAPTURED", null, unique("pay_"), UUID.randomUUID()), "ck_topups_order_id");
        refusedBy(() -> insert("EXPIRED", null, null, null), "ck_topups_order_id");
    }

    @Test
    void journalEntryAndPaymentIdExactlyWhenCaptured() {
        refusedBy(() -> insert("CAPTURED", unique("order_"), unique("pay_"), null), "ck_topups_journal_entry");
        refusedBy(() -> insert("CAPTURED", unique("order_"), null, UUID.randomUUID()), "ck_topups_payment_id");
        refusedBy(() -> insert("PENDING", unique("order_"), null, UUID.randomUUID()), "ck_topups_journal_entry");
        refusedBy(() -> insert("FAILED", unique("order_"), unique("pay_"), null), "ck_topups_payment_id");
    }

    @Test
    void gatewayIdsAndJournalEntryAreUnique() {
        String order = unique("order_");
        insert("PENDING", order, null, null);
        assertThatThrownBy(() -> insert("PENDING", order, null, null)).isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uk_topups_gateway_order");

        String payment = unique("pay_");
        UUID entry = UUID.randomUUID();
        insert("CAPTURED", unique("order_"), payment, entry);
        assertThatThrownBy(() -> insert("CAPTURED", unique("order_"), payment, UUID.randomUUID()))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uk_topups_gateway_payment");
        assertThatThrownBy(() -> insert("CAPTURED", unique("order_"), unique("pay_"), entry))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uk_topups_journal_entry");
    }

    // --- V15: PARKED and the reconciler index ---

    @Test
    void parkedIsAcceptedWithOrderPaymentAndJournalEntry() {
        insert("PARKED", unique("order_"), unique("pay_"), UUID.randomUUID());
    }

    @Test
    void parkedNeedsOrderPaymentAndJournalEntry() {
        refusedBy(() -> insert("PARKED", null, unique("pay_"), UUID.randomUUID()), "ck_topups_order_id");
        refusedBy(() -> insert("PARKED", unique("order_"), unique("pay_"), null), "ck_topups_journal_entry");
        refusedBy(() -> insert("PARKED", unique("order_"), null, UUID.randomUUID()), "ck_topups_payment_id");
    }

    @Test
    void onlyCapturedAndParkedCarryAPaymentOrAJournalEntry() {
        refusedBy(() -> insert("EXPIRED", unique("order_"), unique("pay_"), UUID.randomUUID()),
                "ck_topups_journal_entry");
        refusedBy(() -> insert("FAILED", unique("order_"), null, UUID.randomUUID()), "ck_topups_journal_entry");
    }

    @Test
    void pendingTopupsAreIndexedByAgeForTheReconciler() {
        String definition = jdbc.queryForObject("""
                select indexdef from pg_indexes where schemaname = 'payments' and indexname = 'ix_topups_pending_created'""",
                String.class);
        assertThat(definition).contains("(created_at)").contains("WHERE").contains("'PENDING'");
    }

    @Test
    void walletAndCreatedAtAreIndexedForTheMonthlySum() {
        String definition = jdbc.queryForObject("""
                select indexdef from pg_indexes where schemaname = 'payments' and indexname = 'ix_topups_wallet_created_id'""",
                String.class);
        assertThat(definition).contains("(wallet_id, created_at, id)"); // V26 replaced V13's index
    }
}
