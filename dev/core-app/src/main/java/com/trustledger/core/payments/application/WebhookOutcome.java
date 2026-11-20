package com.trustledger.core.payments.application;

/** What happened to an accepted webhook event. Every outcome answers the gateway 200. */
public enum WebhookOutcome {
    /** A handler processed it now. */
    PROCESSED,
    /** No handler for its type: stored and marked processed, nothing else done. */
    IGNORED,
    /** Already processed by an earlier delivery: nothing done. */
    DUPLICATE
}
