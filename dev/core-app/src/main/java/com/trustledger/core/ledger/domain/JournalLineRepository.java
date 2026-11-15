package com.trustledger.core.ledger.domain;

import com.trustledger.core.ledger.Direction;
import java.util.UUID;

/** Port for reading journal lines; implemented by Spring Data in {@code ledger.infrastructure}. */
public interface JournalLineRepository {

    /** Sum of the account's lines on one side, 0 if it has none. */
    long sumAmount(UUID accountId, Direction direction);
}
