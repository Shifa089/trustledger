package com.trustledger.core.wallet;

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
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V5/V6 migrations: the database itself refuses a negative balance, a hold above the balance, a second wallet
 * per user and an inconsistent freeze history, even for raw SQL that bypasses the Wallet aggregate.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WalletMigrationIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-11-15T10:00:00Z"));

    @Autowired
    JdbcTemplate jdbc;

    private UUID insertWallet(UUID userId, String tier, String status, long balance, long held, String currency) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into wallet.wallets (id, user_id, tier, status, balance_minor, held_minor, currency,
                    created_at, updated_at, version) values (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
                id, userId, tier, status, balance, held, currency, NOW, NOW);
        return id;
    }

    private UUID insertWallet(long balance, long held) {
        return insertWallet(UUID.randomUUID(), "MIN", "ACTIVE", balance, held, "INR");
    }

    private UUID insertFreeze(UUID walletId, String source, String reason, UUID frozenBy, UUID unfrozenBy,
            Timestamp unfrozenAt, String unfreezeReason) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into wallet.wallet_freezes (id, wallet_id, source, reason, frozen_by, frozen_at,
                    unfrozen_by, unfrozen_at, unfreeze_reason, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, walletId, source, reason, frozenBy, NOW, unfrozenBy, unfrozenAt, unfreezeReason, NOW);
        return id;
    }

    private static void refusedBy(Runnable insert, String constraint) {
        assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    @Test
    void validWalletIsAccepted() {
        UUID id = insertWallet(10_000, 10_000);

        assertThat(jdbc.queryForObject("select held_minor from wallet.wallets where id = ?", Long.class, id))
                .isEqualTo(10_000L);
    }

    @Test
    void negativeBalanceIsRefused() {
        refusedBy(() -> insertWallet(-1, 0), "ck_wallets_balance_non_negative");
    }

    @Test
    void negativeHoldIsRefused() {
        refusedBy(() -> insertWallet(0, -1), "ck_wallets_held_non_negative");
    }

    @Test
    void holdAboveBalanceIsRefused() {
        refusedBy(() -> insertWallet(100, 101), "ck_wallets_held_within_balance");
    }

    @Test
    void laterUpdateCannotOverdrawEither() {
        UUID id = insertWallet(100, 50);

        refusedBy(() -> jdbc.update("update wallet.wallets set balance_minor = 49 where id = ?", id),
                "ck_wallets_held_within_balance");
    }

    @Test
    void secondWalletForTheSameUserIsRefused() {
        UUID user = UUID.randomUUID();
        insertWallet(user, "MIN", "ACTIVE", 0, 0, "INR");

        refusedBy(() -> insertWallet(user, "FULL", "ACTIVE", 0, 0, "INR"), "uk_wallets_user");
    }

    @Test
    void unknownTierStatusOrCurrencyIsRefused() {
        refusedBy(() -> insertWallet(UUID.randomUUID(), "GOLD", "ACTIVE", 0, 0, "INR"), "ck_wallets_tier");
        refusedBy(() -> insertWallet(UUID.randomUUID(), "MIN", "LOCKED", 0, 0, "INR"), "ck_wallets_status");
        refusedBy(() -> insertWallet(UUID.randomUUID(), "MIN", "ACTIVE", 0, 0, "USD"), "ck_wallets_currency");
    }

    @Test
    void freezeOfAnUnknownWalletIsRefused() {
        refusedBy(() -> insertFreeze(UUID.randomUUID(), "AML", "R2", null, null, null, null),
                "fk_wallet_freezes_wallet");
    }

    @Test
    void secondOpenFreezeIsRefusedButHistoryIsFine() {
        UUID wallet = insertWallet(0, 0);
        insertFreeze(wallet, "AML", "first", null, UUID.randomUUID(), NOW, "cleared");
        insertFreeze(wallet, "AML", "second", null, null, null, null);

        refusedBy(() -> insertFreeze(wallet, "COMPLIANCE", "third", UUID.randomUUID(), null, null, null),
                "uk_wallet_freezes_one_open");
    }

    @Test
    void complianceFreezeNeedsTheOfficer() {
        UUID wallet = insertWallet(0, 0);

        refusedBy(() -> insertFreeze(wallet, "COMPLIANCE", "reason", null, null, null, null),
                "ck_wallet_freezes_officer");
    }

    @Test
    void blankReasonOrUnknownSourceIsRefused() {
        UUID wallet = insertWallet(0, 0);

        refusedBy(() -> insertFreeze(wallet, "AML", "   ", null, null, null, null), "ck_wallet_freezes_reason");
        refusedBy(() -> insertFreeze(wallet, "BANK", "x", null, null, null, null), "ck_wallet_freezes_source");
    }

    @Test
    void unfreezeIsAllOrNothing() {
        UUID wallet = insertWallet(0, 0);

        refusedBy(() -> insertFreeze(wallet, "AML", "x", null, UUID.randomUUID(), NOW, null),
                "ck_wallet_freezes_unfreeze_complete");
        refusedBy(() -> insertFreeze(wallet, "AML", "x", null, null, NOW, "why"),
                "ck_wallet_freezes_unfreeze_complete");
        refusedBy(() -> insertFreeze(wallet, "AML", "x", null, UUID.randomUUID(), NOW, " "),
                "ck_wallet_freezes_unfreeze_reason");
    }

    @Test
    void unfreezeBeforeFreezeIsRefused() {
        UUID wallet = insertWallet(0, 0);
        Timestamp before = Timestamp.from(NOW.toInstant().minusSeconds(1));

        refusedBy(() -> insertFreeze(wallet, "AML", "x", null, UUID.randomUUID(), before, "why"),
                "ck_wallet_freezes_unfreeze_after_freeze");
    }
}
