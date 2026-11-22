package com.trustledger.aml.rules.domain;

import java.util.UUID;

/**
 * Port: serialises rule evaluation per wallet until the current transaction ends.
 * <p>
 * Without it, two transactions recording facts for the same wallet at the same time (two partitions, or the
 * transfer and top-up topics) could each count the window before the other commits, and neither would see the 6th
 * transfer. With it, the second one waits and, under READ COMMITTED, its next query sees the first one's facts.
 * Callers lock several wallets in ascending ID order, so two transactions can't deadlock.
 */
public interface WalletEvaluationLock {

    /** Blocks until this transaction holds the wallet's lock; released at commit or rollback. */
    void lock(UUID walletId);
}
