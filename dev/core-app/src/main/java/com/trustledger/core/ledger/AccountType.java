package com.trustledger.core.ledger;

/** The kind of ledger account, which fixes the side on which its balance grows. */
public enum AccountType {
    /** What the platform owns or is owed, e.g. money captured by the gateway. Grows with debits. */
    ASSET(Direction.DEBIT),
    /** What the platform owes, e.g. a customer's wallet balance. Grows with credits. */
    LIABILITY(Direction.CREDIT),
    /** Fees the platform earns. Grows with credits. */
    REVENUE(Direction.CREDIT);

    private final Direction normalSide;

    AccountType(Direction normalSide) {
        this.normalSide = normalSide;
    }

    public Direction normalSide() {
        return normalSide;
    }
}
