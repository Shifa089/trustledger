package com.trustledger.core.privacy.domain;

/** PENDING until processed, then COMPLETED or FAILED, once. */
public enum DataRequestStatus {
    PENDING,
    COMPLETED,
    FAILED
}
