package com.trustledger.aml.rules.infrastructure;

import com.trustledger.aml.rules.domain.WalletEvaluationLock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * {@link WalletEvaluationLock} as a PostgreSQL transaction-level advisory lock. There is no wallet row in this
 * service to {@code SELECT ... FOR UPDATE}, and an advisory lock needs no table. It is released by commit or
 * rollback, so it can't leak. The 128-bit wallet ID is folded into the lock's 64-bit key; two wallets sharing a
 * key only wait for each other, which is safe.
 */
@Component
class PostgresWalletEvaluationLock implements WalletEvaluationLock {

    private final JdbcClient jdbc;

    PostgresWalletEvaluationLock(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lock(UUID walletId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
                .param("key", key(walletId))
                .query((rs, row) -> Boolean.TRUE)
                .list();
    }

    static long key(UUID walletId) {
        return walletId.getMostSignificantBits() ^ walletId.getLeastSignificantBits();
    }
}
