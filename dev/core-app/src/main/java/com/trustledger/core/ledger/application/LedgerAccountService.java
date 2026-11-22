package com.trustledger.core.ledger.application;

import com.trustledger.core.ledger.AccountBalance;
import com.trustledger.core.ledger.AccountCodes;
import com.trustledger.core.ledger.AccountType;
import com.trustledger.core.ledger.Direction;
import com.trustledger.core.ledger.domain.AccountSideTotal;
import com.trustledger.core.ledger.domain.JournalLineRepository;
import com.trustledger.core.ledger.domain.LedgerAccount;
import com.trustledger.core.ledger.domain.LedgerAccountRepository;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.util.Collection;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opens ledger accounts and derives their balances from the journal lines. */
@Service
public class LedgerAccountService {

    private static final Logger log = LoggerFactory.getLogger(LedgerAccountService.class);

    private final LedgerAccountRepository accounts;
    private final LedgerAccountDirectory directory;
    private final JournalLineRepository lines;
    private final Clock clock;

    public LedgerAccountService(LedgerAccountRepository accounts, LedgerAccountDirectory directory,
            JournalLineRepository lines, Clock clock) {
        this.directory = directory;
        this.accounts = accounts;
        this.lines = lines;
        this.clock = clock;
    }

    /**
     * Opens {@code WALLET:<walletId>} as a LIABILITY (credit normal side): the wallet balance is money the
     * platform owes its customer. Idempotent: an existing account is returned as it is. Joins the caller's
     * transaction, so the account and the wallet's link to it commit together.
     *
     * @throws org.springframework.dao.DataIntegrityViolationException if a concurrent call opened it first
     *         (the wallet module prevents that by locking the wallet row before calling)
     */
    @Transactional
    public UUID openWalletAccount(UUID walletId, Currency currency) {
        Objects.requireNonNull(currency, "currency");
        String code = AccountCodes.wallet(walletId);
        return accounts.findByCode(code).map(existing -> {
            if (!existing.getCurrency().equals(currency)) {
                throw new IllegalStateException("Account " + code + " exists in " + existing.getCurrency());
            }
            return existing.getId();
        }).orElseGet(() -> {
            LedgerAccount opened = accounts.saveAndFlush(
                    LedgerAccount.open(code, AccountType.LIABILITY, currency, clock.instant()));
            log.info("Ledger account {} opened for wallet {}", opened.getId(), walletId);
            return opened.getId();
        });
    }

    /**
     * The balance on the account's normal side, summed from its journal lines (never from a cached column).
     * <p>
     * <b>Never cached</b> (ADR 0019): only the account's identity comes from the {@link LedgerAccountDirectory}
     * cache; the sums are read from PostgreSQL on every call, so a posting is visible at once.
     *
     * @throws TrustLedgerException {@code NOT_FOUND} for an unknown code
     */
    @Transactional(readOnly = true)
    public AccountBalance balanceOf(String accountCode) {
        Objects.requireNonNull(accountCode, "accountCode");
        LedgerAccountRef account = directory.findByCode(accountCode)
                .orElseThrow(() -> new TrustLedgerException(ErrorCode.NOT_FOUND, "Ledger account not found."));
        long debits = lines.sumAmount(account.id(), Direction.DEBIT);
        long credits = lines.sumAmount(account.id(), Direction.CREDIT);
        return new AccountBalance(account.code(), account.normalSide(), account.balanceFrom(debits, credits),
                account.currency());
    }

    /**
     * {@link #balanceOf} for many accounts with two queries in total (accounts, then grouped line totals), for
     * reconciliation. Unknown codes are simply absent from the result. Never cached (ADR 0019): reconciliation must
     * compare against the ledger as it is now.
     */
    @Transactional(readOnly = true)
    public Map<String, AccountBalance> balancesOf(Collection<String> accountCodes) {
        Objects.requireNonNull(accountCodes, "accountCodes");
        if (accountCodes.isEmpty()) {
            return Map.of();
        }
        List<LedgerAccount> found = accounts.findAllByCodeIn(Set.copyOf(accountCodes));
        if (found.isEmpty()) {
            return Map.of();
        }
        Map<UUID, long[]> totals = new HashMap<>();
        for (AccountSideTotal total : lines.sumByAccountAndDirection(
                found.stream().map(LedgerAccount::getId).toList())) {
            long[] debitsAndCredits = totals.computeIfAbsent(total.accountId(), id -> new long[2]);
            debitsAndCredits[total.direction() == Direction.DEBIT ? 0 : 1] = total.totalMinor();
        }
        Map<String, AccountBalance> balances = new HashMap<>();
        for (LedgerAccount account : found) {
            long[] debitsAndCredits = totals.getOrDefault(account.getId(), new long[2]);
            balances.put(account.getCode(), new AccountBalance(account.getCode(), account.getNormalSide(),
                    account.balanceFrom(debitsAndCredits[0], debitsAndCredits[1]), account.getCurrency()));
        }
        return Map.copyOf(balances);
    }
}
