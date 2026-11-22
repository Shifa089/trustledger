package com.trustledger.core.wallet.application;

import com.trustledger.core.shared.Money;
import com.trustledger.core.shared.audit.AuditDetails;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The outcome of one reconciliation run (FR-L-4). Amounts are decimal strings, like every API amount.
 *
 * @param walletsChecked every wallet looked at
 * @param mismatches     wallets whose cached balance differs from the ledger ({@link Reason#BALANCE})
 * @param unlinked       wallets without a usable ledger account ({@link Reason#UNLINKED})
 * @param listed         the first {@code max-listed-mismatches} of both kinds, in wallet ID order
 * @param truncated      whether more were found than {@code listed} holds
 */
public record ReconciliationReport(long walletsChecked, long mismatches, long unlinked, List<Mismatch> listed,
        boolean truncated, Instant startedAt, Instant finishedAt) implements AuditDetails {

    public ReconciliationReport {
        listed = List.copyOf(listed);
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(finishedAt, "finishedAt");
    }

    /** The audit trail records the counts of a manual run (FR-A-1), not the listed wallets. */
    @Override
    public Map<String, Object> auditDetails() {
        return Map.of("walletsChecked", walletsChecked, "mismatches", mismatches, "unlinked", unlinked,
                "truncated", truncated);
    }

    public enum Reason {
        /** The wallet's {@code balance_minor} (or currency) differs from its ledger account's balance. */
        BALANCE,
        /** The wallet has no ledger account linked, or the linked account doesn't exist. */
        UNLINKED
    }

    /**
     * @param ledgerBalance {@code null} for {@link Reason#UNLINKED}; may be negative for a broken ledger
     */
    public record Mismatch(UUID walletId, Reason reason, String currency, String cachedBalance,
            String ledgerBalance) {

        static Mismatch balance(UUID walletId, Money cached, long ledgerMinor, String ledgerCurrency) {
            return new Mismatch(walletId, Reason.BALANCE, ledgerCurrency, cached.toDecimalString(),
                    decimal(ledgerMinor));
        }

        static Mismatch unlinked(UUID walletId, Money cached) {
            return new Mismatch(walletId, Reason.UNLINKED, cached.currency().getCurrencyCode(),
                    cached.toDecimalString(), null);
        }

        /** {@link Money} can't be negative, but a broken ledger balance can. */
        private static String decimal(long minor) {
            return minor < 0 ? "-" + Money.of(Math.negateExact(minor), Money.INR).toDecimalString()
                    : Money.of(minor, Money.INR).toDecimalString();
        }
    }
}
