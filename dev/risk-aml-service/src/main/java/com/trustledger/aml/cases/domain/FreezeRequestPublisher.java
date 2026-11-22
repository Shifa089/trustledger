package com.trustledger.aml.cases.domain;

import com.trustledger.events.AccountFreezeRequestedV1;

/** Sends one freeze request to core-app (FR-AML-3). Port; the Kafka adapter lives in {@code cases.messaging}. */
public interface FreezeRequestPublisher {

    /**
     * Sends and waits for the broker's acknowledgement.
     *
     * @param correlationId may be {@code null}
     * @throws FreezeRequestPublishException if the broker did not acknowledge in time
     */
    void publish(AccountFreezeRequestedV1 event, String correlationId);

    /** The send failed or timed out; the request stays pending and the relay tries again. */
    class FreezeRequestPublishException extends RuntimeException {

        public FreezeRequestPublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
