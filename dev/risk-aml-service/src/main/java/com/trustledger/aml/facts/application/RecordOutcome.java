package com.trustledger.aml.facts.application;

/** What {@link TransactionFactRecorder} did with one event. */
public enum RecordOutcome {
    /** First delivery: the facts were stored. */
    RECORDED,
    /** Already processed (a redelivery): nothing stored. */
    DUPLICATE
}
