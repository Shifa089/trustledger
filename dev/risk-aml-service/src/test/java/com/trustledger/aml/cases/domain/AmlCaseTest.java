package com.trustledger.aml.cases.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.aml.rules.domain.Severity;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AmlCaseTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final UUID WALLET = UUID.randomUUID();

    @Test
    void aNewCaseIsOpenForItsWallet() {
        AmlCase amlCase = AmlCase.open(WALLET, NOW);

        assertThat(amlCase.getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(amlCase.isActive()).isTrue();
        assertThat(amlCase.getWalletId()).isEqualTo(WALLET);
        assertThat(amlCase.getCreatedAt()).isEqualTo(NOW);
        assertThat(amlCase.getUpdatedAt()).isEqualTo(NOW);
        assertThat(amlCase.getResolution()).isNull();
    }

    @Test
    void anAlertMovesUpdatedAt() {
        AmlCase amlCase = AmlCase.open(WALLET, NOW);

        amlCase.alertRaised(NOW.plusSeconds(60));

        assertThat(amlCase.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(amlCase.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void onlyOpenAndInvestigatingAreActive() {
        assertThat(CaseStatus.OPEN.isActive()).isTrue();
        assertThat(CaseStatus.INVESTIGATING.isActive()).isTrue();
        assertThat(CaseStatus.CLOSED_FALSE_POSITIVE.isActive()).isFalse();
        assertThat(CaseStatus.ESCALATED.isActive()).isFalse();
    }

    @Test
    void aFinishedCaseTakesNoAlerts() {
        AmlCase amlCase = saved(AmlCase.open(WALLET, NOW));
        ReflectionTestUtils.setField(amlCase, "status", CaseStatus.ESCALATED);

        assertThatThrownBy(() -> amlCase.alertRaised(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Alert.raise(amlCase, "R1", Severity.MEDIUM, "x", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAlertBelongsToItsCaseAndItsWallet() {
        AmlCase amlCase = saved(AmlCase.open(WALLET, NOW));

        Alert alert = Alert.raise(amlCase, "R2", Severity.HIGH, "3 transactions", NOW);

        assertThat(alert.getCaseId()).isEqualTo(amlCase.getId());
        assertThat(alert.getWalletId()).isEqualTo(WALLET);
        assertThat(alert.getRuleCode()).isEqualTo("R2");
        assertThat(alert.getSeverity()).isEqualTo(Severity.HIGH);
        assertThat(alert.getDetails()).isEqualTo("3 transactions");
        assertThat(alert.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void anAlertNeedsASavedCase() {
        assertThatThrownBy(() -> Alert.raise(AmlCase.open(WALLET, NOW), "R1", Severity.MEDIUM, "x", NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("case must be saved first");
    }

    /** What persisting does: give the case its ID. */
    static AmlCase saved(AmlCase amlCase) {
        ReflectionTestUtils.setField(amlCase, "id", UUID.randomUUID());
        return amlCase;
    }
}
