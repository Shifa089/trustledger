package com.trustledger.aml.facts.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;

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

    /** The wallet's facts in {@code [from, to]}, oldest first, at most {@code limit} (case detail, STR report). */
    List<TransactionFact> findByWalletIdAndOccurredAtBetweenOrderByOccurredAtAscIdAsc(UUID walletId, Instant from,
            Instant to, Limit limit);
}
