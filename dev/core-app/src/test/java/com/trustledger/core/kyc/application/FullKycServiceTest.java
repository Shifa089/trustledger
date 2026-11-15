package com.trustledger.core.kyc.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.trustledger.core.kyc.KycLevel;
import com.trustledger.core.kyc.domain.DocumentType;
import com.trustledger.core.kyc.domain.KycCaseRepository;
import com.trustledger.core.kyc.domain.KycStatus;
import com.trustledger.core.shared.ErrorCode;
import com.trustledger.core.shared.TrustLedgerException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.util.unit.DataSize;

class FullKycServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
    private static final UUID USER = UUID.randomUUID();

    private final KycCaseRepository cases = mock(KycCaseRepository.class);
    private FullKycService service;

    @BeforeEach
    void setUp() {
        KycDocumentProperties properties = new KycDocumentProperties(DataSize.ofMegabytes(5), 10,
                List.of(DocumentType.PAN_CARD, DocumentType.ADDRESS_PROOF), Duration.ofMinutes(5));
        service = new FullKycService(cases, properties, Clock.fixed(NOW, ZoneOffset.UTC));
        when(cases.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void opensASubmittedFullCaseAndListsTheRequiredDocuments() {
        when(cases.existsByUserIdAndLevelAndStatus(USER, KycLevel.MIN, KycStatus.APPROVED)).thenReturn(true);

        FullKycCaseView view = service.submit(USER, "12 Test Street, Bengaluru");

        assertThat(view.level()).isEqualTo(KycLevel.FULL);
        assertThat(view.status()).isEqualTo(KycStatus.SUBMITTED);
        assertThat(view.requiredDocuments()).containsExactly(DocumentType.PAN_CARD, DocumentType.ADDRESS_PROOF);
        assertThat(view.createdAt()).isEqualTo(NOW);
    }

    @Test
    void withoutApprovedMinimumKycIsKycRequired() {
        assertThatThrownBy(() -> service.submit(USER, "12 Test Street"))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.KYC_REQUIRED));
        verify(cases, never()).saveAndFlush(any());
    }

    @Test
    void aLiveFullCaseIsAlreadySubmitted() {
        when(cases.existsByUserIdAndLevelAndStatus(USER, KycLevel.MIN, KycStatus.APPROVED)).thenReturn(true);
        when(cases.existsByUserIdAndLevelAndStatusNot(USER, KycLevel.FULL, KycStatus.REJECTED)).thenReturn(true);

        assertThatThrownBy(() -> service.submit(USER, "12 Test Street"))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.KYC_ALREADY_SUBMITTED));
        verify(cases, never()).saveAndFlush(any());
    }

    @Test
    void aLostInsertRaceIsAlreadySubmittedNotA500() {
        when(cases.existsByUserIdAndLevelAndStatus(USER, KycLevel.MIN, KycStatus.APPROVED)).thenReturn(true);
        when(cases.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_kyc_cases_user_level_live"));

        assertThatThrownBy(() -> service.submit(USER, "12 Test Street"))
                .isInstanceOfSatisfying(TrustLedgerException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.KYC_ALREADY_SUBMITTED));
    }
}
