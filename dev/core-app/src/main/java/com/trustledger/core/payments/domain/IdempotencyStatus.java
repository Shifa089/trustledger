package com.trustledger.core.payments.domain;

/** State of an idempotency key: a request is running, or it finished and its response can be replayed. */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED
}
