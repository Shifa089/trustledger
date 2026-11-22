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
 * V10/V11: the database itself refuses an inconsistent idempotency key or transfer, even for raw SQL that
 * bypasses the services, and has the indexes the limit checks and the cleanup job rely on.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PaymentsMigrationIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-11-19T10:00:00Z"));
    private static final Timestamp LATER = Timestamp.from(Instant.parse("2026-11-20T10:00:00Z"));
    private static final String HASH = "0123456789abcdef".repeat(4);

    @Autowired
    JdbcTemplate jdbc;

    private void insertKey(UUID userId, UUID key, String hash, String status, Integer code, String body,
            Timestamp expiresAt) {
        jdbc.update("""
                insert into payments.idempotency_keys (user_id, idem_key, request_hash, status, response_status,
                    response_body, created_at, expires_at) values (?, ?, ?, ?, ?, cast(? as jsonb), ?, ?)""",
                userId, key, hash, status, code, body, NOW, expiresAt);
    }

    private void insertKey(String hash, String status, Integer code, String body) {
        insertKey(UUID.randomUUID(), UUID.randomUUID(), hash, status, code, body, LATER);
    }

    private UUID insertTransfer(UUID sender, UUID receiver, long amount, String currency, String status,
            UUID journalEntryId, UUID reviewedBy, Timestamp reviewedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into payments.transfers (id, sender_wallet_id, receiver_wallet_id, amount_minor, currency,
                    status, journal_entry_id, reviewed_by, reviewed_at, created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
                id, sender, receiver, amount, currency, status, journalEntryId, reviewedBy, reviewedAt, NOW, NOW);
        return id;
    }

    private UUID insertTransfer(long amount, String status, UUID journalEntryId) {
        return insertTransfer(UUID.randomUUID(), UUID.randomUUID(), amount, "INR", status, journalEntryId, null, null);
    }

    private static void refusedBy(Runnable insert, String constraint) {
        assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    // --- payments.idempotency_keys ---

    @Test
    void validKeysAreAccepted() {
        insertKey(HASH, "IN_PROGRESS", null, null);
        insertKey(HASH, "COMPLETED", 201, "{\"id\":1}");
        insertKey(HASH, "COMPLETED", 204, null);
    }

    @Test
    void primaryKeyIsUserAndKey() {
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        insertKey(user, key, HASH, "IN_PROGRESS", null, null, LATER);

        assertThatThrownBy(() -> insertKey(user, key, HASH, "IN_PROGRESS", null, null, LATER))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("pk_idempotency_keys");
        // The same key for another user is a different key.
        insertKey(UUID.randomUUID(), key, HASH, "IN_PROGRESS", null, null, LATER);
    }

    @Test
    void keyChecksAreEnforced() {
        refusedBy(() -> insertKey(HASH, "DONE", null, null), "ck_idempotency_keys_status");
        refusedBy(() -> insertKey("ABC", "IN_PROGRESS", null, null), "ck_idempotency_keys_hash");
        refusedBy(() -> insertKey(HASH.toUpperCase(), "IN_PROGRESS", null, null), "ck_idempotency_keys_hash");
        refusedBy(() -> insertKey(HASH, "COMPLETED", null, "{}"), "ck_idempotency_keys_response");
        refusedBy(() -> insertKey(HASH, "COMPLETED", 422, "{}"), "ck_idempotency_keys_response");
        refusedBy(() -> insertKey(HASH, "IN_PROGRESS", 201, null), "ck_idempotency_keys_response");
        refusedBy(() -> insertKey(HASH, "IN_PROGRESS", null, "{}"), "ck_idempotency_keys_response");
        refusedBy(() -> insertKey(UUID.randomUUID(), UUID.randomUUID(), HASH, "IN_PROGRESS", null, null, NOW),
                "ck_idempotency_keys_expiry");
    }

    // --- payments.transfers ---

    @Test
    void validTransfersAreAccepted() {
        UUID officer = UUID.randomUUID();
        insertTransfer(100, "INITIATED", null);
        insertTransfer(100, "HELD_FOR_REVIEW", null);
        insertTransfer(100, "COMPLETED", UUID.randomUUID());
        insertTransfer(100, "FAILED", null);
        insertTransfer(UUID.randomUUID(), UUID.randomUUID(), 100, "INR", "REJECTED", null, officer, NOW);
        insertTransfer(UUID.randomUUID(), UUID.randomUUID(), 100, "INR", "COMPLETED", UUID.randomUUID(), officer, NOW);
    }

    @Test
    void transferChecksAreEnforced() {
        UUID wallet = UUID.randomUUID();
        refusedBy(() -> insertTransfer(0, "INITIATED", null), "ck_transfers_amount_positive");
        refusedBy(() -> insertTransfer(wallet, wallet, 100, "INR", "INITIATED", null, null, null),
                "ck_transfers_not_self");
        refusedBy(() -> insertTransfer(UUID.randomUUID(), UUID.randomUUID(), 100, "USD", "INITIATED", null, null,
                null), "ck_transfers_currency");
        refusedBy(() -> insertTransfer(100, "PENDING", null), "ck_transfers_status");
        refusedBy(() -> insertTransfer(100, "COMPLETED", null), "ck_transfers_journal_entry");
        refusedBy(() -> insertTransfer(100, "HELD_FOR_REVIEW", UUID.randomUUID()), "ck_transfers_journal_entry");
        refusedBy(() -> insertTransfer(UUID.randomUUID(), UUID.randomUUID(), 100, "INR", "REJECTED", null, null,
                null), "ck_transfers_rejected_reviewed");
        refusedBy(() -> insertTransfer(UUID.randomUUID(), UUID.randomUUID(), 100, "INR", "REJECTED", null,
                UUID.randomUUID(), null), "ck_transfers_review_pair");
    }

    @Test
    void aJournalEntryBelongsToOneTransfer() {
        UUID entry = UUID.randomUUID();
        insertTransfer(100, "COMPLETED", entry);

        assertThatThrownBy(() -> insertTransfer(100, "COMPLETED", entry))
                .isInstanceOf(DuplicateKeyException.class).hasMessageContaining("uk_transfers_journal_entry");
    }

    @Test
    void indexesForLimitsAndCleanupExist() {
        assertThat(jdbc.queryForObject(
                "select indexdef from pg_indexes where schemaname = 'payments' and indexname = ?", String.class,
                "ix_transfers_sender_created_id")).contains("(sender_wallet_id, created_at, id)"); // V26
        assertThat(jdbc.queryForObject(
                "select indexdef from pg_indexes where schemaname = 'payments' and indexname = ?", String.class,
                "ix_idempotency_keys_expires_at")).contains("(expires_at)");
    }
}
