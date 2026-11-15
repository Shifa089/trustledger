package com.trustledger.core.ledger;

/** The side of a journal line. The amount is always positive; the direction carries the sign. */
public enum Direction {
    DEBIT,
    CREDIT;

    /** A reversal posts every line on the other side. */
    public Direction opposite() {
        return this == DEBIT ? CREDIT : DEBIT;
    }
}
