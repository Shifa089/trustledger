package com.trustledger.aml.rules.application;

import com.trustledger.aml.cases.domain.Alert;
import com.trustledger.aml.cases.domain.AlertRepository;
import com.trustledger.aml.cases.domain.AmlCase;
import com.trustledger.aml.cases.domain.AmlCaseRepository;
import com.trustledger.aml.cases.domain.CaseEvent;
import com.trustledger.aml.cases.domain.CaseEventRepository;
import com.trustledger.aml.cases.domain.FreezeRequest;
import com.trustledger.aml.cases.domain.FreezeRequestRepository;
import com.trustledger.aml.facts.domain.TransactionFact;
import com.trustledger.aml.rules.domain.AlertDetails;
import com.trustledger.aml.rules.domain.AmlRule;
import com.trustledger.aml.rules.domain.Severity;
import com.trustledger.aml.rules.domain.WalletEvaluationLock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * FR-AML-2: runs every {@link AmlRule} on each new fact, turns each hit into an alert and attaches it to the
 * wallet's single active case, opening one if there is none.
 * <p>
 * Runs <b>inside the transaction that recorded the facts</b> ({@code MANDATORY}): facts, alerts and the case commit
 * together, a redelivered event (already in {@code processed_events}) is never evaluated twice, and a failure here
 * rolls the facts back too, so the Kafka retry evaluates them again.
 * <p>
 * Per wallet, evaluation is serialised with {@link WalletEvaluationLock}, taken in ascending wallet ID order (a
 * transfer touches two wallets). That also makes "find the active case, or open one" safe without retries; the
 * partial unique index on {@code aml.cases} stays as the backstop.
 * <p>
 * FR-AML-3: a HIGH alert also writes a {@link FreezeRequest} in this same transaction (the outbox, ADR 0007). It is
 * <b>not</b> sent from here: that would be a Kafka call while the wallet lock is held, and a send whose transaction
 * might still roll back. {@code FreezeRequestRelay} sends it after the commit.
 */
@Service
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);
    /** Set by the Kafka listener for the record being processed (already checked to be a plain token). */
    static final String CORRELATION_ID = "correlationId";

    private final List<AmlRule> rules;
    private final WalletEvaluationLock lock;
    private final AmlCaseRepository cases;
    private final AlertRepository alerts;
    private final FreezeRequestRepository freezeRequests;
    private final CaseEventRepository caseEvents;
    private final Clock clock;
    private final AmlAlertMetrics metrics;

    public RuleEngine(List<AmlRule> rules, WalletEvaluationLock lock, AmlCaseRepository cases,
            AlertRepository alerts, FreezeRequestRepository freezeRequests, CaseEventRepository caseEvents,
            Clock clock, AmlAlertMetrics metrics) {
        this.rules = List.copyOf(rules);
        this.lock = lock;
        this.cases = cases;
        this.alerts = alerts;
        this.freezeRequests = freezeRequests;
        this.caseEvents = caseEvents;
        this.clock = clock;
        this.metrics = metrics;
    }

    /**
     * @param newFacts the facts just recorded in this transaction (flushed, so the rules' queries count them)
     * @return the alerts raised, in wallet order
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RaisedAlert> evaluate(List<TransactionFact> newFacts) {
        List<TransactionFact> byWallet = newFacts.stream()
                .sorted(Comparator.comparing(TransactionFact::getWalletId))
                .toList();
        List<RaisedAlert> raised = new ArrayList<>();
        for (TransactionFact fact : byWallet) {
            UUID walletId = fact.getWalletId();
            lock.lock(walletId);
            for (AmlRule rule : rules) {
                rule.evaluate(walletId, fact)
                        .flatMap(details -> raise(rule, walletId, details))
                        .ifPresent(raised::add);
            }
        }
        return raised;
    }

    private Optional<RaisedAlert> raise(AmlRule rule, UUID walletId, AlertDetails details) {
        Instant now = clock.instant();
        if (alreadyAlerted(rule, walletId, now)) {
            log.debug("Rule {} hit again within its window; no new alert wallet={}", rule.code(), walletId);
            return Optional.empty();
        }
        AmlCase amlCase = cases.findActiveByWalletId(walletId)
                .orElseGet(() -> openCase(walletId, now));
        Alert alert = alerts.save(Alert.raise(amlCase, rule.code(), rule.severity(), details.description(), now));
        amlCase.alertRaised(now);
        metrics.alertRaised(rule.code(), rule.severity().name());
        log.info("AML alert raised rule={} severity={} wallet={} case={}", rule.code(), rule.severity(), walletId,
                amlCase.getId());
        if (alert.getSeverity() == Severity.HIGH) {
            FreezeRequest request = freezeRequests.save(FreezeRequest.forAlert(alert, MDC.get(CORRELATION_ID), now));
            log.info("Freeze requested wallet={} case={} request={}", walletId, amlCase.getId(), request.getId());
        }
        return Optional.of(RaisedAlert.of(alert));
    }

    /** A new case, and its "opened" event in the outbox (core-app blocks erasure while it is open, FR-PR-2). */
    private AmlCase openCase(UUID walletId, Instant now) {
        AmlCase opened = cases.save(AmlCase.open(walletId, now));
        caseEvents.save(CaseEvent.of(opened, MDC.get(CORRELATION_ID), now));
        return opened;
    }

    private boolean alreadyAlerted(AmlRule rule, UUID walletId, Instant now) {
        Optional<Duration> window = rule.window();
        return window.isPresent() && alerts.existsByWalletIdAndRuleCodeAndCreatedAtGreaterThanEqual(walletId,
                rule.code(), now.minus(window.get()));
    }
}
