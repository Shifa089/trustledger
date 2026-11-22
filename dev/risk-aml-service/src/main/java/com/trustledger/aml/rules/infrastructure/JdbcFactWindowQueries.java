package com.trustledger.aml.rules.infrastructure;

import com.trustledger.aml.rules.domain.FactWindowQueries;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The rules' windows as SQL over {@code aml.transaction_facts}. Each query starts with {@code wallet_id = ? AND
 * occurred_at >= ?}, which the index {@code ix_transaction_facts_wallet_occurred} serves as one range scan.
 * Counting in the database keeps the work constant however many facts a wallet has. {@link JdbcClient} joins the
 * caller's transaction; the caller flushes JPA first so facts saved in this transaction are counted.
 */
@Repository
class JdbcFactWindowQueries implements FactWindowQueries {

    private final JdbcClient jdbc;

    JdbcFactWindowQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long countOutgoingTransfersSince(UUID walletId, Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM aml.transaction_facts
                        WHERE wallet_id = :walletId AND occurred_at >= :since
                          AND direction = 'OUT' AND source = 'TRANSFER'
                        """)
                .param("walletId", walletId)
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }

    @Override
    public long countInitiatedInAmountRangeSince(UUID walletId, long minAmountMinor, long maxAmountMinor,
            Instant since) {
        return jdbc.sql("""
                        SELECT count(*) FROM aml.transaction_facts
                        WHERE wallet_id = :walletId AND occurred_at >= :since
                          AND (direction = 'OUT' OR source = 'TOPUP')
                          AND amount_minor BETWEEN :minAmount AND :maxAmount
                        """)
                .param("walletId", walletId)
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .param("minAmount", minAmountMinor)
                .param("maxAmount", maxAmountMinor)
                .query(Long.class)
                .single();
    }

    @Override
    public long countDistinctRecipientsSince(UUID walletId, Instant since) {
        return jdbc.sql("""
                        SELECT count(DISTINCT counterparty_wallet_id) FROM aml.transaction_facts
                        WHERE wallet_id = :walletId AND occurred_at >= :since
                          AND direction = 'OUT' AND source = 'TRANSFER'
                        """)
                .param("walletId", walletId)
                .param("since", OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                .query(Long.class)
                .single();
    }
}
