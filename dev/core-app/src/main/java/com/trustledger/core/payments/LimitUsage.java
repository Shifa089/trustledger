package com.trustledger.core.payments;

import com.trustledger.core.shared.Money;
import java.util.Objects;

/**
 * How much of its tier's limits a wallet has used right now (SPEC §5 "limits used"), counted exactly as the limit
 * checks count it: outgoing transfers COMPLETED or HELD_FOR_REVIEW today and this month, and top-ups CREATED, PENDING
 * or CAPTURED this month, with day and month starting in {@code trustledger.payments.limit-zone} (Asia/Kolkata).
 */
public record LimitUsage(Money transferredToday, Money transferredThisMonth, Money addedThisMonth) {

    public LimitUsage {
        Objects.requireNonNull(transferredToday, "transferredToday");
        Objects.requireNonNull(transferredThisMonth, "transferredThisMonth");
        Objects.requireNonNull(addedThisMonth, "addedThisMonth");
    }
}
