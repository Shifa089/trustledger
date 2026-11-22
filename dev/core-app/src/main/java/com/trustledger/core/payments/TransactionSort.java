package com.trustledger.core.payments;

import java.util.Objects;

/**
 * The order of a history page. Only these fields can be sorted on (a whitelist by type); ties are always broken by
 * creation time, newest first, then the row ID, so paging is stable.
 */
public record TransactionSort(Field field, boolean ascending) {

    public enum Field {
        CREATED_AT,
        AMOUNT
    }

    /** The default: newest first. */
    public static final TransactionSort NEWEST_FIRST = new TransactionSort(Field.CREATED_AT, false);

    public TransactionSort {
        Objects.requireNonNull(field, "field");
    }
}
