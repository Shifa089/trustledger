package com.trustledger.aml.facts.application;

import com.trustledger.aml.facts.domain.ProcessedEventRepository;
import com.trustledger.aml.facts.domain.TransactionFact;
import com.trustledger.aml.facts.domain.TransactionFactRepository;
import com.trustledger.aml.rules.application.RuleEngine;
import com.trustledger.events.TopupCapturedV1;
import com.trustledger.events.TransferCompletedV1;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns money-movement events into {@code transaction_facts} (FR-AML-1) and runs the AML rules on them (FR-AML-2),
 * exactly once per event.
 * <p>
 * Each event is handled in <b>one transaction</b>: claim its ID in {@code processed_events}, insert its facts, then
 * let the {@link RuleEngine} raise alerts. All of it commits together or not at all, so:
 * <ul>
 * <li>a redelivered event (retry, rebalance, crash before the Kafka offset commit) finds its ID, stores nothing and
 * raises no second alert;</li>
 * <li>a failure while storing facts or evaluating rules rolls back the claim too, so the retry starts clean.</li>
 * </ul>
 * Logs carry event and wallet IDs only; the contracts hold no personal data.
 */
@Service
public class TransactionFactRecorder {

    private static final Logger log = LoggerFactory.getLogger(TransactionFactRecorder.class);

    private final ProcessedEventRepository processedEvents;
    private final TransactionFactRepository facts;
    private final RuleEngine ruleEngine;
    private final Clock clock;

    public TransactionFactRecorder(ProcessedEventRepository processedEvents, TransactionFactRepository facts,
            RuleEngine ruleEngine, Clock clock) {
        this.processedEvents = processedEvents;
        this.facts = facts;
        this.ruleEngine = ruleEngine;
        this.clock = clock;
    }

    /** A transfer: an OUT fact for the sender and an IN fact for the receiver. */
    @Transactional
    public RecordOutcome record(TransferCompletedV1 event) {
        Instant now = clock.instant();
        if (!processedEvents.markProcessed(event.eventId(), TransferCompletedV1.TOPIC, now)) {
            return duplicate(event.eventId());
        }
        TransactionFact out = facts.save(TransactionFact.outgoingTransfer(event.eventId(), event.walletId(),
                event.counterpartyWalletId(), event.transferId(), event.amountMinor(), event.currency(),
                event.occurredAt(), now));
        TransactionFact in = facts.save(TransactionFact.incomingTransfer(event.eventId(),
                event.counterpartyWalletId(), event.walletId(), event.transferId(), event.amountMinor(),
                event.currency(), event.occurredAt(), now));
        log.info("Recorded transfer facts eventId={} sender={} receiver={}", event.eventId(), event.walletId(),
                event.counterpartyWalletId());
        evaluate(List.of(out, in));
        return RecordOutcome.RECORDED;
    }

    /** A captured top-up: one IN fact. */
    @Transactional
    public RecordOutcome record(TopupCapturedV1 event) {
        Instant now = clock.instant();
        if (!processedEvents.markProcessed(event.eventId(), TopupCapturedV1.TOPIC, now)) {
            return duplicate(event.eventId());
        }
        TransactionFact topup = facts.save(TransactionFact.topup(event.eventId(), event.walletId(), event.topupId(),
                event.amountMinor(), event.currency(), event.occurredAt(), now));
        log.info("Recorded top-up fact eventId={} wallet={}", event.eventId(), event.walletId());
        evaluate(List.of(topup));
        return RecordOutcome.RECORDED;
    }

    /** Flush first: the rules count with plain SQL, which must see the facts just saved. */
    private void evaluate(List<TransactionFact> newFacts) {
        facts.flush();
        ruleEngine.evaluate(newFacts);
    }

    private static RecordOutcome duplicate(UUID eventId) {
        log.info("Skipped already processed event eventId={}", eventId);
        return RecordOutcome.DUPLICATE;
    }
}
