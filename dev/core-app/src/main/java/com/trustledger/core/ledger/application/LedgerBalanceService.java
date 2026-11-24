package com.trustledger.core.ledger.application;

import com.trustledger.core.ledger.AccountBalance;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.time.Instant;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ledger-derived balance of one account for admins and auditors (SPEC §5
 * {@code GET /admin/ledger/accounts/{code}/balance}, FR-L-2). Always summed from the journal lines, never cached
 * (ADR 0019), so it is what reconciliation compares wallet balances against.
 * <p>
 * The code must have the shape the database allows ({@code ck_ledger_accounts_code}, at most 64 characters), so a
 * malformed code is a 400 and a well-formed unknown one a 404.
 */
@Service
public class LedgerBalanceService {

    private static final Pattern ACCOUNT_CODE = Pattern.compile("[A-Z_]+:[A-Za-z0-9_-]+");
    private static final int MAX_CODE_LENGTH = 64;

    private final LedgerAccountService accounts;
    private final Clock clock;

    public LedgerBalanceService(LedgerAccountService accounts, Clock clock) {
        this.accounts = accounts;
        this.clock = clock;
    }

    /**
     * @throws TrustLedgerException {@code VALIDATION_FAILED} for a malformed code, {@code NOT_FOUND} for an unknown one
     */
    @Transactional(readOnly = true)
    public LedgerBalanceView balanceOf(String accountCode) {
        if (accountCode == null || accountCode.length() > MAX_CODE_LENGTH
                || !ACCOUNT_CODE.matcher(accountCode).matches()) {
            throw new TrustLedgerException(ErrorCode.VALIDATION_FAILED,
                    "code must look like PLATFORM:SUSPENSE or WALLET:<id>, at most 64 characters.");
        }
        Instant asOf = clock.instant();
        return new LedgerBalanceView(accounts.balanceOf(accountCode), asOf);
    }

    /** The balance and the moment it was read. */
    public record LedgerBalanceView(AccountBalance balance, Instant asOf) {
    }
}
