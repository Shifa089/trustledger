package com.trustledger.core.payments.domain;

import java.util.EnumSet;
import java.util.Set;

/** Lifecycle of a {@link Topup} (SPEC §4 {@code payments.topups}). */
public enum TopupStatus {

    /** Saved and checked against the limits; the gateway order is not created yet. */
    CREATED,
    /** The gateway order exists; waiting for the customer to pay (webhook or reconciler). */
    PENDING,
    /** Paid and credited to the wallet (journal entry GATEWAY_CLEARING -> WALLET posted). */
    CAPTURED,
    /**
     * Paid, but the wallet could not take the money when it arrived (over a limit, frozen or closed). The money sits
     * in {@code PLATFORM:SUSPENSE} (journal entry GATEWAY_CLEARING -> SUSPENSE) until an operator settles it
     * (ADR 0006).
     */
    PARKED,
    /** The order could not be created, or the payment failed. */
    FAILED,
    /** Nobody paid in time (FR-P-6 reconciler). */
    EXPIRED;

    /** Top-ups that may still bring money in: they reserve room under the balance cap. */
    public static final Set<TopupStatus> OPEN = EnumSet.of(CREATED, PENDING);

    /** Top-ups that use up the monthly load limit: open ones and captured ones. */
    public static final Set<TopupStatus> COUNTED_FOR_LOAD = EnumSet.of(CREATED, PENDING, CAPTURED);

    /**
     * Top-ups a captured payment can still settle. Not only PENDING: in checkout the customer can retry on the same
     * order after a failed attempt, and a payment can be captured after the order expired here. Captured money must
     * never be dropped, so a late capture is credited (or parked) too.
     */
    public static final Set<TopupStatus> SETTLEABLE = EnumSet.of(PENDING, FAILED, EXPIRED);

    /** Top-ups whose payment was captured and booked: credited or parked. */
    public static final Set<TopupStatus> SETTLED = EnumSet.of(CAPTURED, PARKED);
}
