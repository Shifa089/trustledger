package com.trustledger.core.payments.domain;

import java.util.EnumSet;
import java.util.Set;

/** Lifecycle of a {@link Topup} (SPEC §4 {@code payments.topups}). */
public enum TopupStatus {

    /** Saved and checked against the limits; the gateway order is not created yet. */
    CREATED,
    /** The gateway order exists; waiting for the customer to pay (webhook or reconciler). */
    PENDING,
    /** Paid and credited to the wallet (journal entry posted). */
    CAPTURED,
    /** The order could not be created, or the payment failed. */
    FAILED,
    /** Nobody paid in time (FR-P-6 reconciler). */
    EXPIRED;

    /** Top-ups that may still bring money in: they reserve room under the balance cap. */
    public static final Set<TopupStatus> OPEN = EnumSet.of(CREATED, PENDING);

    /** Top-ups that use up the monthly load limit: open ones and captured ones. */
    public static final Set<TopupStatus> COUNTED_FOR_LOAD = EnumSet.of(CREATED, PENDING, CAPTURED);
}
