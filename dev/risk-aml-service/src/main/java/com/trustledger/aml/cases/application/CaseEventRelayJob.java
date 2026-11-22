package com.trustledger.aml.cases.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link CaseEventRelay} on the freeze relay's interval, measured from the end of the previous run. Off with
 * {@code trustledger.aml.scheduling.enabled=false} (tests that drive the relay themselves).
 */
@Component
@ConditionalOnBooleanProperty(name = "trustledger.aml.scheduling.enabled", matchIfMissing = true)
class CaseEventRelayJob {

    private final CaseEventRelay relay;

    CaseEventRelayJob(CaseEventRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${trustledger.aml.freeze-relay.interval}")
    void run() {
        relay.relayPending();
    }
}
