package com.trustledger.core.ledger.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port for ledger accounts; implemented by Spring Data in {@code ledger.infrastructure}. */
public interface LedgerAccountRepository {

    Optional<LedgerAccount> findByCode(String code);

    /** Every account whose code is in {@code codes}, in one query; unknown codes are simply missing. */
    List<LedgerAccount> findAllByCodeIn(Collection<String> codes);

    List<LedgerAccount> findAllByIdIn(Collection<UUID> ids);

    /** Flushes at once, so the unique {@code code} surfaces here and not at commit. */
    LedgerAccount saveAndFlush(LedgerAccount account);
}
