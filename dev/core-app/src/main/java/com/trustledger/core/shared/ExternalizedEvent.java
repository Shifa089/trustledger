package com.trustledger.core.shared;

/**
 * An internal domain event that also leaves core-app through Kafka (M8, ADR 0005).
 * <p>
 * The event type itself carries {@code @Externalized("<topic>::#{#this.<walletId>()}")} (topic and message key).
 * This interface gives the one shared externalization configuration ({@code shared.events}) what it needs without
 * knowing the business modules: the contract record that goes on the wire and the metadata for the headers. The
 * internal event is never sent itself: it may hold data the contract leaves out (a user ID, a journal entry).
 */
public interface ExternalizedEvent {

    /** The versioned contract from {@code event-contracts}; pure, so a resubmitted event maps to the same record. */
    Record toV1();

    /** Event ID and correlation ID, fixed at publish time; they become Kafka headers. */
    EventMetadata metadata();
}
