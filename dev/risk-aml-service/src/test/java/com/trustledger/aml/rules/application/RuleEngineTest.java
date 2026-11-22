package com.trustledger.aml.rules.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.aml.cases.domain.Alert;
import com.trustledger.aml.cases.domain.AlertRepository;
import com.trustledger.aml.cases.domain.AmlCase;
import com.trustledger.aml.cases.domain.CaseEvent;
import com.trustledger.aml.cases.domain.CaseEventRepository;
import com.trustledger.aml.cases.domain.AmlCaseRepository;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.slf4j.MDC;
import org.springframework.test.util.ReflectionTestUtils;

class RuleEngineTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final UUID LOW_WALLET = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID HIGH_WALLET = UUID.fromString("00000000-0000-7000-8000-000000000002");

    private final WalletEvaluationLock lock = mock(WalletEvaluationLock.class);
    private final AmlCaseRepository cases = mock(AmlCaseRepository.class);
    private final AlertRepository alerts = mock(AlertRepository.class);
    private final FreezeRequestRepository freezeRequests = mock(FreezeRequestRepository.class);
    private final CaseEventRepository caseEvents = mock(CaseEventRepository.class);
    private final List<CaseEvent> savedCaseEvents = new ArrayList<>();
    private final List<AmlCase> savedCases = new ArrayList<>();
    private final List<Alert> savedAlerts = new ArrayList<>();
    private final List<FreezeRequest> savedFreezeRequests = new ArrayList<>();

    @BeforeEach
    void persistLikeJpa() {
        when(cases.save(any())).thenAnswer(call -> {
            AmlCase amlCase = call.getArgument(0);
            ReflectionTestUtils.setField(amlCase, "id", UUID.randomUUID());
            savedCases.add(amlCase);
            return amlCase;
        });
        when(cases.findActiveByWalletId(any())).thenAnswer(call -> savedCases.stream()
                .filter(c -> c.getWalletId().equals(call.getArgument(0)))
                .findFirst());
        when(alerts.save(any())).thenAnswer(call -> {
            Alert alert = call.getArgument(0);
            ReflectionTestUtils.setField(alert, "id", UUID.randomUUID());
            savedAlerts.add(alert);
            return alert;
        });
        when(freezeRequests.save(any())).thenAnswer(call -> {
            FreezeRequest request = call.getArgument(0);
            ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
            savedFreezeRequests.add(request);
            return request;
        });
        when(caseEvents.save(any())).thenAnswer(call -> {
            CaseEvent event = call.getArgument(0);
            savedCaseEvents.add(event);
            return event;
        });
    }

    // --- FR-AML-3: a HIGH alert writes a freeze request (the outbox), a MEDIUM one doesn't ---------------------

    @Test
    void aHighAlertWritesAFreezeRequestForItsWalletAndCase() {
        RuleEngine engine = engine(rule("R2", Severity.HIGH, fact -> true));

        try (MDC.MDCCloseable ignored = MDC.putCloseable("correlationId", "corr-123")) {
            engine.evaluate(List.of(outgoing(LOW_WALLET)));
        }

        Alert alert = savedAlerts.getFirst();
        assertThat(savedFreezeRequests).singleElement().satisfies(request -> {
            assertThat(request.getAlertId()).isEqualTo(alert.getId());
            assertThat(request.getCaseId()).isEqualTo(alert.getCaseId());
            assertThat(request.getWalletId()).isEqualTo(LOW_WALLET);
            assertThat(request.getRuleCode()).isEqualTo("R2");
            assertThat(request.getCorrelationId()).isEqualTo("corr-123");
            assertThat(request.getRequestedAt()).isEqualTo(NOW);
            assertThat(request.getPublishedAt()).isNull();
        });
    }

    @Test
    void aMediumAlertRequestsNoFreeze() {
        engine(rule("R3", Severity.MEDIUM, fact -> true)).evaluate(List.of(outgoing(LOW_WALLET)));

        assertThat(savedAlerts).hasSize(1);
        verify(freezeRequests, never()).save(any());
    }

    @Test
    void aSuppressedHighHitRequestsNoSecondFreeze() {
        when(alerts.existsByWalletIdAndRuleCodeAndCreatedAtGreaterThanEqual(eq(LOW_WALLET), eq("R4"), any()))
                .thenReturn(true);

        engine(rule("R4", Severity.HIGH, fact -> true, Duration.ofHours(24))).evaluate(List.of(outgoing(LOW_WALLET)));

        verify(freezeRequests, never()).save(any());
    }

    @Test
    void noHitTouchesNoCase() {
        RuleEngine engine = engine(rule("R1", Severity.MEDIUM, fact -> false));

        assertThat(engine.evaluate(List.of(outgoing(LOW_WALLET)))).isEmpty();

        verify(cases, never()).save(any());
        verify(alerts, never()).save(any());
    }

    @Test
    void theFirstHitOpensACaseForTheWallet() {
        RuleEngine engine = engine(rule("R2", Severity.HIGH, fact -> true));

        List<RaisedAlert> raised = engine.evaluate(List.of(outgoing(LOW_WALLET)));

        assertThat(savedCases).singleElement().satisfies(c -> assertThat(c.getWalletId()).isEqualTo(LOW_WALLET));
        assertThat(raised).singleElement().satisfies(alert -> {
            assertThat(alert.caseId()).isEqualTo(savedCases.getFirst().getId());
            assertThat(alert.walletId()).isEqualTo(LOW_WALLET);
            assertThat(alert.ruleCode()).isEqualTo("R2");
            assertThat(alert.severity()).isEqualTo(Severity.HIGH);
        });
        assertThat(savedAlerts).singleElement().satisfies(alert -> {
            assertThat(alert.getDetails()).isEqualTo("R2 hit");
            assertThat(alert.getCreatedAt()).isEqualTo(NOW);
        });
        assertThat(savedCaseEvents).as("the outbox learns the case opened").singleElement().satisfies(event -> {
            assertThat(event.getCaseId()).isEqualTo(savedCases.getFirst().getId());
            assertThat(event.getWalletId()).isEqualTo(LOW_WALLET);
            assertThat(event.getStatus()).isEqualTo("OPEN");
            assertThat(event.getOccurredAt()).isEqualTo(NOW);
        });
    }

    @Test
    void aLaterHitJoinsTheOpenCase() {
        AmlCase open = AmlCase.open(LOW_WALLET, NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(open, "id", UUID.randomUUID());
        savedCases.add(open);
        RuleEngine engine = engine(rule("R3", Severity.MEDIUM, fact -> true));

        List<RaisedAlert> raised = engine.evaluate(List.of(outgoing(LOW_WALLET)));

        assertThat(savedCases).containsExactly(open);
        assertThat(savedCaseEvents).as("joining an open case sends no event").isEmpty();
        assertThat(raised).singleElement().satisfies(alert -> assertThat(alert.caseId()).isEqualTo(open.getId()));
        assertThat(open.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void twoRulesHittingTogetherShareOneCase() {
        RuleEngine engine = engine(rule("R1", Severity.MEDIUM, fact -> true),
                rule("R4", Severity.HIGH, fact -> true));

        List<RaisedAlert> raised = engine.evaluate(List.of(outgoing(LOW_WALLET)));

        assertThat(savedCases).hasSize(1);
        assertThat(raised).extracting(RaisedAlert::ruleCode).containsExactly("R1", "R4");
        assertThat(raised).extracting(RaisedAlert::caseId).containsOnly(savedCases.getFirst().getId());
    }

    @Test
    void aRuleThatAlertedWithinItsWindowIsNotRepeated() {
        AmlRule velocity = rule("R1", Severity.MEDIUM, fact -> true, Duration.ofMinutes(10));
        when(alerts.existsByWalletIdAndRuleCodeAndCreatedAtGreaterThanEqual(LOW_WALLET, "R1",
                NOW.minus(Duration.ofMinutes(10)))).thenReturn(true);

        assertThat(engine(velocity).evaluate(List.of(outgoing(LOW_WALLET)))).isEmpty();

        verify(cases, never()).save(any());
        verify(alerts, never()).save(any());
    }

    @Test
    void aRuleWithoutAWindowAlertsEveryTime() {
        RuleEngine engine = engine(rule("R3", Severity.MEDIUM, fact -> true));

        engine.evaluate(List.of(outgoing(LOW_WALLET)));
        engine.evaluate(List.of(outgoing(LOW_WALLET)));

        assertThat(savedAlerts).hasSize(2);
        verify(alerts, never()).existsByWalletIdAndRuleCodeAndCreatedAtGreaterThanEqual(any(), anyString(), any());
    }

    @Test
    void walletsAreLockedAndEvaluatedInAscendingIdOrder() {
        AmlRule everything = rule("R3", Severity.MEDIUM, fact -> true);
        TransactionFact out = TransactionFact.outgoingTransfer(UUID.randomUUID(), HIGH_WALLET, LOW_WALLET,
                UUID.randomUUID(), 100, "INR", NOW, NOW);
        TransactionFact in = TransactionFact.incomingTransfer(out.getEventId(), LOW_WALLET, HIGH_WALLET,
                out.getReferenceId(), 100, "INR", NOW, NOW);

        List<RaisedAlert> raised = engine(everything).evaluate(List.of(out, in));

        InOrder order = inOrder(lock, everything);
        order.verify(lock).lock(LOW_WALLET);
        order.verify(everything).evaluate(LOW_WALLET, in);
        order.verify(lock).lock(HIGH_WALLET);
        order.verify(everything).evaluate(HIGH_WALLET, out);
        assertThat(raised).extracting(RaisedAlert::walletId).containsExactly(LOW_WALLET, HIGH_WALLET);
    }

    @Test
    void eachRuleSeesTheFactsOwnWallet() {
        AmlRule rule = rule("R1", Severity.MEDIUM, fact -> false);
        TransactionFact fact = outgoing(HIGH_WALLET);

        engine(rule).evaluate(List.of(fact));

        verify(rule).evaluate(eq(HIGH_WALLET), eq(fact));
    }

    private RuleEngine engine(AmlRule... rules) {
        return new RuleEngine(List.of(rules), lock, cases, alerts, freezeRequests, caseEvents,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AmlRule rule(String code, Severity severity, Predicate<TransactionFact> fires) {
        return rule(code, severity, fires, null);
    }

    private static AmlRule rule(String code, Severity severity, Predicate<TransactionFact> fires, Duration window) {
        AmlRule rule = mock(AmlRule.class);
        when(rule.code()).thenReturn(code);
        when(rule.severity()).thenReturn(severity);
        when(rule.window()).thenReturn(Optional.ofNullable(window));
        when(rule.evaluate(any(), any())).thenAnswer(call -> fires.test(call.getArgument(1))
                ? Optional.of(new AlertDetails(code + " hit"))
                : Optional.empty());
        return rule;
    }

    private static TransactionFact outgoing(UUID walletId) {
        return TransactionFact.outgoingTransfer(UUID.randomUUID(), walletId, UUID.randomUUID(), UUID.randomUUID(),
                100, "INR", NOW, NOW);
    }
}
