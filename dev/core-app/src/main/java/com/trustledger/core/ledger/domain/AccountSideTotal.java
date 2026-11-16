package com.trustledger.core.ledger.domain;

import com.trustledger.core.ledger.Direction;
import java.util.Objects;
import java.util.UUID;

/** The sum of one account's journal lines on one side; a row of a grouped balance query. */
public record AccountSideTotal(UUID accountId, Direction direction, long totalMinor) {

    public AccountSideTotal {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(direction, "direction");
    }
}
