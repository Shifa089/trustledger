package com.trustledger.aml.cases.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.trustledger.aml.rules.domain.Severity;
import com.trustledger.events.AccountFreezeRequestedV1;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class FreezeRequestTest {

    private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
    private static final UUID WALLET = UUID.randomUUID();

    @Test
    void aHighAlertsRequestCarriesItsAlertCaseWalletAndRule() {
        Alert alert = savedAlert(Severity.HIGH, "R2");

        FreezeRequest request = FreezeRequest.forAlert(alert, "corr-1", NOW);

        assertThat(request.getAlertId()).isEqualTo(alert.getId());
        assertThat(request.getCaseId()).isEqualTo(alert.getCaseId());
        assertThat(request.getWalletId()).isEqualTo(WALLET);
        assertThat(request.getRuleCode()).isEqualTo("R2");
        assertThat(request.getCorrelationId()).isEqualTo("corr-1");
        assertThat(request.getRequestedAt()).isEqualTo(NOW);
        assertThat(request.getPublishedAt()).isNull();
        assertThat(request.getAttempts()).isZero();
    }

    @Test
    void theContractUsesTheRequestIdAsEventIdAndIsTheSameOnEveryCall() {
        FreezeRequest request = FreezeRequest.forAlert(savedAlert(Severity.HIGH, "R4"), null, NOW);
        UUID id = UUID.randomUUID();
        ReflectionTestUtils.setField(request, "id", id);

        AccountFreezeRequestedV1 event = request.toV1();

        assertThat(event).isEqualTo(new AccountFreezeRequestedV1(id, NOW, WALLET, request.getCaseId(), "R4"));
        assertThat(request.toV1()).isEqualTo(event);
    }

    @Test
    void aMediumAlertRequestsNoFreeze() {
        assertThatThrownBy(() -> FreezeRequest.forAlert(savedAlert(Severity.MEDIUM, "R1"), null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAlertMustBeSavedFirst() {
        Alert unsaved = Alert.raise(AmlCaseTest.saved(AmlCase.open(WALLET, NOW)), "R2", Severity.HIGH, "x", NOW);

        assertThatThrownBy(() -> FreezeRequest.forAlert(unsaved, null, NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("alert must be saved first");
    }

    private static Alert savedAlert(Severity severity, String rule) {
        Alert alert = Alert.raise(AmlCaseTest.saved(AmlCase.open(WALLET, NOW)), rule, severity, "details", NOW);
        ReflectionTestUtils.setField(alert, "id", UUID.randomUUID());
        return alert;
    }
}
