package com.trustledger.aml.cases.domain;

import java.util.Optional;
import java.util.UUID;

/** Port for {@code aml.cases}. */
public interface AmlCaseRepository {

    AmlCase save(AmlCase amlCase);

    /** The wallet's OPEN or INVESTIGATING case; there is at most one. */
    Optional<AmlCase> findActiveByWalletId(UUID walletId);
}
