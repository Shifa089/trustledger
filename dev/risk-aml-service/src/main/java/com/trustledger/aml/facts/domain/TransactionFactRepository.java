package com.trustledger.aml.facts.domain;

import java.util.List;
import java.util.UUID;

/** Port for {@code aml.transaction_facts}. Append-only: there is no update and no delete. */
public interface TransactionFactRepository {

    TransactionFact save(TransactionFact fact);

    List<TransactionFact> findByEventId(UUID eventId);

    List<TransactionFact> findByWalletIdOrderByOccurredAtAsc(UUID walletId);
}
