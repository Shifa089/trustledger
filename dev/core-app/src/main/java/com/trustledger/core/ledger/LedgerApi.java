package com.trustledger.core.ledger;

import java.util.Currency;
import java.util.UUID;

/**
 * The ledger module's public API (FR-L-1 to FR-L-3). Payments posts top-ups and transfers through it; the
 * wallet module opens wallet accounts through it.
 * <p>
 * Every method joins the caller's transaction (or starts one), so a posting commits or rolls back together
 * with the wallet balance changes around it. Refusals throw
 * {@link com.trustledger.core.shared.TrustLedgerException}: {@code VALIDATION_FAILED} (fewer than 2 lines,
 * unbalanced, mixed currencies, a zero amount, a REVERSAL sent to {@link #post}), {@code NOT_FOUND} (unknown
 * account or entry), {@code DUPLICATE_POSTING} (the business event or the reversal was already posted) and
 * {@code INVALID_STATE_TRANSITION} (reversing a reversal).
 */
public interface LedgerApi {

    /**
     * Posts one balanced journal entry. A REVERSAL is refused here: use {@link #reverse}.
     *
     * @return the new entry's ID
     */
    UUID post(PostingCommand command);

    /**
     * FR-L-3: posts a REVERSAL of {@code entryId}: every debit of the original becomes a credit and vice versa.
     *
     * @param reason why, 1-500 characters; stored, never logged
     * @return the reversal entry's ID
     */
    UUID reverse(UUID entryId, String reason);

    /** The balance derived from the journal lines of the account with this code. */
    AccountBalance balanceOf(String accountCode);

    /**
     * Opens {@code WALLET:<walletId>} (LIABILITY, credit normal side) if it doesn't exist yet. Idempotent.
     *
     * @return the account's ID
     */
    UUID openWalletAccount(UUID walletId, Currency currency);
}
