package com.trustledger.aml.cases.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link FreezeRequestRelay} every {@code trustledger.aml.freeze-relay.interval}, measured from the end of the
 * previous run. No ShedLock: the relay's {@code SKIP LOCKED} claim already lets several instances run it at once.
 * Off with {@code trustledger.aml.scheduling.enabled=false} (tests that drive the relay themselves).
 */
@Component
@ConditionalOnBooleanProperty(name = "trustledger.aml.scheduling.enabled", matchIfMissing = true)
class FreezeRequestRelayJob {

    private final FreezeRequestRelay relay;

    FreezeRequestRelayJob(FreezeRequestRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${trustledger.aml.freeze-relay.interval}")
    void run() {
        relay.relayPending();
    }
}
