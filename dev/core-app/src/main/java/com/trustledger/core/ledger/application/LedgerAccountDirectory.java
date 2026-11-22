package com.trustledger.core.ledger.application;

import com.trustledger.core.ledger.domain.LedgerAccountRepository;
import com.trustledger.core.shared.cache.CacheNames;
import java.util.Objects;
import java.util.Optional;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Ledger account lookups by code, cached in Redis ({@link CacheNames#LEDGER_ACCOUNTS}, M11, ADR 0019). An account
 * row never changes after it is opened (the entity is {@code @Immutable} and there is no delete), so a cached entry
 * can't go stale; the TTL only bounds memory. An unknown code is not cached, so an account opened later is found.
 * <p>
 * Used only where no row locks are held (CLAUDE.md: no remote calls while holding locks). Posting, which runs under
 * the wallet locks, resolves its accounts from PostgreSQL in its own query ({@code LedgerPostingService}).
 * <p>
 * <b>Never cache a balance here or anywhere else</b>: {@link LedgerAccountService#balanceOf} always sums the journal
 * lines.
 */
@Service
public class LedgerAccountDirectory {

    private final LedgerAccountRepository accounts;

    public LedgerAccountDirectory(LedgerAccountRepository accounts) {
        this.accounts = accounts;
    }

    @Cacheable(cacheNames = CacheNames.LEDGER_ACCOUNTS, key = "#code", unless = "#result == null")
    public Optional<LedgerAccountRef> findByCode(String code) {
        Objects.requireNonNull(code, "code");
        return accounts.findByCode(code).map(LedgerAccountRef::of);
    }
}
