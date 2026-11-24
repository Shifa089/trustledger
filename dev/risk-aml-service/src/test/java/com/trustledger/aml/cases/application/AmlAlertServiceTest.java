package com.trustledger.aml.cases.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.trustledger.aml.cases.application.CaseViews.AlertPage;
import com.trustledger.aml.cases.domain.Alert;
import com.trustledger.aml.cases.domain.AlertFilter;
import com.trustledger.aml.cases.domain.AlertRepository;
import com.trustledger.aml.rules.domain.Severity;
import com.trustledger.aml.shared.ErrorCode;
import com.trustledger.aml.shared.TrustLedgerException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class AmlAlertServiceTest {

    private final AlertRepository alerts = mock(AlertRepository.class);
    private final AmlAlertService service =
            new AmlAlertService(alerts, new CaseProperties(Duration.ofHours(24), 500, 100));

    @BeforeEach
    void emptyResult() {
        when(alerts.search(any(), any())).thenAnswer(call -> new PageImpl<Alert>(List.of(), call.getArgument(1), 0));
    }

    @Test
    void theFiltersArePassedOnAsGivenAndUnsetOnesStayNull() {
        UUID wallet = UUID.randomUUID();

        service.list("R2", Severity.HIGH, wallet, 3, 25);
        service.list(null, null, null, 0, 20);

        verify(alerts).search(new AlertFilter("R2", Severity.HIGH, wallet), PageRequest.of(3, 25));
        verify(alerts).search(AlertFilter.NONE, PageRequest.of(0, 20));
    }

    @Test
    void thePageEchoesTheRequestAndTheTotals() {
        doAnswer(call -> new PageImpl<Alert>(List.of(), call.getArgument(1), 41)).when(alerts).search(any(), any());

        AlertPage page = service.list(null, null, null, 1, 20);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.totalElements()).isEqualTo(41);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({"-1, 20", "0, 0", "0, 101"})
    void pagesOutsideTheLimitsAreRefusedBeforeAnyQuery(int page, int size) {
        assertThatThrownBy(() -> service.list(null, null, null, page, size))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(alerts);
    }

    @Test
    void theLargestAllowedPageIsTheConfiguredOne() {
        assertThat(service.list(null, null, null, 0, 100).size()).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "r1", "X1", "R", "R123", "R1 ", "R1;"})
    void malformedRuleCodesAreRefused(String ruleCode) {
        assertThatThrownBy(() -> service.list(ruleCode, null, null, 0, 20))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(alerts);
    }

    @ParameterizedTest
    @ValueSource(strings = {"R1", "R4", "R9", "R10"})
    void wellFormedRuleCodesAreSearched(String ruleCode) {
        service.list(ruleCode, null, null, 0, 20);

        verify(alerts).search(new AlertFilter(ruleCode, null, null), PageRequest.of(0, 20));
    }
}
