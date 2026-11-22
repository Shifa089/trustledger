package com.trustledger.aml.facts.domain;

import java.util.List;
import java.util.UUID;

/** Port for {@code aml.transaction_facts}. Append-only: there is no update and no delete. */
public interface TransactionFactRepository {

    TransactionFact save(TransactionFact fact);

    /**
     * Writes pending inserts now, so the rules' SQL (plain JDBC, which doesn't trigger Hibernate's auto-flush)
     * sees the facts saved earlier in this transaction.
     */
    void flush();

    List<TransactionFact> findByEventId(UUID eventId);

    List<TransactionFact> findByWalletIdOrderByOccurredAtAsc(UUID walletId);
}
