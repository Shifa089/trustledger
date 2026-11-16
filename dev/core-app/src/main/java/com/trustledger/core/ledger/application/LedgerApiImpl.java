package com.trustledger.core.ledger.application;

import com.trustledger.core.ledger.AccountBalance;
import com.trustledger.core.ledger.LedgerApi;
import com.trustledger.core.ledger.PostingCommand;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.util.Collection;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** {@link LedgerApi} for other modules; the services it delegates to own the transactions. */
@Service
class LedgerApiImpl implements LedgerApi {

    private final LedgerPostingService posting;
    private final LedgerReversalService reversals;
    private final LedgerAccountService accounts;

    LedgerApiImpl(LedgerPostingService posting, LedgerReversalService reversals, LedgerAccountService accounts) {
        this.posting = posting;
        this.reversals = reversals;
        this.accounts = accounts;
    }

    /** A REVERSAL must come through {@link #reverse}, which builds its lines from the original entry. */
    @Override
    public UUID post(PostingCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.isReversal()) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED, "A reversal is posted with reverse().");
        }
        return posting.post(command);
    }

    @Override
    public UUID reverse(UUID entryId, String reason) {
        return reversals.reverse(entryId, reason);
    }

    @Override
    public AccountBalance balanceOf(String accountCode) {
        return accounts.balanceOf(accountCode);
    }

    @Override
    public Map<String, AccountBalance> balancesOf(Collection<String> accountCodes) {
        return accounts.balancesOf(accountCodes);
    }

    @Override
    public UUID openWalletAccount(UUID walletId, Currency currency) {
        return accounts.openWalletAccount(walletId, currency);
    }
}
