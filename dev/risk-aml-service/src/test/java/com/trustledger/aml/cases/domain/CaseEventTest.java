package com.trustledger.aml.cases.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.events.AmlCaseStatusChangedV1;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** The case-event outbox row and its Kafka contract (FR-PR-2: core-app refuses erasure while a case is open). */
class CaseEventTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final UUID WALLET = UUID.randomUUID();
    private static final UUID OFFICER = UUID.randomUUID();

    @Test
    void anOpenedCaseIsSentAsOpen() {
        AmlCase amlCase = saved(AmlCase.open(WALLET, NOW));

        CaseEvent event = CaseEvent.of(amlCase, "corr-1", NOW);
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        AmlCaseStatusChangedV1 v1 = event.toV1();

        assertThat(v1.eventId()).isEqualTo(event.getId());
        assertThat(v1.caseId()).isEqualTo(amlCase.getId());
        assertThat(v1.walletId()).isEqualTo(WALLET);
        assertThat(v1.status()).isEqualTo("OPEN");
        assertThat(v1.open()).isTrue();
        assertThat(v1.occurredAt()).isEqualTo(NOW);
        assertThat(event.getCorrelationId()).isEqualTo("corr-1");
    }

    @Test
    void aFinishedCaseIsSentAsNoLongerOpen() {
        AmlCase closed = saved(AmlCase.open(WALLET, NOW));
        closed.assign(OFFICER, NOW);
        closed.closeAsFalsePositive(OFFICER, "Salary payments, verified.", NOW);
        AmlCase escalated = saved(AmlCase.open(WALLET, NOW));
        escalated.assign(OFFICER, NOW);
        escalated.escalate(OFFICER, "STR filed.", NOW);

        assertThat(withId(CaseEvent.of(closed, null, NOW)).toV1())
                .satisfies(v1 -> assertThat(v1.status()).isEqualTo("CLOSED_FALSE_POSITIVE"))
                .satisfies(v1 -> assertThat(v1.open()).isFalse());
        assertThat(withId(CaseEvent.of(escalated, null, NOW)).toV1())
                .satisfies(v1 -> assertThat(v1.status()).isEqualTo("ESCALATED"))
                .satisfies(v1 -> assertThat(v1.open()).isFalse());
    }

    @Test
    void assignmentIsNotSent() {
        AmlCase investigating = saved(AmlCase.open(WALLET, NOW));
        investigating.assign(OFFICER, NOW);

        assertThatThrownBy(() -> CaseEvent.of(investigating, null, NOW)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnsafeCorrelationIdIsDropped() {
        AmlCase amlCase = saved(AmlCase.open(WALLET, NOW));

        assertThat(CaseEvent.of(amlCase, "bad id\r\nX-Injected: 1", NOW).getCorrelationId()).isNull();
        assertThat(CaseEvent.of(amlCase, "x".repeat(65), NOW).getCorrelationId()).isNull();
    }

    @Test
    void anUnsavedCaseIsRefused() {
        assertThatThrownBy(() -> CaseEvent.of(AmlCase.open(WALLET, NOW), null, NOW))
                .isInstanceOf(NullPointerException.class);
    }

    private static AmlCase saved(AmlCase amlCase) {
        ReflectionTestUtils.setField(amlCase, "id", UUID.randomUUID());
        return amlCase;
    }

    private static CaseEvent withId(CaseEvent event) {
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        return event;
    }
}
