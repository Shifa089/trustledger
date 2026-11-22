package com.trustledger.aml.cases.domain;

import com.trustledger.events.AmlCaseStatusChangedV1;

/** Sends one case event to core-app. Port; the Kafka adapter lives in {@code cases.messaging}. */
public interface CaseEventPublisher {

    /**
     * Sends and waits for the broker's acknowledgement.
     *
     * @param correlationId may be {@code null}
     * @throws RuntimeException if the broker did not acknowledge in time; the event stays pending
     */
    void publish(AmlCaseStatusChangedV1 event, String correlationId);
}
